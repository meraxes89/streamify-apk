// ============================================================================
//  test_canvas_loop_agsl.cc — Phase 3 verification: seamless Canvas loop math
//  + AGSL ambient glow shader plumbing (BEHIND.md #45)
// ============================================================================
//
//  Covers the Phase-3 directive deliverable 1:
//    A. CanvasLoopMath — ping-pong / wraparound phases, boundary crossfade
//       weighting, C0+C1 seam of the blended signal, bit-exact periodicity.
//    B. CanvasLoopRenderer — origin anchoring, pause/resume without phase
//       jump, seek, canvas matrix composition.
//    C. AmbientGlowShader — shader source sanity, uniform tables, runtime
//       param generation (finite under hostile config/time, bit-exact at the
//       seam, zero-allocation hot path).
//
//  Linked into dsp_test_suite (native-dsp CI shard); entry point
//  run_canvas_loop_agsl_tests() is called from test_dsp.cc's main().
// ============================================================================

#include <cmath>
#include <cstdio>
#include <cstring>

#include "../agsl/AmbientGlowShader.h"
#include "../video/CanvasLoopMath.h"
#include "../video/CanvasLoopRenderer.h"

#include "AllocGuard.h"

using streamify::agsl::AmbientGlowConfig;
using streamify::agsl::AmbientGlowRuntime;
using streamify::agsl::GlowShaderParams;
using streamify::video::CanvasLoopConfig;
using streamify::video::CanvasLoopFrame;
using streamify::video::CanvasLoopMath;
using streamify::video::CanvasLoopRenderer;
using streamify::video::CanvasLoopMotion;
using streamify::video::LoopStrategy;

namespace {

int g_passed = 0;
int g_failed = 0;

void check(bool ok, const char* what) {
    if (ok) {
        ++g_passed;
    } else {
        ++g_failed;
        std::printf("    FAILED: %s\n", what);
    }
}

void checkNear(float got, float want, float tol, const char* what) {
    const bool ok = std::fabs(got - want) <= tol;
    if (!ok) {
        std::printf("    FAILED: %s (got %.7f want %.7f)\n", what, got, want);
    }
    check(ok, what);
}

// The aperiodic generator the crossfade must tame: a linear ramp. Its raw
// sawtooth sampling jumps by 1.0 at the seam; the lagged blend must be C0.
float blendedRamp(float phase01, float blendFrac) {
    const float w =
        CanvasLoopMath::boundaryCrossfadeWeight(phase01, blendFrac);
    return (1.0f - w) * phase01 + w * (phase01 - 1.0f);
}

}  // namespace

