#ifndef STREAMIFY_VIRTUAL_HAL_H
#define STREAMIFY_VIRTUAL_HAL_H
// ============================================================================
//  virtual_hal.h — Phase 4a VirtualHAL: deterministic adversarial audio
//  test double with a simulated hardware DAC presentation clock
//  (native/include/virtual_hal.h — MP-HAL01 / MP-HAL02)
// ============================================================================
//
//  PURPOSE
//  -------
//  An in-memory, deterministic, platform-agnostic audio sink that emulates
//  hardware PULL callbacks against the hardened SPSC `AudioSink` ring and
//  validates the `DacPresentationClock` contract WITHOUT physical audio
//  hardware. It runs headless on Linux, macOS and Windows CI runners and
//  is the reference oracle for the Phase 4a acceptance target:
//
//      DacPresentationClock monotonic timestamp interpolation must stay
//      within < 5 ms of the simulated presentation time under adversarial
//      callback conditions (master plan invariant 3 / MP-HAL06 rehearsal).
//
//  SIMULATED DAC MODEL (per epoch — an epoch ends at a rate reconfiguration)
//  -----------------------------------------------------------------------
//  * The device presents exactly `rate` frames per second of monotonic time
//    from the epoch origin: frame F presents at
//        t(F) = epoch_origin_ns + F * 1e9 / rate_true
//    where rate_true = rate * (1 + drift_ppm * 1e-6). The mapping is
//    computed in IEEE-754 f64 (mul/div only — bit-deterministic on every
//    target; no transcendentals in the clock path).
//  * Callback k renders quantum [S, S + Q): it pulls up to Q frames from
//    the ring (silence-filling any starved tail — a starving consumer
//    never stalls the hardware clock; AudioTrack semantics) and reports
//    the CONSISTENT pair
//        dac_frame_position = S            (CoreAudio mSampleTime
//        monotonic_ns       = t(S)           semantics: the first frame
//                                           of the quantum being rendered)
//    with `monotonic_ns` quantized to `timestamp_quantum_ns` (the grid
//    real HALs report timestamps on; 0 = exact).
//  * The callback FIRE time (when the host invoked the callback relative
//    to its deadline) carries the ±jitterFraction variance. A pull HAL
//    fires ~one full period EARLY (the callback fills the buffer the
//    hardware will present next). Fire times are reported for diagnostics
//    ONLY: they are the classic broken-estimator input (position inferred
//    from "when I learned about it" instead of "when it presents") and the
//    test suite uses them as the negative control proving the 5 ms bound
//    is actually measurable.
//
//  ADVERSARIAL MODES (MP-HAL02)
//  ----------------------------
//  * Timing jitter          — ±jitterFraction fire-time variance per
//                             callback interval (default ±10 %).
//  * Injected underruns     — scripted producer starvation windows; the
//                             consumer pulls starved quanta (silence).
//  * Injected overruns      — scripted producer floods (writes beyond ring
//                             capacity: short writes, overrun telemetry).
//  * Rate reconfiguration   — mid-stream device rate changes; per master
//                             plan invariant 6 the stream is torn down
//                             and rebuilt: the ring is flushed, the frame
//                             epoch restarts and the clock domain moves.
//
//  All scheduling is driven by an internal SplitMix64 PRNG seeded from the
//  config: identical seed => identical callback-for-callback run on every
//  platform and compiler (integer-only state, no <random> dependence).
//
//  DETERMINISM & THREADING CONTRACT
//  ----------------------------------
//  The double is cooperatively scheduled (no internal threads): the test
//  drives producer writes and `RunCallback` pulls in explicit order, so a
//  failing scenario replays byte-for-byte. The two PRODUCER-side counters
//  (frames_written / overrun_events) are atomics so a producer thread may
//  legally race the callback thread (the threaded SPSC lane) — every
//  other member is single-owner: callback state (cursor / clock / rng) on
//  the callback driver, epoch rebuilds on the control thread with the
//  stream stopped (the same teardown ordering the real adapters follow).
//  Real SPSC ring contention is already covered by the 10M-frame stress in
//  test_audio_sink_ringbuffer; this double layers the ADVERSARIAL CLOCK
//  semantics on top of the same hardened ring the production HAL adapters
//  pull from.
// ============================================================================

