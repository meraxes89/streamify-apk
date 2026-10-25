// ============================================================================
//  CanvasLoopRenderer.cc — see CanvasLoopRenderer.h for the contracts.
// ============================================================================

#include "CanvasLoopRenderer.h"

#include <cmath>

#include "../util/Finite.h"

namespace streamify::video {

void CanvasLoopRenderer::reset(int64_t monotonicMs, const CanvasLoopConfig& cfg,
                               const CanvasLoopMotion& motion) {
    originMs_ = monotonicMs;
    paused_ = false;
    everReset_ = true;
    cfg_ = cfg;
    motion_ = motion;
    lastPhase_ = 0.0f;
}

void CanvasLoopRenderer::pause(int64_t monotonicMs) {
    if (paused_ || !everReset_) {
        return;
    }
    pausedAtMs_ = monotonicMs;
    paused_ = true;
}

void CanvasLoopRenderer::resume(int64_t monotonicMs) {
    if (!paused_) {
        return;
    }
    // Shift the origin forward by the pause duration: the loop continues
    // from the exact phase it froze on (no jump, no replayed content).
    originMs_ += (monotonicMs - pausedAtMs_);
    paused_ = false;
}

void CanvasLoopRenderer::seek(int64_t monotonicMs, float phase01) {
    if (!everReset_) {
        return;  // nothing to seek yet — reset() owns the first anchor
    }
    // Bit-pattern finite check (audit #22 item 2 follow-up): the prod
    // -ffinite-math-only build folds std::isfinite to `true` here.
    if (!streamify::util::f32_is_finite(phase01)) {
        phase01 = 0.0f;
    }
    float p = phase01 - std::floor(phase01);
    if (p >= 1.0f) {
        p = 0.0f;
    }
    float period = (streamify::util::f32_is_finite(cfg_.periodSec) &&
                    cfg_.periodSec > 0.0f)
                       ? cfg_.periodSec
                       : 8.0f;
    // Bound the anchor offset so p * period * 1000 stays inside int64 for
    // the cast below: a hostile/huge period (Inf passes the > 0 test) would
    // be float-cast-overflow UB. Real configs are [0.1, 120] s after AGSL
    // sanitization; one hour is far beyond any canvas loop.
    if (period > 3.6e6f) {
        period = 3.6e6f;
    }
    // Anchor so that the loop-relative time AT monotonicMs maps to `p`.
    // Ping-pong maps phase through the triangle: time fraction 0.5*p is
    // enough because triangle(p/2) == p for p in [0,1] (rise half only) —
    // but the seam-consistent choice is the rising edge for any strategy.
    originMs_ = monotonicMs - static_cast<int64_t>(p * period * 1000.0f);
    if (paused_) {
        pausedAtMs_ = monotonicMs;
    }
}

void CanvasLoopRenderer::advanceTo(int64_t monotonicMs, CanvasLoopFrame* outFrame) {
    if (outFrame == nullptr) {
        return;
    }
    if (!everReset_) {
        // Pre-reset quiescent frame: static, in-range, never garbage.
        *outFrame = CanvasLoopFrame{};
        outFrame->scale = 1.0f;
        outFrame->glowPulse = 0.5f;
        return;
    }
    int64_t effectiveMs = monotonicMs;
    if (paused_) {
        effectiveMs = pausedAtMs_;
    }
    const float tSec =
        static_cast<float>(effectiveMs - originMs_) * (1.0f / 1000.0f);
    CanvasLoopMath::computeFrame(tSec, cfg_, motion_.scaleAmp,
                                 motion_.rotateAmpRad, motion_.translateAmp,
                                 motion_.hueTurns, outFrame);
    lastPhase_ = outFrame->phase;
}

void CanvasLoopRenderer::composeCanvasMatrix(const CanvasLoopFrame& frame,
                                             float translateAmp,
                                             float translateScalePx,
                                             float out6[6]) {
    if (out6 == nullptr) {
        return;
    }
    const float tx = frame.translateX * translateScalePx;
    const float ty = frame.translateY * translateScalePx;
    const float c = std::cos(frame.rotationRad);
    const float s = std::sin(frame.rotationRad);
    // scale, then rotate, then translate:
    //   M = T * R * S   (row-major 2x3)
    //   [ c*sx  -s*sy   tx ]
    //   [ s*sx   c*sy   ty ]
    out6[0] = c * frame.scale;
    out6[1] = -s * frame.scale;
    out6[2] = tx;
    out6[3] = s * frame.scale;
    out6[4] = c * frame.scale;
    out6[5] = ty;
    (void)translateAmp;  // amplitude already folded into frame.translate*
}

}  // namespace streamify::video
