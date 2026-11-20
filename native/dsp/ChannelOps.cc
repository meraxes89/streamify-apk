#include "ChannelOps.h"

#include <algorithm>
#include <cmath>

#include "../util/Finite.h"
#include "../util/NeonCompat.h"

namespace streamify::dsp {

void ChannelOps::recomputeGains() {
    // theta = (pi/4) * (1 + balance): -1 -> 0 (full L), +1 -> pi/2 (full R).
    // Scaled by sqrt(2): gL^2 + gR^2 == 2 at every position, so the CENTER
    // is exactly unity (bit-transparent) and the extremes are (sqrt2, 0).
    constexpr float kSqrt2 = 1.41421356237309504880f;
    const float theta = (static_cast<float>(M_PI) * 0.25f) * (1.0f + balance_);
    gL_ = kSqrt2 * std::cos(theta);
    gR_ = kSqrt2 * std::sin(theta);
    // Renormalize to gL^2 + gR^2 == 2 (defends against fast-math
    // reassociation on device and locks the center to exactly 1.0).
    const float p = std::sqrt(0.5f * (gL_ * gL_ + gR_ * gR_));
    if (p > 1e-9f) { gL_ /= p; gR_ /= p; }
    // Exact muting at the extremes: cos(float(pi/2)) leaves a ~4e-8 speck
    // (phase-inverted residual). Snap it so full-left/full-right are true.
    if (std::fabs(gL_) < 1e-6f) gL_ = 0.0f;
    if (std::fabs(gR_) < 1e-6f) gR_ = 0.0f;
}

void ChannelOps::setBalance(float balance) {
    // Bit-pattern NaN guard on the INPUT (audit #22 item 2): the old
    // `if (!(balance_ >= -1.0f))` backstop elides under prod
    // -ffinite-math-only, and clamp() alone is UB on NaN — sanitize
    // first, then clamp.
    balance_ = std::clamp(
        util::f32_finite_or(balance, 0.0f), -1.0f, 1.0f);
    recomputeGains();
}

void ChannelOps::processInterleaved(float* io, size_t frames) {
    if (io == nullptr || frames == 0) return;

    if (!mono_) {
        // Stereo balance: multiply by [gL, gR] lanes — no deinterleave needed.
#if STREAMIFY_HAVE_NEON
        alignas(16) float lanes[4] = {gL_, gR_, gL_, gR_};
        const float32x4_t g = vld1q_f32(lanes);
        size_t i = 0;
        for (; i + 2 <= frames; i += 2) {
            float* p = io + i * 2;
            vst1q_f32(p, vmulq_f32(vld1q_f32(p), g));
        }
        for (; i < frames; ++i) {
            io[i * 2] *= gL_;
            io[i * 2 + 1] *= gR_;
        }
#else
        for (size_t i = 0; i < frames; ++i) {
            io[i * 2] *= gL_;
            io[i * 2 + 1] *= gR_;
        }
#endif
        return;
    }

    // Mono: M = (L + R)/sqrt(2) on the raw channels, then constant-power pan
    // of the dual-mono result: outL = M*gL, outR = M*gR.
#if STREAMIFY_HAVE_NEON
    const float32x4_t gL4 = vdupq_n_f32(gL_);
    const float32x4_t gR4 = vdupq_n_f32(gR_);
    const float32x4_t invSqrt2 = vdupq_n_f32(kInvSqrt2);
    size_t i = 0;
    for (; i + 2 <= frames; i += 2) {
        float* p = io + i * 2;
        const float32x4x2_t lr = vld2q_f32(p);      // {L0,L1}, {R0,R1}
        // M = (L + R) * invSqrt2  (directive: (L+R)/sqrt(2), -3 dB center).
        const float32x4_t m =
            vmulq_f32(vaddq_f32(lr.val[0], lr.val[1]), invSqrt2);
        float32x4x2_t dual;
        dual.val[0] = vmulq_f32(m, gL4);            // outL = M * gL
        dual.val[1] = vmulq_f32(m, gR4);            // outR = M * gR
        vst2q_f32(p, dual);
    }
    for (; i < frames; ++i) {
        const float l = io[i * 2];
        const float r = io[i * 2 + 1];
        const float m = (l + r) * kInvSqrt2;
        io[i * 2] = m * gL_;
        io[i * 2 + 1] = m * gR_;
    }
#else
    for (size_t i = 0; i < frames; ++i) {
        const float l = io[i * 2];
        const float r = io[i * 2 + 1];
        const float m = (l + r) * kInvSqrt2;
        io[i * 2] = m * gL_;
        io[i * 2 + 1] = m * gR_;
    }
#endif
}

}  // namespace streamify::dsp
