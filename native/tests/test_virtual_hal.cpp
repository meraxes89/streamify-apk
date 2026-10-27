// ============================================================================
//  test_virtual_hal.cpp — Phase 4a VirtualHAL verification suite
//  (native/tests/test_virtual_hal.cpp — MP-HAL01 / MP-HAL02)
// ============================================================================
//
//  Standalone CTest binary (virtual_hal_test_suite). Verifies the
//  deterministic adversarial audio test double against the REAL hardened
//  `AudioSink` SPSC ring:
//
//    A. Config validation — chunk-size, rate, channel and jitter bounds.
//    B. Chunk sweep 256/512/1024 — quantum-exact pulls, exact clock
//       advancement (frames AND nanoseconds), zero-allocation callback
//       path (AllocGuard).
//    C. Steady stream — ramp integrity through the ring, frame
//       conservation, zero starvation.
//    D. Underrun injection — starved callbacks, digital-silence tails,
//       position advances through silence, telemetry accounting.
//    E. Overrun injection — producer floods: short writes, overrun
//       counters, conservation, zero dropped frames.
//    F. Rate reconfiguration — teardown/rebuild epoch semantics, flushed
//       accounting, conservation across epochs.
//    G. DacPresentationClock interpolation acceptance (<5 ms):
//       exact and quantized timestamps, ±10 % jitter, 200 ppm drift —
//       plus the fire-time NEGATIVE CONTROL proving the bound has teeth.
//    H. Full adversarial scenario — every mode at once, all chunk sizes.
//    I. Threaded SPSC — producer thread + callback driver on the real
//       ring with sequence integrity.
// ============================================================================

#include <atomic>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <thread>
#include <vector>

#include "../include/virtual_hal.h"

#include "AllocGuard.h"

using streamify::hal::AdversarialModes;
using streamify::hal::DacClockInterpolator;
using streamify::hal::VirtualDacClock;
using streamify::hal::VirtualHalCallback;
using streamify::hal::VirtualHalSink;

