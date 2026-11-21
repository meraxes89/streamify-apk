#pragma once
#include <cstddef>
#include <cstdint>

// ============================================================================
//  SoftKneeLimiter — true-peak soft-knee limiter (Phase-1 upgrade, Gap #39/#41)
// ============================================================================
//
//  ARCHITECTURE (feed-forward, frame-linked, low-lookahead)
//  --------------------------------------------------------
//  sidechain : 4x-oversampled TRUE-PEAK per frame (BS.1770-style: two cascaded
//              halfband interpolators, composed into three quarter-offset
//              kernels on the base grid; the base grid itself is an exact
//              passthrough point of the tree, so p0 = |x[t]| bit-exactly).
//  curve     : tanh soft knee in the LINEAR domain — identity below
//              kneeLin = ceiling * 10^(-kneeDb/20) (bit-transparent),
//              C1-continuous at the knee, saturating to the ceiling with
//              slope -> 0. No fixed ratio: the compression slope varies
//              smoothly from 1:1 to inf:1 (the "smooth tanh compression
//              curve" of the directive).
//  lookahead : per-call sliding-min over `windowFrames_` (default 3 ms) via
//              a pre-allocated monotonic deque; gain falls with a 1.5 ms
//              one-pole (attack) and recovers with an 80 ms one-pole
//              (release). Both gains of a frame are IDENTICAL for all
//              channels (stereo image preserved — the pre-Phase-1 engine
//              modulated L and R of the same frame differently).
//  safety    : memoryless C1 tanh stage at the ceiling — bit-exact identity
//              below the ceiling, bounded at ceiling*1.004 above it. This
//              makes the OUTPUT bound absolute: |y| <= ceiling * 1.004 no
//              matter what the ballistics do.
//
//  BLOCK SEMANTICS: every call processes its input completely (legacy
//  contract — the old engine did the same). Lookahead therefore lives
//  *within* a call: early frames of a block see up to 3 ms of future, the
//  last ~3 ms of a block is covered by the attack ballistics + the absolute
//  safety stage. A 17-frame input tail is carried across calls for the
//  sidechain neighborhood so inter-call boundaries measure true peaks.
//
//  Zero allocation on the hot path; the only allocation is the deque at
//  setSampleRate() (control path).
// ============================================================================

class SoftKneeLimiter {
public:
    // ceilingDb: true-peak ceiling in dBFS (EU default -1.0).
    // kneeDb: dB below the ceiling where the curve is still identity.
    // ratio: retained for source compatibility; the tanh shoulder defines
    //        the compression shape (see header docs).
    SoftKneeLimiter(float ceilingDb = -1.0f, float kneeDb = 4.0f,
                    float ratio = 20.0f);
    ~SoftKneeLimiter();

    SoftKneeLimiter(const SoftKneeLimiter&) = delete;
    SoftKneeLimiter& operator=(const SoftKneeLimiter&) = delete;

    // ---- configuration (control path; cheap enough to re-apply per block) --
    void setParameters(float ceilingDb, float kneeDb);   // legacy entry point
    void setCeilingDb(float ceilingDb);                  // clamp [-12, 0]
    void setKneeDb(float kneeDb);                        // clamp [0.5, 12]
    void setSampleRate(int sampleRateHz);                // window + ballistics
    float ceilingDb() const { return ceilingDb_; }
    float kneeDb() const { return kneeWidthDb_; }
    int windowFrames() const { return windowFrames_; }

    // ---- processing (audio path; zero allocation) --------------------------
    // Linked multi-channel path used by MasterChain. `interleaved` is modified
    // in place; frames*channels floats.
    void processFrames(float* interleaved, int frames, int channels);

    // Legacy mono adapters (jni_bridge.cc, tests, fuzzer).
    void processInterleavedSIMD(float* pcm_samples, size_t total_samples);
    void processFloats(float* buffer, int numSamples);
    void processShorts(int16_t* buffer, int numSamples);

    void reset();

    // Independent true-peak measurement of a buffer (4x oversampled, max over
    // channels/frames). Used by tests to verify ceiling enforcement.
    float measureTruePeak(const float* interleaved, int frames, int channels);

    static constexpr int kMaxChannels = 8;
    static constexpr int kSideTaps = 25;    // 2*kSideHist + 1
    static constexpr int kSideHist = 12;    // composite kernel half-span

private:
    void rebuildDerived();
    void buildKernels();                     // composite halfband kernels (once)
    float curveLin(float x) const;           // tanh soft-knee static curve
    float satStage(float v) const;           // memoryless ceiling guard

    // True peak at frame j of the current call. `hist` is the previous call's
    // 17-sample tail per channel (x[-17..-1]); frames beyond the buffer are 0.
    float truePeakAt(const float* io, int frames, int j, int channels,
                      const float (&hist)[kMaxChannels][kSideTaps]) const;

    // Monotonic deque over (idx, schedule value in dB):
    // greqDb(idx) + rampDbPerFrame_*idx — see the schedule note in processFrames.
    // int64 index (audit #22 item 9 hardening): per-call frame
    // indices (dqClear() resets every processFrames call — no
    // cross-call accumulation), widened so no arithmetic path can
    // approach signed overflow even for pathological frame counts.
    struct GainPoint {
        int64_t idx;
        float g;
    };
    void dqClear();
    void dqPush(int64_t idx, float g);

    // Configuration.
    float ceilingDb_ = -1.0f;
    float kneeWidthDb_ = 4.0f;
    float ratio_ = 20.0f;
    float ceilLin_ = 0.891f;
    float kneeLin_ = 0.562f;
    int fs_ = 48000;
    int windowFrames_ = 144;                 // 3 ms @ 48k
    float rampDbPerFrame_ = 0.0833f;         // 12 dB ramp budget / window
    float releaseCoef_ = 0.0f;               // 80 ms one-pole (upward only)

    // Composite kernels for sub-phases 1..3 (quarter-sample offsets);
    // phase 0 is the exact passthrough sample. kernel_[p][q + kSideHist].
    float kernel_[3][kSideTaps] = {};

    // Cross-call sidechain tail: last kSideTaps input frames per channel.
    float tailHist_[kMaxChannels][kSideTaps] = {};

    // Ballistics state.
    float gsDb_ = 0.0f;   // applied gain, dB (0 == unity)

    // Monotonic deque storage.
    GainPoint* deque_ = nullptr;
    int dqCap_ = 0;
    int dqHead_ = 0;
    int dqTail_ = 0;
};

namespace streamify {
namespace dsp {
using SoftKneeLimiter = ::SoftKneeLimiter;
}
}  // namespace streamify
