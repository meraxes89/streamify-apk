// ============================================================================
//  CircadianCurves.cc — continuous daypart energy weighting (Phase 2)
// ============================================================================

#include "CircadianCurves.h"

#include <cmath>

#include "../util/Finite.h"

namespace streamify {
namespace math {
namespace {

inline float clamp01(float x) {
    if (x < 0.0f) return 0.0f;
    if (x > 1.0f) return 1.0f;
    return x;
}

// Raised-cosine position on a segment: s(0)=0, s(1)=1, zero slope at both
// ends (C1 across anchors, including the 24h wrap).
inline float smoothPos(float u) {
    constexpr float kTwoPi = 6.283185307179586f;
    return u - std::sin(kTwoPi * u) / kTwoPi;
}

inline float lerp(float a, float b, float s) {
    return a + (b - a) * s;
}

}  // namespace

CircadianWeights circadianWeights(float localHour) {
    // Bit-pattern check (audit #22 item 2 follow-up): std::isfinite folds
    // to `true` in the prod -ffinite-math-only build — a NaN clock read
    // would propagate NaN weights into every radio score.
    if (!streamify::util::f32_is_finite(localHour)) {
        return kCircadianNeutral;
    }

    // Wrap into [0, 24): handles negatives, 24.0, and multi-day values.
    float t = std::fmod(localHour, 24.0f);
    if (t < 0.0f) {
        t += 24.0f;
    }

    // Find the anchor segment [h_i, h_{i+1}) containing t, circularly. The
    // last segment runs 19.5h -> 26.0h (== anchor 0 + 24): t in [19.5, 24)
    // wraps `next` back to anchor 0, t in [0, 2.0) lifts onto the same
    // segment via tSeg = t + 24.
    int next = 0;
    while (next < 4 && kCircadianAnchors[next].hour <= t) {
        ++next;
    }
    if (next == 4) {
        next = 0;   // t in [19.5, 24): circular segment 19.5 -> 26.0
    }
    const int prev = (next == 0) ? 3 : next - 1;
    const float h0 = kCircadianAnchors[prev].hour;
    const float h1 = (next == 0) ? kCircadianAnchors[0].hour + 24.0f
                                 : kCircadianAnchors[next].hour;
    const float tSeg = (t < h0) ? t + 24.0f : t;

    const float u = (tSeg - h0) / (h1 - h0);   // in [0, 1)
    const float s = smoothPos(u);

    const CircadianWeights& w0 = kCircadianAnchors[prev].weights;
    const CircadianWeights& w1 = (next == 0) ? kCircadianAnchors[0].weights
                                             : kCircadianAnchors[next].weights;

    CircadianWeights out;
    out.energy = clamp01(lerp(w0.energy, w1.energy, s));
    out.acousticness = clamp01(lerp(w0.acousticness, w1.acousticness, s));
    out.valence = clamp01(lerp(w0.valence, w1.valence, s));
    return out;
}

}  // namespace math
}  // namespace streamify

// ---------------------------------------------------------------------------
// Frozen C-ABI (Rust FFI) — see CircadianCurves.h.
// ---------------------------------------------------------------------------
extern "C" {

STREAMIFY_MATH_API void streamify_circadian_weights(float hourOfDay,
                                                    float* outWeights3) {
    if (outWeights3 == nullptr) {
        return;
    }
    const streamify::math::CircadianWeights w =
        streamify::math::circadianWeights(hourOfDay);
    outWeights3[0] = w.energy;
    outWeights3[1] = w.acousticness;
    outWeights3[2] = w.valence;
}

}  // extern "C"
