// ============================================================================
//  virtual_hal.cpp — Phase 4a VirtualHAL implementation (MP-HAL01 / MP-HAL02)
// ============================================================================
//
//  Deterministic adversarial audio test double: simulated DAC presentation
//  clock domain layered on top of the production `AudioSink` SPSC ring.
//  See native/include/virtual_hal.h for the full model documentation.
//
//  RT-discipline note: the SIMULATOR itself is a cooperative test harness —
//  it may allocate in its own setup paths (ring rebuilds, scenario feed
//  buffers). The object under test, `AudioSink::ReadFramesF32`, keeps its
//  documented zero-allocation contract; the suite proves it with AllocGuard.
// ============================================================================

#include "../include/virtual_hal.h"

#include <cmath>
#include <cstring>
#include <memory>
#include <vector>

namespace streamify {
namespace hal {

namespace {

// Chunk sizes the double models (the directive's canonical HAL quanta).
inline bool IsValidCallbackChunk(size_t frames) {
    return frames == 256 || frames == 512 || frames == 1024;
}

}  // namespace

// ---------------------------------------------------------------------------
// DacClockInterpolator
// ---------------------------------------------------------------------------

void DacClockInterpolator::Update(const VirtualDacClock& clock) {
    if (has_sample_) {
        // Epoch-reset detection (master plan invariant 6: a device rate
        // change is a teardown/rebuild — the frame counter restarts and the
        // rate moves). Never extrapolate across the discontinuity: drop the
        // stale baseline and re-latch on this sample.
        const bool rate_changed = clock.sample_rate != rate_;
        const bool frames_regressed =
            clock.dac_frame_position < baseline_frames_;
        const bool time_regressed = clock.monotonic_ns < baseline_ns_;
        if (rate_changed || frames_regressed || time_regressed) {
            // Fall through: re-baseline on the incoming sample below.
        }
    }
    has_sample_ = true;
    baseline_frames_ = clock.dac_frame_position;
    baseline_ns_ = clock.monotonic_ns;
    rate_ = clock.sample_rate;
}

bool DacClockInterpolator::PositionFramesAt(uint64_t now_ns,
                                            uint64_t* out_frames) const {
    if (!has_sample_ || out_frames == nullptr) return false;
    // est(t) = F + (t - T) * rate / 1e9, rounded-to-nearest, pure u64 math.
    // Magnitude check: a 100 s staleness window at 192 kHz is
    // 1e11 * 192000 = 1.9e16 — comfortably inside u64.
    const uint64_t delta_ns =
        now_ns > baseline_ns_ ? now_ns - baseline_ns_ : 0u;
    const uint64_t advanced =
        (delta_ns * static_cast<uint64_t>(rate_) + 500'000'000ull) /
        1'000'000'000ull;
    *out_frames = baseline_frames_ + advanced;
    return true;
}

bool DacClockInterpolator::PositionMsAt(uint64_t now_ns, double* out_ms) const {
    uint64_t frames = 0;
    if (!PositionFramesAt(now_ns, &frames)) return false;
    *out_ms = static_cast<double>(frames) * 1000.0 / static_cast<double>(rate_);
    return true;
}

// ---------------------------------------------------------------------------
// VirtualHalSink
// ---------------------------------------------------------------------------

VirtualHalSink::VirtualHalSink(const Config& config)
    : channels_(config.channels),
      callback_frames_(config.callback_frames),
      capacity_frames_(config.capacity_frames),
      modes_(config.modes),
      jitter_fraction_(config.jitter_fraction),
      timestamp_quantum_ns_(config.timestamp_quantum_ns),
      drift_ppm_(config.drift_ppm),
      rng_(config.rng_seed) {
    // ---- validation (mirrors the AudioSink envelope + HAL quantum rules) --
    const bool rate_ok = config.sample_rate >= kMinRate && config.sample_rate <= kMaxRate;
    const bool channels_ok = config.channels == 1 || config.channels == 2;
    const bool capacity_ok = config.capacity_frames >= 64 &&
                             config.capacity_frames <= (1u << 22);
    const bool chunk_ok = IsValidCallbackChunk(config.callback_frames);
    const bool jitter_ok = config.jitter_fraction >= 0.0 && config.jitter_fraction <= 1.0;
    const bool drift_ok = config.drift_ppm >= -2000 && config.drift_ppm <= 2000;
    const bool quantum_ok = config.timestamp_quantum_ns <= 100'000'000ull;
    if (!rate_ok || !channels_ok || !capacity_ok || !chunk_ok || !jitter_ok ||
        !drift_ok || !quantum_ok) {
        return;  // stays invalid; every operation no-ops
    }

    rate_ = config.sample_rate;
    RebuildRing_(rate_);
    if (sink_ == nullptr || !sink_->valid()) {
        sink_.reset();
        return;
    }
    scratch_.assign(callback_frames_ * channels_, 0.0f);
    last_clock_ = VirtualDacClock{0, rate_, 0};
}

size_t VirtualHalSink::WriteProducerFrames(const float* samples,
                                           size_t sample_count) {
    if (sink_ == nullptr || samples == nullptr) return 0;
    const size_t whole = sample_count - sample_count % channels_;
    if (whole == 0) return 0;
    const size_t consumed = sink_->WriteFloat(samples, whole);
    if (consumed < whole) {
        overrun_events_.fetch_add(1, std::memory_order_relaxed);  // producer flooded the ring (short write)
    }
    frames_written_.fetch_add(consumed / channels_, std::memory_order_relaxed);
    return consumed;
}

VirtualHalCallback VirtualHalSink::RunCallback(float* dst, size_t dst_frames) {
    VirtualHalCallback cb;
    cb.requested_frames = dst_frames;
    cb.epoch = epoch_;
    if (sink_ == nullptr || dst == nullptr || dst_frames == 0) {
        cb.starved = true;
        cb.clock = last_clock_;
        return cb;
    }

    // Ground truth for this quantum: the DAC will present frames
    // [S, S + Q) starting at t(S).
    const uint64_t first_frame = cursor_frames_;
    const size_t quantum = dst_frames;

    // Pull real audio from the hardened ring (the production edge the
    // CoreAudio / AAudio adapters use); silence-fill any starved tail.
    const size_t got = sink_->ReadFramesF32(dst, quantum);
    if (got < quantum) {
        std::memset(dst + got * channels_, 0,
                    (quantum - got) * channels_ * sizeof(float));
        cb.starved = true;
        ++starved_callbacks_;
        frames_silenced_ += quantum - got;
    }

    // The CONSISTENT reported pair: (first frame of the quantum, the
    // monotonic instant it presents), quantized onto the HAL's timestamp
    // grid. Fire-time jitter never contaminates this pair.
    const uint64_t t_true = static_cast<uint64_t>(TimeAtFrameNs(first_frame));
    cb.clock = VirtualDacClock{first_frame, rate_,
                               QuantizeTimestamp_(t_true)};
    cb.fire_time_ns = FireTimeFor_(first_frame, quantum);
    cb.supplied_frames = got;

    last_clock_ = cb.clock;
    cursor_frames_ += quantum;
    frames_pulled_ += got;
    ++callbacks_run_;
    return cb;
}

VirtualHalCallback VirtualHalSink::RunCallback() {
    return RunCallback(scratch_.data(), callback_frames_);
}

bool VirtualHalSink::ReconfigureRate(uint32_t new_rate) {
    if (sink_ == nullptr) return false;
    if (new_rate < kMinRate || new_rate > kMaxRate) return false;

    // Teardown-and-rebuild (master plan invariant 6): the buffered stream
    // is dropped, the frame epoch restarts at the current presentation
    // instant and the clock domain moves to the new rate.
    const uint64_t now_ns = SimTimeNs();
    RebuildRing_(new_rate);
    if (sink_ == nullptr) return false;

    ++epoch_;
    rate_ = new_rate;
    rate_true_ = static_cast<double>(new_rate) *
                 (1.0 + static_cast<double>(drift_ppm_) * 1e-6);
    epoch_origin_ns_ = now_ns;
    cursor_frames_ = 0;
    return true;
}

VirtualHalStats VirtualHalSink::Stats() const {
    VirtualHalStats st;
    st.frames_written = frames_written_.load(std::memory_order_relaxed);
    st.frames_pulled = frames_pulled_;
    st.frames_silenced = frames_silenced_;
    st.starved_callbacks = starved_callbacks_;
    st.overrun_events = overrun_events_.load(std::memory_order_relaxed);
    st.frames_flushed = frames_flushed_;
    st.callbacks_run = callbacks_run_;
    st.epoch = epoch_;
    st.capacity_frames = capacity_frames_;
    if (sink_ != nullptr) {
        sink::SinkStats rs;
        sink_->GetStats(&rs);
        st.occupancy_frames =
            static_cast<size_t>(rs.frames_written - rs.frames_read);
    }
    return st;
}

uint64_t VirtualHalSink::SimTimeNs() const {
    return static_cast<uint64_t>(TimeAtFrameNs(cursor_frames_));
}

double VirtualHalSink::TruePositionFramesAt(uint64_t now_ns) const {
    if (now_ns <= epoch_origin_ns_) return 0.0;
    // Exact epoch mapping in f64 (mul/div only — bit-deterministic).
    const double elapsed = static_cast<double>(now_ns - epoch_origin_ns_);
    return elapsed * rate_true_ / 1e9;
}

VirtualHalSink::ScenarioReport VirtualHalSink::RunAdversarialScenario(
    const AdversarialProfile& profile) {
    ScenarioReport report;
    if (sink_ == nullptr) return report;

    const size_t quantum = callback_frames_;
    const size_t target_occupancy =
        profile.feed_ahead_quanta * quantum;  // nominal ring fill target

    // Producer feed buffer: worst of (top-up block, flood attempt). One
    // allocation for the whole scenario — the harness may allocate.
    const size_t feed_frames =
        std::max<size_t>(target_occupancy, capacity_frames_ + quantum);
    std::vector<float> feed(feed_frames * channels_, 0.0f);
    uint64_t fed_frame_cursor = 0;  // deterministic ramp phase

    // Rate-change schedule: evenly spaced, cycling the non-canonical
    // hardware rates back to canonical.
    const uint32_t rate_table[3] = {44100, 96000, 48000};
    size_t rate_idx = 0;
    size_t rate_changes_done = 0;
    const size_t rate_stride = profile.rate_changes > 0
                                   ? profile.callbacks / (profile.rate_changes + 1)
                                   : 0;

    DacClockInterpolator interp;       // the estimator under validation
    DacClockInterpolator fire_interp;  // negative control (naive fire-time)

    for (size_t k = 0; k < profile.callbacks; ++k) {
        // ---- scheduled mid-stream rate reconfiguration --------------------
        if (profile.rate_changes > 0 && rate_stride > 0 &&
            rate_changes_done < profile.rate_changes &&
            k > 0 && k % rate_stride == 0) {
            if (ReconfigureRate(rate_table[rate_idx % 3])) {
                ++rate_idx;
                ++rate_changes_done;
            }
        }

        // ---- scripted producer behaviour ------------------------------------
        const bool starving =
            profile.starve_every > profile.starve_burst &&
            (k % profile.starve_every) >=
                (profile.starve_every - profile.starve_burst);
        const bool flooding =
            profile.flood_every > 0 && k > 0 && k % profile.flood_every == 0;

        if (!starving) {
            // Nominal feeding: top the ring up to the target occupancy.
            const VirtualHalStats st = Stats();
            if (st.occupancy_frames < target_occupancy) {
                size_t want = target_occupancy - st.occupancy_frames;
                if (want > feed_frames) want = feed_frames;
                for (size_t f = 0; f < want * channels_; ++f) {
                    // Deterministic ramp filler (phase continuity is the
                    // producer's, not the double's, concern here).
                    feed[f] = static_cast<float>(
                        (static_cast<double>((fed_frame_cursor + f / channels_) % 4096) /
                         4095.0) * 2.0 - 1.0);
                }
                const size_t consumed =
                    WriteProducerFrames(feed.data(), want * channels_);
                fed_frame_cursor += consumed / channels_;
            }
        }
        if (flooding) {
            // Overrun injection: attempt strictly more than the ring can
            // hold (producer floods the ringbuffer).
            const size_t attempt = capacity_frames_ + quantum;
            for (size_t f = 0; f < attempt * channels_; ++f) {
                feed[f] = static_cast<float>(
                    (static_cast<double>((fed_frame_cursor + f / channels_) % 4096) /
                     4095.0) * 2.0 - 1.0);
            }
            const size_t consumed =
                WriteProducerFrames(feed.data(), attempt * channels_);
            fed_frame_cursor += consumed / channels_;
        }

        // ---- one simulated hardware callback --------------------------------
        const VirtualHalCallback cb = RunCallback();
        interp.Update(cb.clock);
        // Negative control: the naive estimator that pairs the frame
        // counter with the FIRE time instead of the presentation time.
        fire_interp.Update(
            VirtualDacClock{cb.clock.dac_frame_position, cb.clock.sample_rate,
                            cb.fire_time_ns});

        if (cb.starved) ++report.starved_callbacks;

        // ---- interpolation acceptance checks ---------------------------------
        // Deterministic query instants spread across the quantum (and just
        // past its end: the staleness window a real UI reader hits).
        const uint64_t quantum_ns_now = static_cast<uint64_t>(
            static_cast<double>(quantum) * 1e9 / rate_true_);
        for (size_t q = 1; q <= profile.queries_per_callback; ++q) {
            const uint64_t now =
                cb.clock.monotonic_ns +
                (quantum_ns_now * q) / profile.queries_per_callback;
            uint64_t est = 0;
            if (interp.PositionFramesAt(now, &est)) {
                const double truth = TruePositionFramesAt(now);
                const double err_ms =
                    std::fabs(static_cast<double>(est) - truth) * 1000.0 /
                    rate_true_;
                if (err_ms > report.max_interpolation_error_ms) {
                    report.max_interpolation_error_ms = err_ms;
                }
            }
            uint64_t fire_est = 0;
            if (fire_interp.PositionFramesAt(now, &fire_est)) {
                const double truth = TruePositionFramesAt(now);
                const double err_ms =
                    std::fabs(static_cast<double>(fire_est) - truth) * 1000.0 /
                    rate_true_;
                if (err_ms > report.max_firetime_error_ms) {
                    report.max_firetime_error_ms = err_ms;
                }
            }
        }
    }

    report.callbacks_run = profile.callbacks;
    report.rate_changes = rate_changes_done;
    report.overrun_events = overrun_events_.load(std::memory_order_relaxed);
    report.frames_written = frames_written_.load(std::memory_order_relaxed);
    report.frames_pulled = frames_pulled_;

    // ---- frame conservation across the whole run (incl. epoch rebuilds) ----
    const VirtualHalStats st = Stats();
    report.conservation_ok =
        frames_written_.load(std::memory_order_relaxed) ==
        frames_pulled_ + st.occupancy_frames + frames_flushed_;
    return report;
}

// ---------------------------------------------------------------------------
// internals
// ---------------------------------------------------------------------------

void VirtualHalSink::RebuildRing_(uint32_t rate) {
    // Account the buffered frames of the outgoing ring as flushed (the
    // teardown half of teardown-and-rebuild).
    if (sink_ != nullptr) {
        sink::SinkStats st;
        sink_->GetStats(&st);
        frames_flushed_ += st.frames_written - st.frames_read;
    }
    sink::AudioSink::Config cfg;
    cfg.sample_rate = rate;
    cfg.channels = channels_;
    cfg.capacity_frames = capacity_frames_;
    sink_ = std::make_unique<sink::AudioSink>(cfg);
    if (sink_ != nullptr && !sink_->valid()) {
        sink_.reset();
        return;
    }
    // A rebuilt epoch starts a fresh clock domain at the same drift model.
    rate_true_ =
        static_cast<double>(rate) * (1.0 + static_cast<double>(drift_ppm_) * 1e-6);
    // epoch_origin_ns_ / cursor_frames_ are set by the caller (constructor
    // starts at t=0 frame 0; ReconfigureRate carries the timeline forward).
}

uint64_t VirtualHalSink::FireTimeFor_(uint64_t first_frame, size_t quantum) {
    // Pull HALs fire roughly one full buffer period ahead of the deadline.
    // `jitter_fraction` is the PEAK CALLBACK-INTERVAL variance: the lead
    // varies by ±(jitter_fraction / 2) × period so consecutive fire
    // intervals differ by up to ±jitter_fraction × period. The draw is
    // integer-seeded SplitMix64 — callback-identical everywhere.
    double lead_ns =
        static_cast<double>(quantum) * 1e9 / rate_true_;
    if (modes_.has(AdversarialModes::kTimingJitter)) {
        lead_ns *= 1.0 + 0.5 * jitter_fraction_ * rng_.NextSymmetric();
    }
    const double deadline =
        static_cast<double>(epoch_origin_ns_) +
        static_cast<double>(first_frame) * 1e9 / rate_true_;
    double fire = deadline - lead_ns;
    if (fire < 0.0) fire = 0.0;
    return static_cast<uint64_t>(fire);
}

uint64_t VirtualHalSink::QuantizeTimestamp_(uint64_t t) const {
    if (timestamp_quantum_ns_ == 0) return t;
    return (t / timestamp_quantum_ns_) * timestamp_quantum_ns_;
}

}  // namespace hal
}  // namespace streamify