#include <atomic>
#include <cstddef>
#include <cstdint>
#include <memory>
#include <vector>

#include "audio_sink_ringbuffer.h"

namespace streamify {
namespace hal {

// ---- the wire mirror of the Rust `DacPresentationClock` (MP-C01) -----------
// Field-for-field the FFI contract struct; kept as a plain C++ aggregate so
// the native adapters and the Rust core agree on one clock-sample image.
struct VirtualDacClock {
    uint64_t dac_frame_position = 0;  // hardware-consumed frame counter
    uint32_t sample_rate = 48000;     // device DAC nominal rate (Hz)
    uint64_t monotonic_ns = 0;        // OS monotonic presentation ts (ns)
};

// ---- DacPresentationClock monotonic-timestamp interpolation -----------------
//
// The canonical consumer of HAL clock samples (the same estimator every
// platform shell uses): given a stream of CONSISTENT (frames, rate, time)
// samples, estimate the presentation position at an arbitrary monotonic
// instant by linear extrapolation from the newest sample:
//
//     est(t) = F_last + (t - T_last) * rate_last / 1e9
//
// Epoch-reset aware: a device rate change or a frame-counter regression
// (stream teardown/rebuild — master plan invariant 6) drops the stale
// baseline instead of extrapolating across the discontinuity.
class DacClockInterpolator {
public:
    // Ingest one consistent clock sample (any thread; not RT-bound).
    void Update(const VirtualDacClock& clock);

    // True once at least one sample has been ingested.
    bool HasSample() const { return has_sample_; }

    // Interpolated presentation position, in device FRAMES, at monotonic
    // instant `now_ns`. Returns false before the first sample. Uses only
    // integer arithmetic on the ingest path (exact within the sample's own
    // reported precision).
    bool PositionFramesAt(uint64_t now_ns, uint64_t* out_frames) const;

    // Same estimate expressed in milliseconds of presented audio.
    bool PositionMsAt(uint64_t now_ns, double* out_ms) const;

    uint32_t rate() const { return rate_; }
    uint64_t baseline_frames() const { return baseline_frames_; }
    uint64_t baseline_monotonic_ns() const { return baseline_ns_; }

private:
    bool has_sample_ = false;
    uint64_t baseline_frames_ = 0;
    uint64_t baseline_ns_ = 0;
    uint32_t rate_ = 48000;
};

// ---- adversarial mode flags (MP-HAL02) --------------------------------------
struct AdversarialModes {
    // Bit set; combine with |.
    static constexpr uint32_t kNone = 0u;
    static constexpr uint32_t kTimingJitter = 1u << 0;  // ±fire-time variance
    static constexpr uint32_t kInjectUnderrun = 1u << 1;  // starve consumer
    static constexpr uint32_t kInjectOverrun = 1u << 2;  // flood the ring
    static constexpr uint32_t kRateChange = 1u << 3;     // mid-stream reconfig

    uint32_t bits = kNone;

    bool has(uint32_t m) const { return (bits & m) == m; }
};

// One simulated hardware pull callback result.
struct VirtualHalCallback {
    size_t requested_frames = 0;   // quantum the HAL asked for
    size_t supplied_frames = 0;    // real audio pulled from the ring
    bool starved = false;          // supplied < requested (silence-filled)
    VirtualDacClock clock;         // the CONSISTENT reported pair
    uint64_t fire_time_ns = 0;     // jittered fire instant (diagnostics)
    uint32_t epoch = 0;            // clock epoch of this callback
};

// Live simulator telemetry (cumulative across epochs unless noted).
struct VirtualHalStats {
    uint64_t frames_written = 0;     // producer frames accepted by the ring
    uint64_t frames_pulled = 0;      // real frames consumed by callbacks
    uint64_t frames_silenced = 0;    // starved frames the DAC zero-filled
    uint64_t starved_callbacks = 0;  // callbacks with supplied < requested
    uint64_t overrun_events = 0;     // producer writes that returned short
    uint64_t frames_flushed = 0;     // frames dropped by rate reconfigs
    uint64_t callbacks_run = 0;      // total simulated callbacks
    uint32_t epoch = 0;              // current clock epoch
    size_t occupancy_frames = 0;     // live ring occupancy
    size_t capacity_frames = 0;      // current ring capacity
};

// ---- the VirtualHAL sink -----------------------------------------------------
//
// Wraps a REAL `streamify::sink::AudioSink` (the hardened SPSC f32 ring the
// production HAL adapters pull from): producer writes route through
// `AudioSink::WriteFloat`, callback pulls through `AudioSink::ReadFramesF32`
// — the double adds the simulated DAC clock domain and adversarial
// scheduling on top of the production data plane.
class VirtualHalSink {
public:
    struct Config {
        uint32_t sample_rate = 48000;   // device/epoch rate (Hz)
        uint32_t channels = 2;          // canonical stereo (1|2)
        size_t capacity_frames = 8192;  // ring capacity (pow2-rounded)
        size_t callback_frames = 512;   // HAL quantum: 256 | 512 | 1024
        AdversarialModes modes;         // scripted by RunAdversarialScenario
        double jitter_fraction = 0.10;  // ±fire-time variance (0.10 = ±10 %)
        uint64_t timestamp_quantum_ns = 0;  // reported-ts grid (0 = exact)
        int32_t drift_ppm = 0;          // DAC-vs-monotonic clock drift
        uint64_t rng_seed = 0x53545245414D4946ull;  // "STREAMIFY"
    };

