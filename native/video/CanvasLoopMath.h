#ifndef STREAMIFY_CANVAS_LOOP_MATH_H
#define STREAMIFY_CANVAS_LOOP_MATH_H
// ============================================================================
//  CanvasLoopMath.h — seamless 8-second Canvas loop mathematics (Phase 3,
//  BEHIND.md #45 "Canvas 8s loops on Now Playing (reuse AGSL/ambient-glow
//  infra)")
// ============================================================================
//
//  Two loop strategies, exactly as mandated by the Phase-3 directive:
//
//  * kPingPong — triangle phase: motion runs 0 -> 1 -> 0 over the period.
//    Continuous at the seam BY CONSTRUCTION (phase hits 0 at both ends), so
//    no crossfade is needed; the trade-off is that motion direction reverses
//    at the turnarounds.
//
//  * kWraparoundCrossfade — sawtooth phase: motion runs 0 -> 1 and wraps.
//    The wrap seam is hidden by a raised-cosine boundary crossfade that
//    blends the current phase with a LAGGED copy (phase - 1). With weight
//    w(0)=0 and w(1-)=1 the blended signal s(p) = (1-w) g(p) + w g(p-1) is
//    C0-continuous across the seam, and because the raised cosine has zero
//    end-slopes the composite derivative is C1 as well:
//        s'(1-) = g'(0) + w'(1)(g(0)-g(1)) = g'(0)   since w'(1)=0
//        s'(0+) = g'(0) + w'(0)(g(-1)-g(0)) = g'(0)  since w'(0)=0
//
//  Bit-exact periodicity: every periodic term is derived from the WRAPPED
//  phase p (never from raw time), and wraparoundPhase(T) == wraparoundPhase(0)
//    == 0.0f exactly (t/period lands on an integer and the fractional part
//    underflows to zero). All derived floats are therefore bit-identical at
//    t = 0 and t = period — the seam is provably invisible, not merely small.
//
//  Zero allocation: static functions over POD outputs only.
// ============================================================================

#include <cstdint>

namespace streamify::video {

enum class LoopStrategy : int32_t {
    kPingPong = 0,
    kWraparoundCrossfade = 1,
};

// Tunables for one looping canvas (all fields plain floats: pass-by-value
// friendly, JNI-packable as 2 floats — see AmbientGlowRuntime).
struct CanvasLoopConfig {
    LoopStrategy strategy = LoopStrategy::kWraparoundCrossfade;
    float periodSec = 8.0f;   // Spotify Canvas loop length
    float blendSec = 1.0f;    // crossfade window (wraparound only)
};

// Per-frame loop state consumed by both the canvas transform and the AGSL
// uniform packer. Every field is periodic in periodSec.
struct CanvasLoopFrame {
    float phase;            // [0,1) sampling phase (triangle when ping-pong)
    float lagPhase;         // phase - 1 (wraparound; == phase when ping-pong)
    float crossfadeWeight;  // [0,1] weight of the lagged copy (0: ping-pong)
    float scale;            // breathing scale about 1.0
    float rotationRad;      // drift rotation, odd-symmetric in phase
    float translateX;       // parallax offset, unit circle
    float translateY;
    float glowPulse;        // [0,1] breathing intensity peak at phase 0
    float hueDriftRad;      // hue rotation angle (full turns per loop)
};

class CanvasLoopMath {
public:
    // Triangle wave in [0,1]: 0 at x=0, 1 at x=0.5, back to 0 at x=1.
    // Input is seconds; negative time wraps (loops are periodic).
    static float pingPongPhase(float tSec, float periodSec);

    // Sawtooth in [0,1): the raw wraparound phase.
    static float wraparoundPhase(float tSec, float periodSec);

    // Raised-cosine boundary crossfade weight for the LAGGED copy.
    // 0 while phase <= 1 - blendFrac, then a smooth 0 -> 1 ramp to the seam
    // (C1 at both ramp ends: zero slope). blendFrac is clamped to (0, 0.5].
    static float boundaryCrossfadeWeight(float phase01, float blendFrac);

    // Full per-frame evaluation. Amplitudes are caller-tuned; every derived
    // term is a function of the wrapped phase only (bit-exact periodicity —
    // see header top). out must be non-null; writes 9 floats, allocates
    // nothing.
    static void computeFrame(float tSec, const CanvasLoopConfig& cfg,
                             float scaleAmp, float rotateAmpRad,
                             float translateAmp, float hueTurns,
                             CanvasLoopFrame* out);

    // C1-smooth unit sawtooth used by the crossfade ramp (exposed for tests):
    // smooth01(u) = u - sin(2*pi*u) / (2*pi),  smooth01(0)=0, smooth01(1)=1,
    // zero slope at both ends.
    static float smooth01(float u);
};

}  // namespace streamify::video

#endif  // STREAMIFY_CANVAS_LOOP_MATH_H
