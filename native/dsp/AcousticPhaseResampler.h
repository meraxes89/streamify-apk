#ifndef STREAMIFY_ACOUSTIC_PHASE_RESAMPLER_H
#define STREAMIFY_ACOUSTIC_PHASE_RESAMPLER_H

#include <cstdint>
#include <cmath>
#include <atomic>
#include <memory>

// Shared alignment shims: the polyphase table and the ring are heap blocks
// with 32-byte alignment, and the deleter below MUST stay paired with the
// allocator (posix_memalign/free on POSIX, _aligned_malloc/_aligned_free
// on MSVC — the C4 rot guard compiles this unit's header on Windows).
#include "../util/NeonCompat.h"

namespace streamify::dsp {

// ============================================================================
//  AcousticPhaseResampler — real-time bandlimited fractional resampler
// ============================================================================
//
//  PURPOSE
//  -------
//  Continuous, inaudible micro-tuning of the playback rate so that N devices
//  in one room converge to sample-accurate acoustic phase lockstep. Coarse
//  corrections (seeks / speed changes in ExoPlayer) audibly pop and
//  pitch-wobble; this engine instead moves the read position through the PCM
//  float stream by a *fractional, continuously slewing* amount:
//
//      output frame j is evaluated at input position p_j = sum(ratio_i, i<j)
//
//  with ratio = 1 + drift, |drift| <= 500 PPM (0.05%).
//
//  PERCEPTUAL BUDGET (why 500 PPM is safe)
//  ---------------------------------------
//      pitch shift [cents] = 1200 * log2(ratio)
//                          ~= 1731 * drift[PPM] / 1e6
//
//      full-scale  500 PPM  ->  0.87 cents  (below the ~3-cent JND of even
//                                           trained listeners in ideal A/B)
//      typical lock-loop drive (<60 PPM) -> < 0.11 cents
//      per-frame slew step (tau = 250 ms) -> < 0.0002 cents, i.e. the ratio
//      trajectory itself is inaudible; there is never a discrete "seek".
//
//  INTERPOLATION KERNEL
//  --------------------
//  Windowed-sinc (Kaiser, beta ~= 9.0, 32 taps, ~-85 dB stopband) evaluated
//  on a 512-phase polyphase grid with LINEAR INTERPOLATION between adjacent
//  phase banks. Interpolating the taps (rather than quantizing the phase)
//  pushes the phase-quantization noise floor from ~-66 dB down below
//  -140 dB — effectively continuous fractional delay at 2x the MAC cost of
//  a plain polyphase table, still far inside the CPU budget.
//
//  PHASE BOOKKEEPING
//  -----------------
//  The fractional read position is tracked in 32.32 fixed point (uint64):
//  zero drift over hour-long sessions (a float accumulator would wander by
//  thousands of samples), exact frame accounting, O(1) per output frame,
//  and deterministic across block boundaries (identical output whether the
//  stream arrives in 64-frame or 65536-frame blocks — asserted in tests).
//
//  STREAMING CONTRACT
//  ------------------
//  process() APPENDS input to an internal pre-allocated ring and emits as
//  many output frames as fit in the caller's buffer. Callers simply pipe
//  consecutive input blocks; the engine remembers unconsumed history.
//  History before stream start is zero-padded so output frame 0 aligns
//  with input frame 0 (unity group delay at drift == 0).
//
//  PERFORMANCE CONTRACT
//  --------------------
//  ZERO heap allocation in process() after configure(). All tables and the
//  ring are pre-allocated. The inner kernel is NEON (vfmaq_f32, vld1q_f32)
//  on arm64-v8a and armeabi-v7a, with a portable scalar fallback for host
//  builds. Stereo 48 kHz costs ~2.3M MAC/s; even the scalar path fits the
//  <1.5%-of-a-Cortex-A55 budget, NEON leaves ~4x headroom.
// ============================================================================

class AcousticPhaseResampler {
public:
    struct Config {
        int    channels        = 2;       // interleaved PCM channel count
        double sampleRateHz    = 48'000.0;
        int    tapsPerPhase    = 32;      // filter length (even)
        int    phaseCount      = 512;     // polyphase sub-phase grid
        double kaiserBeta      = 9.0;     // ~-85 dB stopband
        double slewTauSeconds  = 0.25;    // drift-rate smoothing constant
        int    maxInputFrames  = 16'384;  // per process() call
    };

    // Drift limit: +-0.05% == +-500 PPM == +-500_000 ns/s (mission brief 3B).
    static constexpr double kMaxDriftPpm = 500.0;
    static constexpr int64_t kMaxDriftNanosPerSec =
        static_cast<int64_t>(kMaxDriftPpm) * 1'000;

    // Two-ctor split instead of `= Config()` default argument: an in-class
    // default argument would need the nested NSDMIs before the end of the
    // enclosing class (complete-class context) — rejected by GCC 9+/Clang.
    AcousticPhaseResampler();             // AcousticPhaseResampler(Config())
    explicit AcousticPhaseResampler(const Config& cfg);
    ~AcousticPhaseResampler();

    AcousticPhaseResampler(const AcousticPhaseResampler&) = delete;
    AcousticPhaseResampler& operator=(const AcousticPhaseResampler&) = delete;

    // Reconfigure for a new stream (channels / sample rate). Rebuilds the
    // kernel tables and clears stream state. Returns true if a rebuild
    // happened, false if the configuration was already active. NOT for the
    // audio callback — call on track transitions.
    bool configure(int channels, double sampleRateHz);