namespace {

int g_passed = 0;
int g_failed = 0;

void check(bool ok, const char* what) {
    if (ok) {
        ++g_passed;
    } else {
        ++g_failed;
        std::printf("    FAILED: %s\n", what);
    }
}

void check_label(bool ok, const char* label_fmt, double value) {
    char label[192];
    std::snprintf(label, sizeof(label), label_fmt, value);
    check(ok, label);
}

// Deterministic stereo ramp frame: value depends only on the absolute
// frame index, so sequence integrity is checkable after any pull pattern.
inline float ramp_left(uint64_t frame) {
    return static_cast<float>(
        static_cast<double>(frame % 4096) / 4095.0 * 2.0 - 1.0);
}
inline float ramp_right(uint64_t frame) { return -ramp_left(frame); }

void fill_ramp(std::vector<float>& buf, uint64_t first_frame, size_t frames) {
    buf.resize(frames * 2);
    for (size_t f = 0; f < frames; ++f) {
        buf[f * 2] = ramp_left(first_frame + f);
        buf[f * 2 + 1] = ramp_right(first_frame + f);
    }
}

// ---------------------------------------------------------------------------
// A. Config validation
// ---------------------------------------------------------------------------
void test_config_validation() {
    std::printf("  [A] config validation\n");

    {  // valid defaults
        VirtualHalSink::Config cfg;
        VirtualHalSink sink(cfg);
        check(sink.valid(), "default config is valid");
        check(sink.sample_rate() == 48000, "default rate 48 kHz");
        check(sink.callback_frames() == 512, "default chunk 512");
    }
    {  // the three canonical chunk sizes are accepted
        for (size_t chunk : {size_t{256}, size_t{512}, size_t{1024}}) {
            VirtualHalSink::Config cfg;
            cfg.callback_frames = chunk;
            VirtualHalSink sink(cfg);
            check(sink.valid(), "chunk 256/512/1024 accepted");
        }
    }
    {  // non-canonical chunks rejected
        for (size_t chunk :
             {size_t{0}, size_t{128}, size_t{768}, size_t{2048}}) {
            VirtualHalSink::Config cfg;
            cfg.callback_frames = chunk;
            VirtualHalSink sink(cfg);
            check(!sink.valid(), "non-canonical chunk rejected");
        }
    }
    {  // rate envelope
        for (uint32_t rate : {uint32_t{7999}, uint32_t{192001}, uint32_t{0}}) {
            VirtualHalSink::Config cfg;
            cfg.sample_rate = rate;
            VirtualHalSink sink(cfg);
            check(!sink.valid(), "out-of-envelope rate rejected");
        }
        for (uint32_t rate :
             {uint32_t{8000}, uint32_t{44100}, uint32_t{192000}}) {
            VirtualHalSink::Config cfg;
            cfg.sample_rate = rate;
            VirtualHalSink sink(cfg);
            check(sink.valid(), "in-envelope rate accepted");
        }
    }
    {  // channels / capacity / jitter / drift bounds
        VirtualHalSink::Config cfg;
        cfg.channels = 3;
        VirtualHalSink bad_ch(cfg);
        check(!bad_ch.valid(), "3 channels rejected");

        VirtualHalSink::Config cfg2;
        cfg2.capacity_frames = 16;  // below ring minimum
        VirtualHalSink bad_cap(cfg2);
        check(!bad_cap.valid(), "tiny capacity rejected");

        VirtualHalSink::Config cfg3;
        cfg3.jitter_fraction = 1.5;
        VirtualHalSink bad_jit(cfg3);
        check(!bad_jit.valid(), "jitter > 1.0 rejected");

        VirtualHalSink::Config cfg4;
        cfg4.drift_ppm = 5000;
        VirtualHalSink bad_drift(cfg4);
        check(!bad_drift.valid(), "wild drift rejected");
    }
}

// ---------------------------------------------------------------------------
// B. Chunk sweep: quantum-exact pulls + exact clock advancement
// ---------------------------------------------------------------------------
void test_chunk_sweep() {
    std::printf("  [B] callback chunk sweep 256/512/1024\n");
    for (size_t chunk : {size_t{256}, size_t{512}, size_t{1024}}) {
        VirtualHalSink::Config cfg;
        cfg.callback_frames = chunk;
        cfg.capacity_frames = 16384;  // >= 8 quanta for every chunk
        VirtualHalSink sink(cfg);
        check(sink.valid(), "sweep config valid");

        // Fill enough ramp for 8 callbacks, then drive 8 callbacks.
        std::vector<float> feed;
        fill_ramp(feed, 0, chunk * 8);
        const size_t consumed =
            sink.WriteProducerFrames(feed.data(), feed.size());
        check(consumed == feed.size(), "feed fully consumed");

        std::vector<float> dst(chunk * 2, 1.0f);
        uint64_t expect_frames = 0;
        for (size_t cb = 0; cb < 8; ++cb) {
            const VirtualHalCallback rep = sink.RunCallback(dst.data(), chunk);
            check(rep.requested_frames == chunk, "requested == chunk");
            check(rep.supplied_frames == chunk, "fully supplied");
            check(!rep.starved, "not starved");
            check(rep.clock.dac_frame_position == expect_frames,
                  "clock frame == quantum start");
            // Exact timestamp advancement: chunk frames at 48 kHz.
            const uint64_t expect_ns = expect_frames * 1000000000ull / 48000ull;
            check(rep.clock.monotonic_ns == expect_ns,
                  "exact monotonic presentation ts");
            expect_frames += chunk;
        }

        // Data integrity: the LAST quantum's ramp came through untouched.
        bool intact = true;
        for (size_t f = 0; f < chunk; ++f) {
            const float want_l = ramp_left((chunk * 7) + f);
            const float want_r = ramp_right((chunk * 7) + f);
            if (dst[f * 2] != want_l || dst[f * 2 + 1] != want_r) {
                intact = false;
                break;
            }
        }
        check(intact, "quantum ramp intact (bit-exact f32 passthrough)");

        // Zero-allocation audit on the callback hot path.
        {
            streamify_test::AllocGuard guard;
            const VirtualHalCallback rep = sink.RunCallback(dst.data(), chunk);
            check(rep.requested_frames == chunk, "guard callback ran");
            check(guard.count() == 0, "RunCallback allocates nothing");
        }

        const auto st = sink.Stats();
        check(st.frames_written == chunk * 8, "written accounting");
        check(st.frames_pulled == chunk * 8, "pulled accounting");
    }
}

// ---------------------------------------------------------------------------
// C. Steady stream: ramp integrity + conservation
// ---------------------------------------------------------------------------
void test_steady_stream() {
    std::printf("  [C] steady stream conservation\n");
    VirtualHalSink::Config cfg;
    cfg.callback_frames = 512;
    VirtualHalSink sink(cfg);

    // Quantum-aligned total (90 x 512) and a matched 512-in/512-out
    // cadence after a half-capacity prefill: the ring never fills (no
    // overrun events) and never drains (no starvation) — the contract a
    // healthy producer/consumer pair keeps in steady state.
    const size_t total_frames = 46080;  // 90 quanta @48k
    std::vector<float> feed;
    fill_ramp(feed, 0, 4096);  // prefill: half capacity
    size_t fed = 4096;
    check(sink.WriteProducerFrames(feed.data(), feed.size()) == feed.size(),
          "prefill fully consumed");
    uint64_t pulled = 0;
    std::vector<float> dst(512 * 2);
    bool intact = true;

    while (pulled < total_frames) {
        if (fed < total_frames) {
            const size_t n = std::min<size_t>(512, total_frames - fed);
            fill_ramp(feed, fed, n);
            const size_t consumed =
                sink.WriteProducerFrames(feed.data(), n * 2);
            if (consumed != n * 2) intact = false;  // must always fit
            fed += consumed / 2;
        }
        const VirtualHalCallback rep = sink.RunCallback(dst.data(), 512);
        for (size_t f = 0; f < rep.supplied_frames; ++f) {
            const uint64_t idx = pulled + f;
            if (dst[f * 2] != ramp_left(idx) || dst[f * 2 + 1] != ramp_right(idx)) {
                intact = false;
            }
        }
        pulled += rep.supplied_frames;
    }
    check(intact, "92k-sample ramp sequence intact through the ring");
    check(pulled == total_frames, "all frames pulled");

    const auto st = sink.Stats();
    check(st.starved_callbacks == 0, "no starvation in steady state");
    check(st.overrun_events == 0, "no overruns in steady state");
    check(st.frames_written == total_frames, "written accounting");
    check(st.frames_pulled == total_frames, "pulled accounting");
    check(st.occupancy_frames == 0, "ring drained exactly");
}

// ---------------------------------------------------------------------------
// D. Underrun injection
// ---------------------------------------------------------------------------
void test_underrun_injection() {
    std::printf("  [D] underrun injection\n");
    VirtualHalSink::Config cfg;
    cfg.callback_frames = 512;
    VirtualHalSink sink(cfg);

    std::vector<float> feed;
    fill_ramp(feed, 0, 1024);
    sink.WriteProducerFrames(feed.data(), feed.size());

    // Two full callbacks consume the 1024 buffered frames.
    std::vector<float> dst(512 * 2, 1.0f);
    sink.RunCallback(dst.data(), 512);
    sink.RunCallback(dst.data(), 512);

    // Now starve: three callbacks with an empty ring.
    const uint64_t clock_before = sink.last_clock().dac_frame_position;
    for (int i = 0; i < 3; ++i) {
        const VirtualHalCallback rep = sink.RunCallback(dst.data(), 512);
        check(rep.starved, "starved callback flagged");
        check(rep.supplied_frames == 0, "no real audio supplied");
        bool silent = true;
        for (size_t f = 0; f < 512 * 2; ++f) {
            if (dst[f] != 0.0f) silent = false;
        }
        check(silent, "starved quantum is digital silence");
    }
    // The DAC presents through the silence: position advanced by 3 quanta.
    check(sink.last_clock().dac_frame_position == clock_before + 3 * 512,
          "position advances through silence");

    const auto st = sink.Stats();
    check(st.starved_callbacks == 3, "underrun callback accounting");
    check(st.frames_silenced == 3 * 512, "silenced frame accounting");

    // Producer resumes: audio flows again from the right sequence point.
    fill_ramp(feed, 1024, 512);  // continue the ramp
    sink.WriteProducerFrames(feed.data(), feed.size());
    const VirtualHalCallback rep = sink.RunCallback(dst.data(), 512);
    check(!rep.starved, "recovered after feed");
    check(dst[0] == ramp_left(1024) && dst[1] == ramp_right(1024),
          "sequence resumes exactly where it left off");
}

// ---------------------------------------------------------------------------
// E. Overrun injection
// ---------------------------------------------------------------------------
void test_overrun_injection() {
    std::printf("  [E] overrun injection\n");
    VirtualHalSink::Config cfg;
    cfg.callback_frames = 512;
    cfg.capacity_frames = 4096;
    VirtualHalSink sink(cfg);

    std::vector<float> feed;
    fill_ramp(feed, 0, 4096);
    const size_t consumed1 =
        sink.WriteProducerFrames(feed.data(), feed.size());
    check(consumed1 == feed.size(), "ring filled to capacity");

    // Flood: strictly more than the ring can hold.
    std::vector<float> flood;
    fill_ramp(flood, 4096, 4096 + 512);
    const size_t consumed2 =
        sink.WriteProducerFrames(flood.data(), flood.size());
    check(consumed2 == 0, "flood write fully rejected (ring full)");

    const auto st = sink.Stats();
    check(st.overrun_events == 1, "flood counted as overrun");
    check(st.occupancy_frames == 4096, "occupancy pinned at capacity");

    // Drain one callback, then the next flood partially commits
    // (block-aligned: 512 free frames accept exactly one 512-frame write).
    std::vector<float> dst(512 * 2);
    sink.RunCallback(dst.data(), 512);
    fill_ramp(flood, 0, 2048);
    const size_t consumed3 =
        sink.WriteProducerFrames(flood.data(), flood.size());
    check(consumed3 == 512 * 2, "partial commit after draining");

    const auto st2 = sink.Stats();
    check(st2.overrun_events == 2, "second short write counted");
    // Conservation: written == pulled + occupancy (nothing dropped).
    check(st2.frames_written ==
              st2.frames_pulled + st2.occupancy_frames + st2.frames_flushed,
          "conservation holds under overrun");
}

// ---------------------------------------------------------------------------
// F. Rate reconfiguration
// ---------------------------------------------------------------------------
void test_rate_reconfiguration() {
    std::printf("  [F] mid-stream rate reconfiguration\n");
    VirtualHalSink::Config cfg;
    cfg.callback_frames = 512;
    VirtualHalSink sink(cfg);

    std::vector<float> feed;
    fill_ramp(feed, 0, 2048);
    sink.WriteProducerFrames(feed.data(), feed.size());
    std::vector<float> dst(512 * 2);
    sink.RunCallback(dst.data(), 512);
    const uint32_t epoch_before = sink.Stats().epoch;
    const uint64_t time_before = sink.SimTimeNs();

    check(sink.ReconfigureRate(44100), "reconfig accepted");
    const auto st = sink.Stats();
    check(st.epoch == epoch_before + 1, "epoch advanced");
    check(sink.sample_rate() == 44100, "rate moved");
    check(sink.SimTimeNs() == time_before, "timeline continues monotonically");
    check(st.occupancy_frames == 0, "ring flushed by teardown/rebuild");
    check(st.frames_flushed == 2048 - 512, "flushed accounting exact");

    // The next callback reports the new-rate epoch from frame 0.
    fill_ramp(feed, 0, 512);
    sink.WriteProducerFrames(feed.data(), feed.size());
    const VirtualHalCallback rep = sink.RunCallback(dst.data(), 512);
    check(rep.epoch == epoch_before + 1, "callback carries new epoch");
    check(rep.clock.dac_frame_position == 0, "frame counter restarted");
    check(rep.clock.sample_rate == 44100, "clock sample carries new rate");
    check(rep.clock.monotonic_ns >= time_before,
          "presentation ts continues monotonically");
    // 512 frames @44.1k = 11.61 ms quantum: the cursor must have moved by
    // more than a 48k quantum would (10.67 ms).
    check(sink.SimTimeNs() - time_before > 11000000ull,
          "cursor moves at the new rate");

    check(!sink.ReconfigureRate(500), "garbage rate rejected");
    check(sink.ReconfigureRate(96000), "back to a sane rate works");

    // Conservation across the whole multi-epoch run.
    const auto st2 = sink.Stats();
    check(st2.frames_written ==
              st2.frames_pulled + st2.occupancy_frames + st2.frames_flushed,
          "conservation across epochs");
}

// ---------------------------------------------------------------------------
// G. Interpolation acceptance (<5 ms) + fire-time negative control
// ---------------------------------------------------------------------------
void drive_and_measure(VirtualHalSink& sink, size_t callbacks,
                       DacClockInterpolator* interp,
                       DacClockInterpolator* fire_interp, double* max_err_ms,
                       double* max_fire_err_ms) {
    const size_t chunk = sink.callback_frames();
    std::vector<float> feed;
    std::vector<float> dst(chunk * 2);
    uint64_t next_feed_frame = 0;
    for (size_t k = 0; k < callbacks; ++k) {
        // Keep the ring topped up (deterministic ramp).
        fill_ramp(feed, next_feed_frame, chunk * 2);
        next_feed_frame +=
            sink.WriteProducerFrames(feed.data(), feed.size()) / 2;
        const VirtualHalCallback rep = sink.RunCallback(dst.data(), chunk);
        interp->Update(rep.clock);
        fire_interp->Update(VirtualDacClock{rep.clock.dac_frame_position,
                                            rep.clock.sample_rate,
                                            rep.fire_time_ns});
        // Deterministic query instants across the quantum + staleness tail.
        const uint64_t quantum_ns =
            1000000000ull * chunk / rep.clock.sample_rate;
        for (uint64_t q = 1; q <= 4; ++q) {
            const uint64_t now = rep.clock.monotonic_ns + quantum_ns * q / 4;
            uint64_t est = 0;
            if (interp->PositionFramesAt(now, &est)) {
                const double truth = sink.TruePositionFramesAt(now);
                const double err = std::fabs(static_cast<double>(est) - truth) *
                                   1000.0 / rep.clock.sample_rate;
                if (err > *max_err_ms) *max_err_ms = err;
            }
            uint64_t fest = 0;
            if (fire_interp->PositionFramesAt(now, &fest)) {
                const double truth = sink.TruePositionFramesAt(now);
                const double err =
                    std::fabs(static_cast<double>(fest) - truth) * 1000.0 /
                    rep.clock.sample_rate;
                if (err > *max_fire_err_ms) *max_fire_err_ms = err;
            }
        }
    }
}

void test_interpolation_acceptance() {
    std::printf("  [G] DacPresentationClock interpolation <5 ms\n");

    // G1: exact timestamps, jitter ON — error must be ~0 (sub-frame).
    {
        VirtualHalSink::Config cfg;
        cfg.callback_frames = 512;
        cfg.modes.bits = AdversarialModes::kTimingJitter;
        VirtualHalSink sink(cfg);
        DacClockInterpolator interp, fire_interp;
        double max_err = 0.0, max_fire_err = 0.0;
        drive_and_measure(sink, 400, &interp, &fire_interp, &max_err,
                          &max_fire_err);
        check_label(max_err < 1.0,
                    "jitter alone: consistent-pair error %.4f ms < 1 ms",
                    max_err);
        check_label(max_fire_err > 5.0,
                    "negative control: fire-time error %.4f ms > 5 ms",
                    max_fire_err);
    }

    // G2: 1 ms timestamp grid + 200 ppm drift + jitter + all chunk sizes.
    for (size_t chunk : {size_t{256}, size_t{512}, size_t{1024}}) {
        VirtualHalSink::Config cfg;
        cfg.callback_frames = chunk;
        cfg.modes.bits = AdversarialModes::kTimingJitter;
        cfg.timestamp_quantum_ns = 1000000ull;  // 1 ms HAL timestamp grid
        cfg.drift_ppm = 200;                    // DAC clock drift
        VirtualHalSink sink(cfg);
        DacClockInterpolator interp, fire_interp;
        double max_err = 0.0, max_fire_err = 0.0;
        drive_and_measure(sink, 400, &interp, &fire_interp, &max_err,
                          &max_fire_err);
        char label[160];
        std::snprintf(label, sizeof(label),
                      "chunk %zu: quantized+drift+jitter error %.4f ms < 5 ms",
                      chunk, max_err);
        check(max_err < 5.0, label);
        std::snprintf(label, sizeof(label),
                      "chunk %zu: fire-time control %.4f ms > 5 ms", chunk,
                      max_fire_err);
        check(max_fire_err > 5.0, label);
    }

    // G3: interpolator epoch-reset semantics.
    {
        DacClockInterpolator interp;
        uint64_t out = 0;
        check(!interp.PositionFramesAt(1000, &out),
              "no sample => no position");
        interp.Update(VirtualDacClock{10000, 48000, 2000000000ull});
        check(interp.PositionFramesAt(2000000000ull + 1000000ull, &out),
              "position available after update");
        check(out == 10048, "1 ms extrapolation at 48 kHz = 48 frames");
        interp.Update(
            VirtualDacClock{10048, 48000, 2000000000ull + 1000000ull});
        check(interp.PositionFramesAt(2000000000ull + 1000000ull, &out) &&
                  out == 10048,
              "re-baselined on advancing sample");
        // Epoch reset: rate change + frame-counter regression (teardown).
        interp.Update(VirtualDacClock{0, 44100, 2000000000ull + 2000000ull});
        check(interp.rate() == 44100, "epoch reset re-latches rate");
        check(interp.PositionFramesAt(2000000000ull + 3000000ull, &out) &&
                  out == 44,
              "extrapolates in the NEW epoch after reset (1 ms @44.1k = 44)");
    }
}

// ---------------------------------------------------------------------------
// H. Full adversarial scenario
// ---------------------------------------------------------------------------
void test_full_adversarial_scenario() {
    std::printf("  [H] full adversarial scenario (all modes)\n");
    size_t chunk_label = 0;
    for (size_t chunk : {size_t{256}, size_t{512}, size_t{1024}}) {
        chunk_label = chunk;
        VirtualHalSink::Config cfg;
        cfg.callback_frames = chunk;
        cfg.capacity_frames = 8192;
        cfg.modes.bits = AdversarialModes::kTimingJitter |
                         AdversarialModes::kInjectUnderrun |
                         AdversarialModes::kInjectOverrun |
                         AdversarialModes::kRateChange;
        cfg.jitter_fraction = 0.10;            // ±10 % interval variance
        cfg.timestamp_quantum_ns = 1000000ull;  // 1 ms HAL ts grid
        cfg.drift_ppm = 200;
        VirtualHalSink sink(cfg);
        check(sink.valid(), "scenario config valid");

        VirtualHalSink::AdversarialProfile profile;
        profile.callbacks = 600;
        profile.starve_every = 50;  // starvation window every 50 callbacks
        profile.starve_burst = 5;   // > feed depth: guarantees real starvation
        profile.flood_every = 97;   // flood attempt every 97 callbacks
        profile.rate_changes = 3;   // 48k -> 44.1k -> 96k -> 48k
        profile.queries_per_callback = 4;
        profile.feed_ahead_quanta = 2;  // ring depth: 2 quanta

        const VirtualHalSink::ScenarioReport rep =
            sink.RunAdversarialScenario(profile);

        char label[160];
        std::snprintf(label, sizeof(label),
                      "chunk %zu: interpolation error %.4f ms < 5 ms",
                      chunk_label, rep.max_interpolation_error_ms);
        check(rep.max_interpolation_error_ms < 5.0, label);
        std::snprintf(label, sizeof(label),
                      "chunk %zu: fire-time control %.4f ms > 5 ms",
                      chunk_label, rep.max_firetime_error_ms);
        check(rep.max_firetime_error_ms > 5.0, label);
        check(rep.conservation_ok, "scenario frame conservation");
        check(rep.starved_callbacks > 0, "underruns actually injected");
        check(rep.overrun_events > 0, "overruns actually injected");
        check(rep.rate_changes == 3, "rate changes executed");
        check(rep.callbacks_run == 600, "all callbacks ran");

        const auto st = sink.Stats();
        check(st.epoch == 3, "epoch advanced per reconfig");
        (void)chunk_label;
    }
}

// ---------------------------------------------------------------------------
// I. Threaded SPSC + VirtualHAL clock
// ---------------------------------------------------------------------------
void test_threaded_spsc() {
    std::printf("  [I] threaded SPSC with clock tracking\n");
    VirtualHalSink::Config cfg;
    cfg.callback_frames = 512;
    cfg.capacity_frames = 8192;
    VirtualHalSink sink(cfg);

    const uint64_t total_frames = 100000;
    std::atomic<bool> stop{false};

    // Producer thread: ramps into the ring until the quota is met (the
    // ring backpressures via short writes — retried here). The producer
    // races the callback driver on the REAL SPSC ring (the two atomic
    // producer-side counters are the only shared wrapper state).
    std::thread producer([&] {
        std::vector<float> feed(1024 * 2);
        uint64_t next = 0;
        while (!stop.load(std::memory_order_relaxed) && next < total_frames) {
            const size_t n = static_cast<size_t>(
                std::min<uint64_t>(1024, total_frames - next));
            fill_ramp(feed, next, n);
            const size_t consumed =
                sink.WriteProducerFrames(feed.data(), n * 2);
            if (consumed > 0) {
                next += consumed / 2;
            } else {
                std::this_thread::yield();
            }
        }
    });

    // Consumer: drive callbacks until everything is presented.
    std::vector<float> dst(512 * 2);
    uint64_t verified = 0;
    bool intact = true;
    while (verified < total_frames) {
        const VirtualHalCallback rep = sink.RunCallback(dst.data(), 512);
        for (size_t f = 0; f < rep.supplied_frames; ++f) {
            if (dst[f * 2] != ramp_left(verified + f) ||
                dst[f * 2 + 1] != ramp_right(verified + f)) {
                intact = false;
            }
        }
        if (rep.supplied_frames == 0) {
            std::this_thread::yield();  // spinning ahead of the producer
        }
        verified += rep.supplied_frames;
    }
    stop.store(true, std::memory_order_relaxed);
    producer.join();

    check(intact, "100k-frame ramp intact under real thread contention");
    check(verified == total_frames, "all frames presented");
    const auto st = sink.Stats();
    check(st.frames_written == total_frames, "threaded write accounting");
    check(st.frames_pulled == total_frames, "threaded pull accounting");
}

}  // namespace

int main() {
    std::printf("==================================================\n");
    std::printf("  Streamify VirtualHAL Suite (Phase 4a MP-HAL01/02)\n");
    std::printf("==================================================\n");
    test_config_validation();
    test_chunk_sweep();
    test_steady_stream();
    test_underrun_injection();
    test_overrun_injection();
    test_rate_reconfiguration();
    test_interpolation_acceptance();
    test_full_adversarial_scenario();
    test_threaded_spsc();
    std::printf("--------------------------------------------------\n");
    std::printf("  virtual_hal_test_suite: %d passed, %d failed\n", g_passed,
                g_failed);
    std::printf("==================================================\n");
    return g_failed == 0 ? 0 : 1;
}
