#pragma once
#include <cstddef>

// ============================================================================
//  ChannelOps — constant-power L/R balance + SIMD mono downmix (Gap #41)
// ============================================================================
//
//  BALANCE (constant-power law scaled to UNITY at center, "zero volume dips")
//  --------------------------------------------------------------------------
//  balance in [-1, +1] maps to theta = (pi/4) * (1 + balance):
//      gL = sqrt(2) * cos(theta),  gR = sqrt(2) * sin(theta)
//      ->  gL^2 + gR^2 == 2 exactly at EVERY balance position.
//      balance =  0 -> (1, 1): center is a bit-exact no-op (playback
//                             balance semantics, not a pan mixer);
//      balance = -1 -> (sqrt2, 0) full left; +1 -> (0, sqrt2) full right.
//  The total output power of a mono source is constant across the whole
//  sweep (zero volume dips by construction); the kept channel rises to
//  +3.01 dB only at the extremes, where the far channel is fully muted.
//
//  MONO DOWNMIX (BEHIND.md #41, directive formula)
//  -----------------------------------------------
//      M = (L + R) / sqrt(2)         ("calibrated -3 dB center gain": each
//                                     source channel enters the mix at -3 dB)
//  The mono mix value M is exposed through monoMix(); the OUTPUT applies the
//  balance pan on top of the dual-mono signal:
//      outL = M * gL,  outR = M * gR   (total power = M^2, no volume dip).
//  With balance at an extreme the visible output IS the directive formula:
//  balance = +1 -> outR = (L + R)/sqrt(2) exactly, outL = 0.
//
//  NEON: the stereo path is a [gL, gR, gL, gR] multiply (no deinterleave
//  needed); the mono path is one vld2q + two FMAs + vst2q. Scalar fallback
//  mirrors the math exactly (host builds, CI sanitizer shards).
// ============================================================================

namespace streamify::dsp {

class ChannelOps {
public:
    ChannelOps() = default;

    void setBalance(float balance);          // clamped to [-1, 1]
    float balance() const { return balance_; }
    void setMonoDownmix(bool enabled) { mono_ = enabled; }
    bool monoDownmix() const { return mono_; }

    // Current panning gains (constant power).
    void gains(float* gL, float* gR) const {
        *gL = gL_;
        *gR = gR_;
    }

    // Mono mix value for the current input frame pair: M = (L+R)/sqrt(2).
    static float monoMix(float l, float r) {
        return (l + r) * kInvSqrt2;
    }

    // In-place processing of `frames` INTERLEAVED STEREO frames
    // (io[2*i] = L, io[2*i+1] = R). Zero allocation, no branches in the loop.
    void processInterleaved(float* io, size_t frames);

    static constexpr float kInvSqrt2 = 0.70710678118654752440f;

private:
    void recomputeGains();

    // Gains initialize to the CENTER of the balance law — (1, 1), a
    // bit-exact no-op — NOT kInvSqrt2 (audit #22 item 4): a standalone
    // instance that never calls setBalance() used to silently attenuate
    // every frame by -3.01 dB. MasterChain masked the bug by folding a
    // setBalance() into every process block.
    float balance_ = 0.0f;
    bool mono_ = false;
    float gL_ = 1.0f;
    float gR_ = 1.0f;
};

}  // namespace streamify::dsp