    // ---- Drift control -----------------------------------------------------
    // Target rate adjustment, NANoseconds of media-time advance per SECOND
    // of playback (ns/s; +500_000 == +500 PPM == speed up by 0.05%).
    // Clamped to +-kMaxDriftNanosPerSec. The actual applied drift slews
    // toward the target with tau = slewTauSeconds, so a full +500->-500 PPM
    // command produces per-frame pitch steps below 0.0002 cents.
    void setTargetDriftNanosPerSecond(int64_t driftNanosPerSec);
    void setTargetDriftPpm(double ppm);   // convenience: 1 PPM == 1000 ns/s
    double targetDriftPpm() const {
        return static_cast<double>(
            targetDriftNanoPerSec_.load(std::memory_order_relaxed)) / 1000.0;
    }
    double currentDriftPpm() const { return driftPpm_; }   // post-slew value

    // ---- Streaming ---------------------------------------------------------
    // Append `inFrames` interleaved float frames; write up to
    // `outFrameCapacity` frames to `out`. Returns frames produced
    // (>= 0), or a negative error code (see jni_bridge_dsp.cc docs).
    int process(const float* in, int inFrames, float* out, int outFrameCapacity);

    // Exact long-term accounting (fixed-point truth, not an estimate).
    int64_t framesConsumed() const { return totalInFrames_; }
    int64_t framesProduced() const { return totalOutFrames_; }

    // Suggested drift for a known phase error (PI-free, bounded):
    // a device `errNanos` behind should speed up proportionally, with a
    // 2-second time constant and the hard +-500 PPM clamp. Pure function —
    // the Kotlin controller may use it or roll its own loop.
    static int64_t suggestDriftNanosPerSecond(int64_t phaseErrorNanos);

    void reset();   // clear stream position/history (keeps tables & config)

    // ---- Silent PLL (Gap #13, SINGLE_RENDER party mode) -------------------
    // While bypass is active, process() is a bit-exact memcpy passthrough:
    // zero synthesis kernel work, zero allocations, zero AudioTrack buffer
    // write amplification. The Kalman/PTP clock filter itself is driven by
    // the network timestamp path (nativePtpProcessTimestamps) and therefore
    // keeps converging regardless — this is what lets any guest take over
    // rendering with no resync delay.
    //
    // The drift state machine ALSO keeps tracking: per bypass block the
    // per-frame slew recurrence is advanced in closed form, so the applied
    // rate is already at the commanded value when bypass lifts.
    //
    // Any bypass TRANSITION restarts the stream at the current live position
    // (the bypass-era audio was already rendered passthrough); the rate
    // estimate and the controller command survive the transition.
    void setSilentBypass(bool silentBypass);
    void SetSilentBypass(bool silentBypass) {   // directive-named alias
        setSilentBypass(silentBypass);
    }
    bool silentBypass() const {
        return silentBypass_.load(std::memory_order_relaxed);
    }

    // Introspection for tests / telemetry.
    int channels() const { return cfg_.channels; }
    double sampleRateHz() const { return cfg_.sampleRateHz; }
    int tapsPerPhase() const { return cfg_.tapsPerPhase; }
    int phaseCount() const { return cfg_.phaseCount; }

private:
    void buildFilterTable();   // Kaiser-windowed sinc polyphase bank
    void resetStreamState();

    Config cfg_;

    // Polyphase table: (phaseCount + 1) rows x tapsPerPhase floats.
    // Row P duplicates the phase-0 shift so linear interpolation between
    // rows P-1 and P never reads out of bounds.
    std::unique_ptr<float[], void (*)(void*)> table_{nullptr, &streamify::alignedFree};

    // Ring buffer holding unconsumed input (interleaved), zero-padded
    // history conceptually extending to negative stream indices.
    std::unique_ptr<float[], void (*)(void*)> ring_{nullptr, &streamify::alignedFree};
    int ringCapacity_ = 0;    // frames
    int ringFrames_ = 0;      // frames currently buffered
    int64_t ringStart_ = 0;   // absolute stream index of ring_[0]

    // Fixed-point read position: 32.32 (whole frames . sub-frame units),
    // unsigned sub-sample accumulator carried across frames.
    uint64_t phaseAcc_ = 0;      // fractional part, [0, 2^32)
    int64_t  posInt_ = 0;        // whole input frames consumed so far
    uint64_t stepFixed_ = 1ull << 32;   // ratio * 2^32, updated per frame

    // Absolute stream coordinates.
    int64_t totalInFrames_ = 0;
    int64_t totalOutFrames_ = 0;

    // Drift state. The command from the controller is an atomic (written
    // from any thread, read once per process() call from the audio
    // callback). The applied drift slews toward it per OUTPUT frame, which
    // makes the ratio trajectory deterministic across block boundaries.
    std::atomic<int64_t> targetDriftNanoPerSec_{0};   // command, ns/s
    double driftPpm_ = 0.0;                           // slewed state
    double slewAlpha_ = 0.0;    // per-output-frame slew coefficient

    // Silent PLL (Gap #13). bypassEpoch_ bumps on every setSilentBypass()
    // call; process() detects the change on the audio thread and restarts
    // the stream (keeping drift + command) — race-free, lock-free.
    std::atomic<bool> silentBypass_{false};
    std::atomic<uint32_t> bypassEpoch_{0};
    uint32_t seenBypassEpoch_ = 0;   // audio-thread-private
};

} // namespace streamify::dsp

#endif // STREAMIFY_ACOUSTIC_PHASE_RESAMPLER_H