    // Validation bounds (mirror the AudioSink ring contract).
    static constexpr uint32_t kMinRate = 8000;
    static constexpr uint32_t kMaxRate = 192000;
    static constexpr size_t kMinCallbackFrames = 256;
    static constexpr size_t kMaxCallbackFrames = 1024;

    explicit VirtualHalSink(const Config& config);

    // ---- producer side (the "app" under test) -----------------------------
    // Interleaved f32 samples; returns the SAMPLE count consumed (whole
    // frames only; a short return means the ring is full — overrun
    // telemetry counts one event per short write).
    size_t WriteProducerFrames(const float* samples, size_t sample_count);

    // ---- simulated hardware pull callback ----------------------------------
    // Renders ONE quantum of `dst_frames` frames into the caller-provided
    // interleaved buffer (the HAL supplies the destination, like an
    // AURenderCallback ioData). Starved tails are silence-filled. Advances
    // the simulated DAC clock by the full quantum (position advances
    // through silence) and returns the callback report.
    VirtualHalCallback RunCallback(float* dst, size_t dst_frames);

    // Convenience overload rendering into the internal (init-allocated)
    // scratch: pure clock/stress driving where the audio is discarded.
    VirtualHalCallback RunCallback();

    // ---- mid-stream rate reconfiguration (master plan invariant 6) ---------
    // Teardown-and-rebuild: flushes the ring, restarts the frame epoch and
    // moves the clock domain to the new rate. `new_rate` must be a sane
    // hardware rate; returns false (no-op) otherwise.
    bool ReconfigureRate(uint32_t new_rate);

    // ---- introspection ------------------------------------------------------
    VirtualHalStats Stats() const;
    const VirtualDacClock& last_clock() const { return last_clock_; }
    uint32_t sample_rate() const { return rate_; }
    uint32_t channels() const { return channels_; }
    size_t callback_frames() const { return callback_frames_; }
    bool valid() const { return sink_ != nullptr; }

    // Simulated monotonic instant of the CURRENT presentation cursor (the
    // ground-truth "now" of the DAC timeline; deterministic f64 mapping).
    uint64_t SimTimeNs() const;
    // Ground-truth presented frame count at an arbitrary monotonic instant
    // (clamped to the epoch: negative pre-origin queries return 0).
    double TruePositionFramesAt(uint64_t now_ns) const;

    // ---- self-driving adversarial scenario (MP-HAL02) -----------------------
    // Runs a deterministic producer/consumer schedule exercising every
    // enabled adversarial mode, feeding a deterministic ramp signal, and
    // validating the DacClockInterpolator against the ground-truth cursor
    // at `profile.queries_per_callback` deterministic instants per quantum.
    // The returned report carries the worst observed errors (the suite's
    // acceptance gate: max_interpolation_error_ms < 5.0).
    struct AdversarialProfile {
        size_t callbacks = 600;            // total simulated callbacks
        size_t starve_every = 0;           // 0 = off; starve burst every N callbacks
        size_t starve_burst = 3;           // consecutive unfed callbacks
        size_t flood_every = 0;            // 0 = off; >capacity write every N callbacks
        size_t rate_changes = 0;           // reconfig count, evenly spaced
        size_t queries_per_callback = 4;   // interpolation checks per quantum
        size_t feed_ahead_quanta = 4;      // nominal ring target (quanta)
    };

