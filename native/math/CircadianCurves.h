#ifndef STREAMIFY_CIRCADIAN_CURVES_H
#define STREAMIFY_CIRCADIAN_CURVES_H
// ============================================================================
//  CircadianCurves.h — continuous daypart energy weighting (Phase 2,
//  BEHIND.md gap #20: Daylist Circadian Scheduler)
// ============================================================================
//
//  MANDATE (Phase-2 directive §3C): the Daylist scheduler needs target
//  energy / acousticness / valence as CONTINUOUS functions of the local
//  float hour t in [0, 24) — zero step discontinuities at the classic
//  morning/afternoon/evening/late-night boundaries (ChronosProfiler's
//  hard slot edges 6/11/17/22 are exactly the artifact to eliminate).
//
//  CONSTRUCTION — circular raised-cosine interpolation through 4 calibrated
//  anchors (one per ChronosProfiler slot, centered on each slot's midpoint):
//
//      hour  2.0 (night deep)     energy 0.15  acousticness 0.75  valence 0.30
//      hour  8.5 (morning)        energy 0.55  acousticness 0.60  valence 0.65
//      hour 14.0 (afternoon)      energy 0.70  acousticness 0.35  valence 0.80
//      hour 19.5 (evening peak)   energy 0.90  acousticness 0.20  valence 0.75
//
//  Between adjacent anchors the blend is s(u) = u - sin(2*pi*u)/(2*pi)
//  (u in [0,1]): s(0)=0, s(1)=1, and s'(0)=s'(1)=0, so the curve is C1
//  continuous across every anchor INCLUDING the 24h -> 0h wrap (the last
//  segment interpolates 19.5h -> 26.0h == 2.0h). Interpolation between two
//  values stays inside their range, so every component is bounded by its
//  anchor extremes and needs no extra clamping; a defensive clamp keeps
//  the [0,1] contract under any float scenario.
//
//  CONTRACT
//  * circadianWeights(t): t wraps mod 24 (negative hours too); non-finite
//    t returns the neutral {0.5, 0.5, 0.5} (missing clock data must not
//    skew the Daylist). All outputs in [0,1], deterministic on every ABI.
//  * Zero heap allocations; ~10 flops per call (the Daylist scheduler can
//    sample per-track per-refresh at zero cost).
// ============================================================================

#include <cstdint>

#include "MathExport.h"

namespace streamify {
namespace math {

struct CircadianWeights {
    float energy;     // target danceability/energy (peak 19.5h, trough 2.0h)
    float acousticness;   // target acousticness (peak 2.0h, trough 19.5h)
    float valence;    // target positivity (peak 14.0h, trough 2.0h)
};

// Neutral weights for non-finite input (see contract above).
inline constexpr CircadianWeights kCircadianNeutral{0.5f, 0.5f, 0.5f};

// The 4 calibration anchors (see table in the header comment). Exposed for
// tests and for the Kotlin Daylist UI to draw the same curves.
struct CircadianAnchor {
    float hour;
    CircadianWeights weights;
};
inline constexpr CircadianAnchor kCircadianAnchors[4] = {
    {2.0f, {0.15f, 0.75f, 0.30f}},    // night deep
    {8.5f, {0.55f, 0.60f, 0.65f}},    // morning
    {14.0f, {0.70f, 0.35f, 0.80f}},   // afternoon
    {19.5f, {0.90f, 0.20f, 0.75f}},   // evening peak
};

// Continuous daypart weights for localHour in [0, 24) (wraps otherwise).
CircadianWeights circadianWeights(float localHour);

}  // namespace math
}  // namespace streamify

// ---------------------------------------------------------------------------
// Frozen C-ABI for Rust consumers (extern "C" blocks in Engineer 2's crates).
// ---------------------------------------------------------------------------
extern "C" {

// Writes [energy, acousticness, valence] to outWeights3 (>= 3 floats).
STREAMIFY_MATH_API void streamify_circadian_weights(float hourOfDay,
                                                    float* outWeights3);

}  // extern "C"

#endif  // STREAMIFY_CIRCADIAN_CURVES_H
