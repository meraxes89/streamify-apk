#pragma once
#include <atomic>

#include "ChannelOps.h"
#include "LufsNormalizer.h"
#include "SoftKneeLimiter.h"

// ============================================================================
//  MasterChain — the audiophile playback chain (Phase 1, Gaps #39/#41/#13)
// ============================================================================
//
//  Signal flow (per PCM block, in place):
//
//      [LUFS glide gain]  ->  [mono downmix + constant-power balance]
//                          ->  [true-peak soft-knee limiter]
//
//  * Measurement runs on the PRE-gain signal (the track as received), so
//    user-side balance/mono/ceiling choices never contaminate normalization.
//  * The limiter is last: it bounds whatever the mono sum or panning produced.
//  * SILENT BYPASS (Gap #13, SINGLE_RENDER party mode): process() returns
//    before touching a single sample — zero CPU on the audio path, zero
//    AudioTrack buffer writes, while the PTP/Kalman clock filter keeps
//    running on the network thread (driven by its own timestamp path) so any
//    guest can take over rendering instantly.
//
//  THREADING: setters run on a control thread and publish through atomics;
//  process() reads them at the head of each block and applies them to the
//    sub-engines on the audio thread (plain members afterwards — race-free).
// ============================================================================

namespace streamify::dsp {

class MasterChain {
public:
    MasterChain() = default;

    // Geometry (control path). Returns true if anything changed.
    bool configure(int sampleRate, int channels);

    // ---- settings (control thread, lock-free publication) ------------------
    void setLufsTarget(float lufs);            // clamp [-23, -11]; default -14
    void setLimiterCeiling(float ceilingDb);   // clamp [-12, 0]; default -1.0
    void setMonoDownmix(bool on);
    void setBalance(float balance);            // clamp [-1, 1]
    void setSilentBypass(bool on);             // Gap #13
    bool silentBypass() const {
        return bypass_.load(std::memory_order_relaxed);
    }

    // ---- hot path (audio thread; zero allocation, zero locks) --------------
    // Interleaved float PCM in place. In silent bypass: immediate return.
    void process(float* interleaved, int frames);

    // ---- readouts -----------------------------------------------------------
    float integratedLufs() const { return lufs_.integratedLufs(); }
    float momentaryLufs() const { return lufs_.momentaryLufs(); }
    float shortTermLufs() const { return lufs_.shortTermLufs(); }
    float appliedGainDb() const { return lufs_.appliedGainDb(); }
    float limiterCeilingDb() const { return limiter_.ceilingDb(); }
    int sampleRate() const { return sampleRate_; }
    int channels() const { return channels_; }

private:
    LufsNormalizer lufs_;
    ChannelOps channel_;
    SoftKneeLimiter limiter_;

    std::atomic<float> lufsTarget_{-14.0f};
    std::atomic<float> ceilingDb_{-1.0f};
    std::atomic<float> balance_{0.0f};
    std::atomic<bool> mono_{false};
    std::atomic<bool> bypass_{false};

    int sampleRate_ = 48000;
    int channels_ = 2;
};

}  // namespace streamify::dsp
