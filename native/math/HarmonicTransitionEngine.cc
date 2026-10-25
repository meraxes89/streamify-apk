// ============================================================================
//  HarmonicTransitionEngine.cc — Camelot Wheel key compatibility (Phase 2)
// ============================================================================

#include "HarmonicTransitionEngine.h"

#include <algorithm>
#include <cmath>

#include "../util/Finite.h"

namespace streamify {
namespace math {
namespace {

// Circular helpers on the 12-position wheel.
inline int32_t forwardDist12(int32_t numberA, int32_t numberB) {
    // (B - A) mod 12, euclidean (always in [0, 11]).
    return ((numberB - numberA) % 12 + 12) % 12;
}

inline int32_t ringDistance(int32_t numberA, int32_t numberB) {
    const int32_t d = forwardDist12(numberA, numberB);
    return d <= 6 ? d : 12 - d;   // in [0, 6]
}

// Smoothstep (zero-slope endpoints) used by the clash falloff.
inline float smoothstep01(float t) {
    if (t < 0.0f) t = 0.0f;
    if (t > 1.0f) t = 1.0f;
    return t * t * (3.0f - 2.0f * t);
}

}  // namespace

uint32_t camelotPitchClass(int32_t number, bool isMinor) {
    // 1B = C major (pc 0); +1 wheel step = +7 semitones. 1A = A minor (pc 9).
    const uint32_t pc = (isMinor ? 9u : 0u) + 7u * static_cast<uint32_t>(number - 1);
    return pc % 12u;
}

KeyRelation classifyKeyTransition(int32_t numberA, bool isMinorA,
                                  int32_t numberB, bool isMinorB) {
    if (!camelotKeyValid(numberA) || !camelotKeyValid(numberB)) {
        return KeyRelation::Invalid;
    }
    if (numberA == numberB) {
        return isMinorA == isMinorB ? KeyRelation::ExactMatch
                                    : KeyRelation::Relative;
    }
    const bool sameMode = isMinorA == isMinorB;
    if (sameMode && ringDistance(numberA, numberB) == 1) {
        return KeyRelation::Adjacent;   // includes the 12 <-> 1 wrap
    }

    // Directed root movement in semitones (A tonic -> B tonic, mod 12).
    // Energy boosts are SAME-MODE by definition: shifting a whole track
    // up preserves its mode (8A -> 10A whole step, 8A -> 3A half step).
    // The +7 st shift is wheel adjacency (7 st == +1 Camelot step) and is
    // already claimed by the stronger 0.90 anchor above.
    const int32_t shift =
        static_cast<int32_t>(camelotPitchClass(numberB, isMinorB)) -
        static_cast<int32_t>(camelotPitchClass(numberA, isMinorA));
    const int32_t st = ((shift % 12) + 12) % 12;
    if (sameMode && st == 2) {
        return KeyRelation::EnergyBoostWhole;
    }
    if (sameMode && st == 1) {
        return KeyRelation::EnergyBoostHalf;
    }

    // Distance ladder (mode-agnostic, symmetric).
    const int32_t d = ringDistance(numberA, numberB);
    if (d == 1) {
        return KeyRelation::Diagonal;   // opposite-mode wheel neighbor
    }
    if (d == 2) {
        return KeyRelation::TwoStep;
    }
    return KeyRelation::Clash;          // d in [3, 6]
}

float keyTransitionScore(int32_t numberA, bool isMinorA, int32_t numberB,
                         bool isMinorB) {
    const KeyRelation rel = classifyKeyTransition(numberA, isMinorA, numberB,
                                                  isMinorB);
    switch (rel) {
        case KeyRelation::ExactMatch:
            return kKeyScoreExactMatch;
        case KeyRelation::Relative:
            return kKeyScoreRelative;
        case KeyRelation::Adjacent:
            return kKeyScoreAdjacent;
        case KeyRelation::EnergyBoostWhole:
            return kKeyScoreEnergyBoostWholeStep;
        case KeyRelation::EnergyBoostHalf:
            return kKeyScoreEnergyBoostHalfStep;
        case KeyRelation::Diagonal:
            return kKeyScoreDiagonal;
        case KeyRelation::TwoStep:
            return kKeyScoreTwoStep;
        case KeyRelation::Clash: {
            // Wheel distance D in [3, 6] -> smoothstep from 0.60 down to the
            // 0.10 floor (zero slope at both ends => no audible "seam" in
            // ordered queues as D steps).
            const int32_t d = ringDistance(numberA, numberB);
            const float t = static_cast<float>(d - 2) / 4.0f;
            const float s = smoothstep01(t);
            return kKeyScoreTwoStep - 0.50f * s;
        }
        case KeyRelation::Invalid:
        default:
            return kKeyScoreNeutral;
    }
}

float bpmTransitionScore(float bpmA, float bpmB) {
    // Missing/invalid tempo metadata is NEUTRAL, never punishing.
    // Bit-pattern check (audit #22 item 2 follow-up): std::isfinite folds
    // to `true` in the prod -ffinite-math-only build, so a NaN BPM fed by
    // malformed tags scored NaN (poisoned the whole queue ranking).
    if (!streamify::util::f32_is_finite(bpmA) ||
        !streamify::util::f32_is_finite(bpmB) || bpmA <= 0.0f ||
        bpmB <= 0.0f) {
        return kBpmNeutral;
    }

    float r = bpmB / bpmA;

    // Harmonic double-time / half-time detection (directive example:
    // 75 BPM <-> 150 BPM): ratios within +-5% of the octave fold to unity
    // before the tolerance window is applied. The boundary is a deliberate
    // detection threshold (see header). The +1e-4 slack absorbs float
    // representation noise at the edge (190/100 == 1.8999999762f would
    // otherwise miss the fold by one rounding step); it widens the window
    // by < 0.1% and keeps the boundary deterministic on every ABI.
    constexpr float kFoldSlack = 1.0e-4f;
    if (std::fabs(r - 2.0f) <= 2.0f * kBpmOctaveTolerance + kFoldSlack) {
        r *= 0.5f;
    } else if (std::fabs(r - 0.5f) <= 0.5f * kBpmOctaveTolerance + kFoldSlack) {
        r *= 2.0f;
    }

    const float d = std::fabs(r - 1.0f);
    if (d <= kBpmToleranceWindow) {
        // Inside the +-8% directive window: 1.0 at equal tempo, 0.68 at the
        // edge (kBpmWindowEdgeScore == 1 - 4 * window, kept literal here).
        return 1.0f - 4.0f * d;
    }
    // Beyond the window: value-continuous exponential decay toward 0.
    const float score =
        kBpmWindowEdgeScore * std::exp(-(d - kBpmToleranceWindow) /
                                       kBpmToleranceWindow);
    return score > 0.0f ? score : 0.0f;
}

float transitionCompatibility(int32_t numberA, bool isMinorA, float bpmA,
                              int32_t numberB, bool isMinorB, float bpmB) {
    const float key = keyTransitionScore(numberA, isMinorA, numberB, isMinorB);
    const float bpm = bpmTransitionScore(bpmA, bpmB);
    const float combined = kKeyWeight * key + kBpmWeight * bpm;
    return std::min(std::max(combined, 0.0f), 1.0f);
}

}  // namespace math
}  // namespace streamify

// ---------------------------------------------------------------------------
// Frozen C-ABI (Rust FFI).
// ---------------------------------------------------------------------------
extern "C" {

STREAMIFY_MATH_API float streamify_key_transition_score(int32_t numberA,
                                                        int32_t isMinorA,
                                                        int32_t numberB,
                                                        int32_t isMinorB) {
    return streamify::math::keyTransitionScore(numberA, isMinorA != 0, numberB,
                                               isMinorB != 0);
}

STREAMIFY_MATH_API float streamify_bpm_transition_score(float bpmA, float bpmB) {
    return streamify::math::bpmTransitionScore(bpmA, bpmB);
}

STREAMIFY_MATH_API float streamify_transition_score(int32_t numberA,
                                                    int32_t isMinorA,
                                                    float bpmA,
                                                    int32_t numberB,
                                                    int32_t isMinorB,
                                                    float bpmB) {
    return streamify::math::transitionCompatibility(numberA, isMinorA != 0,
                                                    bpmA, numberB,
                                                    isMinorB != 0, bpmB);
}

}  // extern "C"