    struct ScenarioReport {
        size_t callbacks_run = 0;
        size_t starved_callbacks = 0;
        size_t overrun_events = 0;
        size_t rate_changes = 0;
        size_t frames_written = 0;
        size_t frames_pulled = 0;
        double max_interpolation_error_ms = 0.0;  // consistent-pair estimator
        double max_firetime_error_ms = 0.0;       // naive fire-time control
        bool conservation_ok = false;             // written == pulled + live + flushed + silenced-audit
    };

    ScenarioReport RunAdversarialScenario(const AdversarialProfile& profile);

private:
    // Deterministic SplitMix64 — callback-identical on every platform.
    struct Rng {
        uint64_t s;
        explicit Rng(uint64_t seed) : s(seed) {}
        uint64_t Next() {
            s += 0x9E3779B97F4A7C15ull;
            uint64_t z = s;
            z = (z ^ (z >> 30)) * 0xBF58476D1CE4E5B9ull;
            z = (z ^ (z >> 27)) * 0x94D049BB133111EBull;
            return z ^ (z >> 31);
        }
        // Symmetric float in [-1.0, 1.0) — used only for fire-time jitter
        // (never for reported clock data: the clock path stays exact).
        double NextSymmetric() {
            return static_cast<double>(Next() >> 11) * (1.0 / 9007199254740992.0) * 2.0 - 1.0;
        }
    };

    // Rebuild the ring for a (possibly new) rate, preserving accumulators.
    void RebuildRing_(uint32_t rate);
    // Epoch presentation instant of `frame` (f64: mul/div only —
    // bit-deterministic; no transcendentals on the clock path).
    double TimeAtFrameNs(uint64_t frame) const {
        return static_cast<double>(epoch_origin_ns_) +
               static_cast<double>(frame) * 1e9 / rate_true_;
    }
    // Exact-monotonic fire time for the quantum starting at `first_frame`.
    uint64_t FireTimeFor_(uint64_t first_frame, size_t quantum);
    // Floor `t` onto the reported-timestamp grid.
    uint64_t QuantizeTimestamp_(uint64_t t) const;

    // ---- configuration (immutable after construction / reconfig) ----------
    uint32_t channels_ = 2;
    size_t callback_frames_ = 512;
    size_t capacity_frames_ = 8192;
    AdversarialModes modes_;
    double jitter_fraction_ = 0.10;
    uint64_t timestamp_quantum_ns_ = 0;
    int32_t drift_ppm_ = 0;

    // ---- simulated DAC clock state (per epoch) ------------------------------
    uint32_t epoch_ = 0;
    uint32_t rate_ = 48000;
    double rate_true_ = 48000.0;      // rate * (1 + drift)
    uint64_t epoch_origin_ns_ = 0;    // monotonic ns at epoch frame 0
    uint64_t cursor_frames_ = 0;      // frames presented so far this epoch
    VirtualDacClock last_clock_{};    // newest reported sample
    Rng rng_{0x53545245414D4946ull};

    // ---- the production data plane under test --------------------------------
    std::unique_ptr<sink::AudioSink> sink_;  // null => invalid config
    std::vector<float> scratch_;             // callback_frames * channels

    // ---- cumulative cross-epoch telemetry ------------------------------------
    // Producer-side counters are atomic (a producer thread may race the
    // callback driver — the threaded SPSC lane); everything else is
    // single-owner per the threading contract in the file header.
    std::atomic<uint64_t> frames_written_{0};
    std::atomic<uint64_t> overrun_events_{0};
    uint64_t frames_pulled_ = 0;
    uint64_t frames_silenced_ = 0;
    uint64_t starved_callbacks_ = 0;
    uint64_t frames_flushed_ = 0;
    uint64_t callbacks_run_ = 0;
};

}  // namespace hal
}  // namespace streamify

#endif  // STREAMIFY_VIRTUAL_HAL_H
