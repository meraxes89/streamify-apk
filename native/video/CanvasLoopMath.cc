// ============================================================================
//  CanvasLoopMath.cc — see CanvasLoopMath.h for the math contracts.
// ============================================================================

#include "CanvasLoopMath.h"

#include <cmath>

#include "../util/Finite.h"

namespace streamify::video {

namespace {
constexpr float kTau = 6.28318530717958647692f;

// Fractional part of x, always in [0,1) — including for negative x and for
// exact integers (frac(1.0f) == 0.0f, the bit-exact-seam cornerstone).
// Non-finite x (|t/period| overflowed to Inf) maps to 0.0f: the phase of an
// astronomically-dominated quotient is unknowable at float precision, and
// NaN would poison every derived frame field. Found by the hostile-float
// fuzz section (t ~ 1e27 s against a denormal period).
// Bit-pattern check (audit #22 item 2 follow-up): std::isfinite folds to
// `true` under the prod -ffinite-math-only build — the fuzz-found guard
// was elided in exactly the build that ships. See util/Finite.h.
inline float frac01(float x) {
    if (!streamify::util::f32_is_finite(x)) {
        return 0.0f;
    }
    float f = x - std::floor(x);
    // Guard the x == negative-tiny case where floor gives -1 and f lands at
    // 1.0f (still representable) — map it to 0.
    return (f >= 1.0f) ? 0.0f : f;
}
}  // namespace

float CanvasLoopMath::pingPongPhase(float tSec, float periodSec) {
    if (!(periodSec > 0.0f) || !streamify::util::f32_is_finite(periodSec)) {
        return 0.0f;
    }
    if (!streamify::util::f32_is_finite(tSec)) {
        return 0.0f;
    }
    const float x = frac01(tSec / periodSec);
    // Triangle: rises 0->1 on [0,0.5), falls 1->0 on [0.5,1).
    return (x < 0.5f) ? (2.0f * x) : (2.0f - 2.0f * x);
}

float CanvasLoopMath::wraparoundPhase(float tSec, float periodSec) {
    if (!(periodSec > 0.0f) || !streamify::util::f32_is_finite(periodSec)) {
        return 0.0f;
    }
    if (!streamify::util::f32_is_finite(tSec)) {
        return 0.0f;
    }
    return frac01(tSec / periodSec);
}

float CanvasLoopMath::smooth01(float u) {
    if (!(u > 0.0f)) {
        return 0.0f;
    }
    if (u >= 1.0f) {
        return 1.0f;
    }
    return u - std::sin(kTau * u) / kTau;
}

float CanvasLoopMath::boundaryCrossfadeWeight(float phase01, float blendFrac) {
    // Clamp the blend window to (0, 0.5] — degenerate inputs never divide by
    // zero and never smear the crossfade past half the loop.
    if (!(blendFrac > 0.0f)) {
        return 0.0f;
    }
    if (blendFrac > 0.5f) {
        blendFrac = 0.5f;
    }
    // Normalize the phase into [0,1).
    float p = phase01;
    if (!streamify::util::f32_is_finite(p)) {
        return 0.0f;
    }
    p = p - std::floor(p);
    if (p >= 1.0f) {
        p = 0.0f;
    }
    const float rampStart = 1.0f - blendFrac;
    if (p <= rampStart) {
        return 0.0f;
    }
    // Raised-cosine (== smooth01-scaled) 0 -> 1 across the ramp. Zero slope
    // at both ramp ends gives the C1 composite seam documented in the header.
    return smooth01((p - rampStart) / blendFrac);
}

void CanvasLoopMath::computeFrame(float tSec, const CanvasLoopConfig& cfg,
                                  float scaleAmp, float rotateAmpRad,
                                  float translateAmp, float hueTurns,
                                  CanvasLoopFrame* out) {
    if (out == nullptr) {
        return;
    }
    // Degenerate config -> static frame (never NaN, never garbage).
    float period = cfg.periodSec;
    if (!(period > 0.0f) || !streamify::util::f32_is_finite(period) ||
        !streamify::util::f32_is_finite(tSec)) {
        out->phase = 0.0f;
        out->lagPhase = 0.0f;
        out->crossfadeWeight = 0.0f;
        out->scale = 1.0f;
        out->rotationRad = 0.0f;
        out->translateX = 0.0f;
        out->translateY = 0.0f;
        out->glowPulse = 0.5f;
        out->hueDriftRad = 0.0f;
        return;
    }

    const float phase = (cfg.strategy == LoopStrategy::kPingPong)
                            ? pingPongPhase(tSec, period)
                            : wraparoundPhase(tSec, period);

    // Wraparound: blend in the lagged copy near the seam. Ping-pong needs no
    // crossfade (the triangle phase is already continuous at the wrap).
    float lagPhase = phase;
    float crossfade = 0.0f;
    if (cfg.strategy == LoopStrategy::kWraparoundCrossfade) {
        lagPhase = phase - 1.0f;
        float blend = cfg.blendSec;
        if (!streamify::util::f32_is_finite(blend) || blend <= 0.0f) {
            blend = 0.0f;  // degenerate window: pure hard wrap
        }
        crossfade = boundaryCrossfadeWeight(phase, blend / period);
    }

    // Every motion term below is a function of the WRAPPED phase only, so
    // frame(t=0) and frame(t=period) are bit-identical (phase is exactly
    // 0.0f at both, and cos/sin(0) are exact).
    const float th = kTau * phase;
    const float c = std::cos(th);
    const float s = std::sin(th);

    out->phase = phase;
    out->lagPhase = lagPhase;
    out->crossfadeWeight = crossfade;
    out->scale = 1.0f + scaleAmp * c;
    out->rotationRad = rotateAmpRad * s;
    out->translateX = translateAmp * c;
    out->translateY = translateAmp * s;
    out->glowPulse = 0.5f + 0.5f * c;
    out->hueDriftRad = kTau * hueTurns * phase;
}

}  // namespace streamify::video