int run_canvas_loop_agsl_tests() {
    std::printf("[phase3] canvas loop + AGSL ambient glow\n");

    // ------------------------------------------------------------------
    // A. CanvasLoopMath
    // ------------------------------------------------------------------
    std::printf("  [canvas] loop phase primitives\n");
    check(CanvasLoopMath::wraparoundPhase(0.0f, 8.0f) == 0.0f, "wrap(0) == 0");
    check(CanvasLoopMath::wraparoundPhase(8.0f, 8.0f) == 0.0f,
          "wrap(8) == 0 (exact wrap)");
    checkNear(CanvasLoopMath::wraparoundPhase(2.0f, 8.0f), 0.25f, 0.0f,
              "wrap(2s of 8s) == 0.25");
    checkNear(CanvasLoopMath::wraparoundPhase(-1.0f, 8.0f), 0.875f, 1e-6f,
              "negative time wraps");
    check(CanvasLoopMath::pingPongPhase(0.0f, 8.0f) == 0.0f, "tri(0) == 0");
    checkNear(CanvasLoopMath::pingPongPhase(4.0f, 8.0f), 1.0f, 1e-6f,
              "tri(half) == 1");
    checkNear(CanvasLoopMath::pingPongPhase(6.0f, 8.0f), 0.5f, 1e-6f,
              "tri(3/4) == 0.5");
    check(CanvasLoopMath::pingPongPhase(8.0f, 8.0f) == 0.0f,
          "tri(period) == 0 (seam)");
    // Degenerate inputs never produce NaN.
    const float bad = CanvasLoopMath::wraparoundPhase(NAN, 8.0f);
    check(bad == 0.0f, "NaN time -> 0 phase");
    check(CanvasLoopMath::wraparoundPhase(1.0f, 0.0f) == 0.0f,
          "zero period -> 0 phase");

    std::printf("  [canvas] boundary crossfade weights\n");
    check(CanvasLoopMath::boundaryCrossfadeWeight(0.0f, 0.125f) == 0.0f,
          "weight at seam start == 0");
    check(CanvasLoopMath::boundaryCrossfadeWeight(0.5f, 0.125f) == 0.0f,
          "weight mid-loop == 0");
    checkNear(CanvasLoopMath::boundaryCrossfadeWeight(0.9375f, 0.125f), 0.5f,
              1e-5f, "weight at window midpoint == 0.5");
    check(CanvasLoopMath::boundaryCrossfadeWeight(0.99999f, 0.125f) > 0.999f,
          "weight approaches 1 at the seam");
    // Window clamping: blend > 0.5 clamps; blend <= 0 disables the blend.
    check(CanvasLoopMath::boundaryCrossfadeWeight(0.5f, 2.0f) == 0.0f,
          "oversized blend window clamped");
    check(CanvasLoopMath::boundaryCrossfadeWeight(0.99f, 0.0f) == 0.0f,
          "zero blend window disables");
    check(CanvasLoopMath::boundaryCrossfadeWeight(NAN, 0.125f) == 0.0f,
          "NaN phase -> 0 weight");

    std::printf("  [canvas] blended-signal seam continuity (C0 + C1)\n");
    {
        const float blendFrac = 1.0f / 8.0f;  // 1 s of the 8 s loop
        // C0: |s(seam-) - s(0)| stays at the sampling floor.
        const float jump =
            std::fabs(blendedRamp(1.0f - 1e-4f, blendFrac) -
                      blendedRamp(0.0f, blendFrac));
        check(jump < 1e-3f, "blended ramp seam jump < 1e-3 at 1e-4 sampling");
        // C1: slope steps near the seam are bounded (zero-slope ramp ends).
        // s(p) = p - w(p), so |ds/dp| <= 1 + w'max = 1 + 2/blendFrac = 17
        // for a 1/8 window; a raw sawtooth would step a full ramp (1.0).
        float maxStep = 0.0f;
        const int N = 4000;
        float prev = blendedRamp(0.0f, blendFrac);
        for (int i = 1; i <= N; ++i) {
            const float p = static_cast<float>(i) / static_cast<float>(N);
            const float v = blendedRamp(p < 1.0f ? p : 0.999999f, blendFrac);
            const float step = std::fabs(v - prev);
            if (step > maxStep) maxStep = step;
            prev = v;
        }
        check(maxStep < (2.0f / blendFrac + 2.0f) / static_cast<float>(N),
              "blended slope stays bounded across the seam");
    }

    std::printf("  [canvas] bit-exact frame periodicity (both strategies)\n");
    {
        for (int strat = 0; strat < 2; ++strat) {
            CanvasLoopConfig cfg;
            cfg.strategy = static_cast<LoopStrategy>(strat);
            cfg.periodSec = 8.0f;
            cfg.blendSec = 1.0f;
            CanvasLoopFrame f0{}, fT{}, f5T{};
            CanvasLoopMath::computeFrame(0.0f, cfg, 0.012f, 0.035f, 0.015f,
                                         1.0f, &f0);
            CanvasLoopMath::computeFrame(8.0f, cfg, 0.012f, 0.035f, 0.015f,
                                         1.0f, &fT);
            CanvasLoopMath::computeFrame(40.0f, cfg, 0.012f, 0.035f, 0.015f,
                                         1.0f, &f5T);
            check(std::memcmp(&f0, &fT, sizeof(f0)) == 0,
                  strat == 0 ? "ping-pong frame(T) bit-exact"
                             : "wraparound frame(T) bit-exact");
            check(std::memcmp(&f0, &f5T, sizeof(f0)) == 0,
                  "frame(5T) bit-exact");
        }
        // Frame field sanity on a mid-loop sample.
        CanvasLoopConfig cfg;
        cfg.periodSec = 8.0f;
        cfg.blendSec = 1.0f;
        CanvasLoopFrame f{};
        CanvasLoopMath::computeFrame(2.0f, cfg, 0.012f, 0.035f, 0.015f, 1.0f,
                                     &f);
        check(f.phase >= 0.0f && f.phase < 1.0f, "phase in [0,1)");
        check(f.crossfadeWeight == 0.0f, "no crossfade mid-loop");
        check(f.scale >= 0.988f && f.scale <= 1.012f, "scale near 1");
        check(std::fabs(f.rotationRad) <= 0.0351f, "rotation bounded");
        check(f.glowPulse >= 0.0f && f.glowPulse <= 1.0f, "glowPulse in [0,1]");
    }

    // ------------------------------------------------------------------
    // B. CanvasLoopRenderer
    // ------------------------------------------------------------------
    std::printf("  [canvas] renderer clock discipline\n");
    {
        CanvasLoopRenderer r;
        CanvasLoopConfig cfg;  // wraparound, 8 s, 1 s blend
        CanvasLoopMotion motion;
        r.reset(1000, cfg, motion);
        CanvasLoopFrame before{}, afterPause{}, advanced{};
        r.advanceTo(4200, &before);  // 3.2 s into the loop
        check(r.lastPhase() > 0.0f, "renderer produces phase");
        r.pause(4200);
        check(r.paused(), "paused flag");
        r.advanceTo(9000, &afterPause);
        check(std::memcmp(&before, &afterPause, sizeof(before)) == 0,
              "frozen frame while paused");
        r.resume(9000);
        r.advanceTo(9000, &advanced);
        check(std::memcmp(&before, &advanced, sizeof(before)) == 0,
              "resume continues at the frozen phase");
        // Seek: re-anchor at phase 0.5.
        r.seek(9000, 0.5f);
        CanvasLoopFrame sought{};
        r.advanceTo(9000, &sought);
        checkNear(sought.phase, 0.5f, 1e-4f, "seek lands on the target phase");
        // Hostile seek phases (audit #22 item 2 follow-up): NaN/Inf must
        // fall back to 0 instead of poisoning the anchor — the guard uses
        // the bit-pattern check so it survives the prod fast-math build.
        r.seek(9500, std::nanf(""));
        CanvasLoopFrame hostile{};
        r.advanceTo(9500, &hostile);
        check(hostile.phase >= 0.0f && hostile.phase < 1.0f &&
                  std::isfinite(hostile.phase),
              "seek(NaN) keeps phase finite in [0,1)");
        r.seek(9800, INFINITY);
        r.advanceTo(9800, &hostile);
        check(hostile.phase >= 0.0f && hostile.phase < 1.0f &&
                  std::isfinite(hostile.phase),
              "seek(+Inf) keeps phase finite in [0,1)");
        // Matrix composition: identity-ish at rest (rotation 0, scale ~1).
        float m6[6] = {0, 0, 0, 0, 0, 0};
        CanvasLoopRenderer::composeCanvasMatrix(sought, motion.translateAmp,
                                                1000.0f, m6);
        check(m6[0] > 0.98f && m6[0] < 1.02f, "matrix scaleX ~ 1");
        checkNear(m6[0], m6[4], 1e-5f, "uniform scale");
        checkNear(m6[1], -m6[3], 1e-6f, "rotation antisymmetry");
        // Pre-reset quiescent frame is valid.
        CanvasLoopRenderer fresh;
        CanvasLoopFrame q{};
        fresh.advanceTo(123, &q);
        check(q.scale == 1.0f && q.phase == 0.0f, "pre-reset frame is static");
        // Zero-allocation hot path.
        {
            streamify_test::AllocGuard guard;
            CanvasLoopFrame f{};
            r.advanceTo(9500, &f);
            CanvasLoopRenderer::composeCanvasMatrix(f, 0.015f, 1080.0f, m6);
            check(guard.count() == 0, "advanceTo+matrix: zero allocations");
        }
    }

    // ------------------------------------------------------------------
    // C. AmbientGlowShader
    // ------------------------------------------------------------------
    std::printf("  [agsl] shader source + uniform tables\n");
    {
        const char* src = streamify::agsl::ambientGlowShaderSource();
        check(src != nullptr && src[0] != '\0', "shader source non-empty");
        check(std::strstr(src, "half4 main(float2 fragCoord)") != nullptr,
              "entry point present");
        check(std::strstr(src, "uniform float2 uResolution;") != nullptr &&
                  std::strstr(src, "uniform float3 uColorPrimary;") !=
                      nullptr,
              "uniform declarations present");
        check(std::strstr(src, "uLagPhase") != nullptr &&
                  std::strstr(src, "uCrossfade") != nullptr,
              "crossfade plumbing present");
        // Balanced braces (cheap structural sanity; 5 function bodies).
        int open = 0, close = 0;
        for (const char* p = src; *p; ++p) {
            if (*p == '{') ++open;
            if (*p == '}') ++close;
        }
        check(open == close && open >= 5, "braces balanced");
        // Uniform tables: names unique, arities sum to the packing size.
        int aritySum = 0;
        for (int i = 0; i < streamify::agsl::kUniformCount; ++i) {
            check(streamify::agsl::kUniformNames[i] != nullptr &&
                      streamify::agsl::kUniformNames[i][0] == 'u',
                  "uniform name starts with u");
            for (int j = i + 1; j < streamify::agsl::kUniformCount; ++j) {
                check(std::strcmp(streamify::agsl::kUniformNames[i],
                                  streamify::agsl::kUniformNames[j]) != 0,
                      "uniform names unique");
            }
            aritySum += streamify::agsl::kUniformArities[i];
        }
        check(aritySum == GlowShaderParams::kFloatCount,
              "arity sum == param float count");
        check(GlowShaderParams::kFloatCount == 21, "packing size frozen at 21");
    }

    std::printf("  [agsl] runtime param generation\n");
    {
        AmbientGlowConfig cfg;  // defaults, 8 s loop
        GlowShaderParams p0{}, p8{}, pHostile{};
        AmbientGlowRuntime::computeFrameParams(0, 1080, 2400, cfg, &p0);
        AmbientGlowRuntime::computeFrameParams(8000, 1080, 2400, cfg, &p8);
        check(std::memcmp(&p0, &p8, sizeof(p0)) == 0,
              "params bit-exact across the seam");
        check(p0.loopPhase == 0.0f && p0.crossfade == 0.0f,
              "params at t=0 rest state");
        // Hostile config: NaN everywhere the fuzz can reach it.
        AmbientGlowConfig bad = cfg;
        bad.periodSec = NAN;
        bad.blendSec = INFINITY;
        bad.glowIntensity = NAN;
        bad.noiseScale = -INFINITY;
        bad.colorPrimary[0] = NAN;
        bad.colorSecondary[1] = INFINITY;
        AmbientGlowRuntime::computeFrameParams(123456, 0, -5, bad, &pHostile);
        const float* f = reinterpret_cast<const float*>(&pHostile);
        bool allFinite = true;
        for (int i = 0; i < GlowShaderParams::kFloatCount; ++i) {
            if (!std::isfinite(f[i])) allFinite = false;
        }
        check(allFinite, "hostile config/time -> finite params");
        check(pHostile.resolution[0] >= 1.0f && pHostile.resolution[1] >= 1.0f,
              "degenerate surface stays >= 1x1");
        // Config float round-trip.
        float packed[streamify::agsl::kConfigFloatCount];
        AmbientGlowRuntime::configToFloats(cfg, packed);
        AmbientGlowConfig round;
        AmbientGlowRuntime::floatsToConfig(packed, &round);
        check(round.periodSec == cfg.periodSec &&
                  round.blendSec == cfg.blendSec &&
                  round.glowIntensity == cfg.glowIntensity,
              "config float round-trip");
        // Zero-allocation hot path.
        {
            streamify_test::AllocGuard guard;
            GlowShaderParams tmp{};
            AmbientGlowRuntime::computeFrameParams(1234, 1080, 2400, cfg,
                                                   &tmp);
            check(guard.count() == 0, "computeFrameParams: zero allocations");
        }
        // Mid-loop phase drives the crossfade window (t = 7.5 s of 8 s).
        GlowShaderParams pLate{};
        AmbientGlowRuntime::computeFrameParams(7500, 100, 100, cfg, &pLate);
        check(pLate.loopPhase > 0.9f && pLate.crossfade > 0.0f,
              "late-loop frame engages the crossfade");
        checkNear(pLate.lagPhase, pLate.loopPhase - 1.0f, 1e-6f,
                  "lag phase trails by one loop");
    }

    std::printf("  [agsl] fuzz-regression: hostile time/period quotients\n");
    {
        // Regression 1 (soak trap, section 14): t/period overflowing to
        // +Inf made frac01(Inf) = Inf - floor(Inf) = NaN, poisoning phase
        // and every derived field. Engine must map the unknowable phase to
        // a deterministic in-range value instead.
        CanvasLoopConfig degenerate;
        degenerate.strategy = LoopStrategy::kWraparoundCrossfade;
        degenerate.periodSec = 1.0e-30f;   // denormal-ish period
        CanvasLoopFrame f{};
        CanvasLoopMath::computeFrame(1.0e27f, degenerate, 0.012f, 0.035f,
                                     0.015f, 1.0f, &f);
        check(std::isfinite(f.phase) && f.phase >= 0.0f && f.phase < 1.0f,
              "wraparound: Inf quotient stays finite in [0,1)");
        check(std::isfinite(f.scale) && std::isfinite(f.rotationRad) &&
                  std::isfinite(f.translateX) && std::isfinite(f.translateY) &&
                  std::isfinite(f.glowPulse) && std::isfinite(f.hueDriftRad),
              "wraparound: Inf quotient leaves all fields finite");
        const float pp = CanvasLoopMath::pingPongPhase(1.0e27f, 1.0e-30f);
        check(std::isfinite(pp) && pp >= 0.0f && pp <= 1.0f,
              "ping-pong: Inf quotient stays finite in [0,1]");
        // Negative-side overflow too (negative huge t).
        CanvasLoopMath::computeFrame(-1.0e27f, degenerate, 0.012f, 0.035f,
                                     0.015f, 1.0f, &f);
        check(std::isfinite(f.phase) && f.phase >= 0.0f && f.phase < 1.0f,
              "wraparound: -Inf quotient stays finite in [0,1)");

        // Regression 2 (harness-bounds audit): the ping-pong triangle
        // legitimately peaks at EXACTLY 1.0f at the turnaround
        // (t/period == 0.5) — the documented contract, not a defect.
        check(CanvasLoopMath::pingPongPhase(4.0f, 8.0f) == 1.0f,
              "ping-pong: triangle peak is exactly 1.0 at the turnaround");
        check(CanvasLoopMath::pingPongPhase(0.0f, 8.0f) == 0.0f &&
                  CanvasLoopMath::pingPongPhase(8.0f, 8.0f) == 0.0f,
              "ping-pong: seam rests at exactly 0.0");

        // Regression 3: AGSL config path with a NaN strategy float —
        // lround(NaN) must never be reached (FE_INVALID); the strategy
        // falls back to wraparound and everything stays sanitized.
        float hostile16[streamify::agsl::kConfigFloatCount];
        for (float& v : hostile16) {
            v = NAN;
        }
        hostile16[0] = INFINITY;
        AmbientGlowConfig fromFloats;
        AmbientGlowRuntime::floatsToConfig(hostile16, &fromFloats);
        check(fromFloats.strategy == LoopStrategy::kWraparoundCrossfade,
              "floatsToConfig: NaN/Inf strategy falls back to wraparound");
        check(std::isfinite(fromFloats.periodSec) &&
                  fromFloats.periodSec > 0.0f,
              "floatsToConfig: hostile period sanitized");

        // Regression 4 (renderer hardening): seek() with a huge period
        // must not overflow the int64 anchor cast (float-cast-overflow UB).
        CanvasLoopRenderer renderer;
        CanvasLoopConfig huge;
        huge.strategy = LoopStrategy::kPingPong;
        huge.periodSec = 1.0e30f;  // Inf passes a naive > 0 test
        CanvasLoopMotion motion{};
        renderer.reset(1000, huge, motion);
        renderer.seek(2000, 0.5f);  // would cast 0.5*1e30*1000 to int64
        CanvasLoopFrame afterSeek{};
        renderer.advanceTo(3000, &afterSeek);
        check(std::isfinite(afterSeek.phase) && afterSeek.phase >= 0.0f &&
                  afterSeek.phase <= 1.0f,
              "renderer: seek with huge period stays finite (no UB)");
    }

    std::printf("[phase3] canvas/AGSL: %d passed, %d failed\n", g_passed,
                g_failed);
    return g_failed == 0 ? 0 : 1;
}
