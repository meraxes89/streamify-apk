// ============================================================================
//  AmbientGlowShader.cc — AGSL source + uniform tables + runtime parameter
//  generator (see AmbientGlowShader.h for the contracts).
// ============================================================================

#include "AmbientGlowShader.h"

#include <cmath>
#include <cstring>

#include "../util/Finite.h"

namespace streamify::agsl {

// ---------------------------------------------------------------------------
// 1. The AGSL (SKSL) program.
//
//    Seamless 8-second loop accounting, term by term:
//      * gradient centers c1/c2 — pure cos/sin(2*pi*p): exact-period identity
//      * breathing (breath divisor, intensity pulse) — cos(2*pi*p)
//      * rotation warp offset — cos/sin(2*pi*p)
//      * hue drift — angle 2*pi*turns*p; integer turns are the identity
//        rotation, so the hue at p=0 and p=1 coincides
//      * scroll layer — aperiodic BY DESIGN (linear scroll); the ONLY
//        non-periodic term. Sampled twice (phase, lagPhase) and blended
//        with the CPU crossfade weight, which makes the composite seamless.
//    Grain seeds from fract(loopPhase) so the dither pattern also loops.
// ---------------------------------------------------------------------------
const char* ambientGlowShaderSource() {
    return R"AGSL(
// Streamify ambient glow — Now Playing Canvas background (Phase 3, #45).
// Uniforms are generated per frame by native/agsl/AmbientGlowShader.cc;
// the loop math lives in native/video/CanvasLoopMath.cc.
uniform float2 uResolution;
uniform float  uLoopPhase;      // [0,1) seamless loop phase (CPU-side)
uniform float  uCrossfade;      // boundary crossfade weight (wraparound)
uniform float  uLagPhase;       // loopPhase - 1 (lagged scroll copy)
uniform float  uIntensity;      // master glow multiplier
uniform float  uBreath;         // breathing depth [0,1]
uniform float  uHueDrift;       // hue rotation angle (full turns per loop)
uniform float3 uColorPrimary;   // sRGB [0,1] (palette-driven)
uniform float3 uColorSecondary;
uniform float3 uColorAccent;
uniform float  uNoiseScale;
uniform float  uNoiseAmount;
uniform float  uGrain;
uniform float  uVignette;

const float kTau = 6.28318530718;

float hash21(float2 p) {
    p = fract(p * float2(233.34, 851.73));
    p += dot(p, p + 23.45);
    return fract(p.x * p.y);
}

float valueNoise(float2 p) {
    float2 i = floor(p);
    float2 f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    float a = hash21(i);
    float b = hash21(i + float2(1.0, 0.0));
    float c = hash21(i + float2(0.0, 1.0));
    float d = hash21(i + float2(1.0, 1.0));
    return mix(mix(a, b, f.x), mix(c, d, f.x), f.y);
}

float fbm(float2 p) {
    return 0.64 * valueNoise(p) + 0.36 * valueNoise(p * 2.04 + 19.7);
}

// YIQ hue rotation (composite-video basis; cheap and stable).
float3 hueRotate(float3 col, float angle) {
    float y = dot(col, float3(0.299, 0.587, 0.114));
    float i = dot(col, float3(0.596, -0.274, -0.322));
    float q = dot(col, float3(0.211, -0.523, 0.312));
    float cs = cos(angle);
    float sn = sin(angle);
    float i2 = i * cs - q * sn;
    float q2 = i * sn + q * cs;
    return float3(y + 0.956 * i2 + 0.621 * q2,
                  y - 0.272 * i2 - 0.647 * q2,
                  y - 1.106 * i2 + 1.703 * q2);
}

half4 main(float2 fragCoord) {
    float2 uv = (fragCoord - 0.5 * uResolution) / uResolution.y;
    float th = kTau * uLoopPhase;

    // Drifting gradient centers: exact periodicity (pure sin/cos of th).
    float2 c1 = float2(0.26 * cos(th), 0.15 * sin(th));
    float2 c2 = -float2(0.31 * cos(th + 2.1), 0.18 * sin(2.0 * th));

    // Boundary-crossfaded linear scroll: the only aperiodic layer, made
    // seamless by the CPU-side lagged-copy weights.
    float2 scrollA = float2(3.0 * uLoopPhase, 1.5 * uLoopPhase);
    float2 scrollB = float2(3.0 * uLagPhase, 1.5 * uLagPhase);
    float nScroll = mix(fbm(uv * uNoiseScale + scrollA),
                        fbm(uv * uNoiseScale + scrollB),
                        uCrossfade);

    // Periodic rotation warp + scroll energy blend.
    float2 warp = 0.22 * float2(cos(th), sin(th)) + 0.12 * float2(nScroll);
    float n = fbm(uv * uNoiseScale * 1.7 + warp);

    float breath = 1.0 + uBreath * 0.11 * cos(th);
    float d1 = distance(uv, c1);
    float d2 = distance(uv, c2);
    float g1 = exp(-2.7 * d1 * d1 * breath);
    float g2 = exp(-3.1 * d2 * d2 / breath);

    float3 col = mix(uColorSecondary, uColorPrimary, saturate(g1));
    col = mix(col, uColorAccent, saturate(0.55 * g2 * (0.45 + 0.55 * n)));
    col += uNoiseAmount * (n * 2.0 - 1.0) * (0.30 * g1 + 0.16);
    col = hueRotate(col, uHueDrift);

    float r = length(uv);
    col *= 1.0 - uVignette * smoothstep(0.52, 1.30, r);
    col *= uIntensity * (0.93 + 0.07 * cos(th));
    col += (hash21(fragCoord + fract(uLoopPhase) * 61.7) - 0.5) * uGrain;
    col = max(col, float3(0.0));
    return half4(col, 1.0);
}
)AGSL";
}

int32_t ambientGlowShaderSourceLength() {
    return static_cast<int32_t>(std::strlen(ambientGlowShaderSource()));
}

// ---------------------------------------------------------------------------
// 2. Uniform description tables — index-aligned with the GlowShaderParams
//    field order. The static_asserts make any drift a build error.
// ---------------------------------------------------------------------------
const char* const kUniformNames[kUniformCount] = {
    "uResolution",   "uLoopPhase",     "uCrossfade",     "uLagPhase",
    "uIntensity",    "uBreath",        "uHueDrift",      "uColorPrimary",
    "uColorSecondary", "uColorAccent", "uNoiseScale",    "uNoiseAmount",
    "uGrain",        "uVignette",
};
const int32_t kUniformArities[kUniformCount] = {
    2, 1, 1, 1, 1, 1, 1, 3, 3, 3, 1, 1, 1, 1,
};

namespace {
// Packing is compile-time-locked to the struct layout: 14 uniforms, 21 floats.
constexpr int kExpectedFloats =
    2 + 1 + 1 + 1 + 1 + 1 + 1 + 3 + 3 + 3 + 1 + 1 + 1 + 1;
static_assert(kExpectedFloats == GlowShaderParams::kFloatCount,
              "uniform arity sum must equal GlowShaderParams::kFloatCount");
static_assert(kUniformCount == 14, "uniform table size is frozen");

float clamp01(float v) {
    // Bit-pattern check (audit #22 item 2 follow-up): the prod
    // -ffinite-math-only build folds std::isfinite to `true` here.
    if (!streamify::util::f32_is_finite(v)) return 0.0f;
    return v < 0.0f ? 0.0f : (v > 1.0f ? 1.0f : v);
}
float clampRange(float v, float lo, float hi, float dflt) {
    if (!streamify::util::f32_is_finite(v)) return dflt;
    return v < lo ? lo : (v > hi ? hi : v);
}
}  // namespace

// ---------------------------------------------------------------------------
// 3. Runtime parameter generator.
// ---------------------------------------------------------------------------
void AmbientGlowRuntime::sanitizeConfig(AmbientGlowConfig* cfg) {
    if (cfg == nullptr) {
        return;
    }
    if (cfg->strategy != video::LoopStrategy::kPingPong &&
        cfg->strategy != video::LoopStrategy::kWraparoundCrossfade) {
        cfg->strategy = video::LoopStrategy::kWraparoundCrossfade;
    }
    cfg->periodSec = clampRange(cfg->periodSec, 0.1f, 120.0f, 8.0f);
    cfg->blendSec = clampRange(cfg->blendSec, 0.0f, cfg->periodSec * 0.5f,
                               1.0f);
    cfg->scaleAmp = clampRange(cfg->scaleAmp, 0.0f, 0.25f, 0.012f);
    cfg->rotateAmpRad = clampRange(cfg->rotateAmpRad, 0.0f, 1.0f, 0.035f);
    cfg->translateAmp = clampRange(cfg->translateAmp, 0.0f, 0.25f, 0.015f);
    cfg->hueTurns = clampRange(cfg->hueTurns, 0.0f, 8.0f, 1.0f);
    cfg->glowIntensity = clampRange(cfg->glowIntensity, 0.05f, 4.0f, 1.0f);
    cfg->breath = clampRange(cfg->breath, 0.0f, 1.0f, 0.5f);
    cfg->noiseScale = clampRange(cfg->noiseScale, 0.1f, 16.0f, 2.2f);
    cfg->noiseAmount = clampRange(cfg->noiseAmount, 0.0f, 0.5f, 0.055f);
    cfg->grain = clampRange(cfg->grain, 0.0f, 0.25f, 0.012f);
    cfg->vignette = clampRange(cfg->vignette, 0.0f, 1.0f, 0.45f);
    for (int i = 0; i < 3; ++i) {
        cfg->colorPrimary[static_cast<size_t>(i)] =
            clamp01(cfg->colorPrimary[static_cast<size_t>(i)]);
        cfg->colorSecondary[static_cast<size_t>(i)] =
            clamp01(cfg->colorSecondary[static_cast<size_t>(i)]);
        cfg->colorAccent[static_cast<size_t>(i)] =
            clamp01(cfg->colorAccent[static_cast<size_t>(i)]);
    }
}

void AmbientGlowRuntime::computeFrameParams(int64_t monotonicMs,
                                            int32_t surfaceW, int32_t surfaceH,
                                            const AmbientGlowConfig& cfgIn,
                                            GlowShaderParams* out) {
    if (out == nullptr) {
        return;
    }
    // Neutral-but-valid frame for hostile inputs (never poison the GPU).
    GlowShaderParams neutral{};
    neutral.resolution[0] = 1.0f;
    neutral.resolution[1] = 1.0f;
    neutral.loopPhase = 0.0f;
    neutral.crossfade = 0.0f;
    neutral.lagPhase = 0.0f;
    neutral.intensity = 1.0f;
    neutral.breath = 0.5f;
    neutral.hueDriftRad = 0.0f;
    neutral.noiseScale = 2.2f;
    neutral.noiseAmount = 0.055f;
    neutral.grain = 0.012f;
    neutral.vignette = 0.45f;
    for (int i = 0; i < 3; ++i) {
        neutral.colorPrimary[static_cast<size_t>(i)] = 0.5f;
        neutral.colorSecondary[static_cast<size_t>(i)] = 0.2f;
        neutral.colorAccent[static_cast<size_t>(i)] = 0.8f;
    }

    AmbientGlowConfig cfg = cfgIn;
    sanitizeConfig(&cfg);

    // Surface: degenerate sizes stay finite (shader divides by resolution.y).
    float w = static_cast<float>(surfaceW);
    float h = static_cast<float>(surfaceH);
    if (!(w >= 1.0f)) w = 1.0f;
    if (!(h >= 1.0f)) h = 1.0f;

    // Loop time: raw ms mapped onto loop seconds. Callers normally pass
    // origin-relative ms (CanvasLoopRenderer subtracts its anchor first);
    // the double->float demotion keeps full phase precision for any
    // realistic anchored span, and merely coarsens phase (never NaN/Inf)
    // for absurd absolute spans.
    const double tSec =
        static_cast<double>(monotonicMs) * (1.0 / 1000.0);
    const video::CanvasLoopConfig loopCfg{cfg.strategy, cfg.periodSec,
                                          cfg.blendSec};
    video::CanvasLoopFrame frame{};
    video::CanvasLoopMath::computeFrame(static_cast<float>(tSec), loopCfg,
                                        cfg.scaleAmp, cfg.rotateAmpRad,
                                        cfg.translateAmp, cfg.hueTurns,
                                        &frame);

    out->resolution[0] = w;
    out->resolution[1] = h;
    out->loopPhase = frame.phase;                // [0,1) by construction
    out->crossfade = frame.crossfadeWeight;      // [0,1]
    out->lagPhase = frame.lagPhase;              // [-1,1)
    out->intensity = cfg.glowIntensity;
    out->breath = cfg.breath;
    out->hueDriftRad = frame.hueDriftRad;
    for (int i = 0; i < 3; ++i) {
        out->colorPrimary[static_cast<size_t>(i)] =
            cfg.colorPrimary[static_cast<size_t>(i)];
        out->colorSecondary[static_cast<size_t>(i)] =
            cfg.colorSecondary[static_cast<size_t>(i)];
        out->colorAccent[static_cast<size_t>(i)] =
            cfg.colorAccent[static_cast<size_t>(i)];
    }
    out->noiseScale = cfg.noiseScale;
    out->noiseAmount = cfg.noiseAmount;
    out->grain = cfg.grain;
    out->vignette = cfg.vignette;

    // Final hostile-input sweep: every slot must be finite (defensive — the
    // paths above are already finite; this keeps the contract cheap to
    // prove for the fuzzer). Bit-pattern check (audit #22 item 2 follow-up):
    // std::isfinite folds to `true` in the prod -ffinite-math-only build.
    float* f = reinterpret_cast<float*>(out);
    for (int i = 0; i < GlowShaderParams::kFloatCount; ++i) {
        if (!streamify::util::f32_is_finite(f[static_cast<size_t>(i)])) {
            *out = neutral;
            return;
        }
    }
}

void AmbientGlowRuntime::configToFloats(const AmbientGlowConfig& cfg,
                                        float out[kConfigFloatCount]) {
    if (out == nullptr) {
        return;
    }
    AmbientGlowConfig c = cfg;
    sanitizeConfig(&c);
    out[0] = static_cast<float>(static_cast<int32_t>(c.strategy));
    out[1] = c.periodSec;
    out[2] = c.blendSec;
    out[3] = c.scaleAmp;
    out[4] = c.rotateAmpRad;
    out[5] = c.translateAmp;
    out[6] = c.hueTurns;
    out[7] = c.glowIntensity;
    out[8] = c.breath;
    out[9] = c.noiseScale;
    out[10] = c.noiseAmount;
    out[11] = c.grain;
    out[12] = c.vignette;
    out[13] = c.colorPrimary[0];
    out[14] = c.colorPrimary[1];
    out[15] = c.colorPrimary[2];
}

void AmbientGlowRuntime::floatsToConfig(const float in[kConfigFloatCount],
                                        AmbientGlowConfig* cfg) {
    if (cfg == nullptr || in == nullptr) {
        return;
    }
    AmbientGlowConfig c;
    // Guard lround BEFORE the call: lround(NaN/Inf) raises FE_INVALID
    // (hostile config floats hit this directly via the fuzz surface).
    // Bit-pattern check (audit #22 item 2 follow-up): prod fast-math folds
    // std::isfinite to `true`, sending NaN straight into lround (UB).
    const int32_t strat =
        (streamify::util::f32_is_finite(in[0]) && std::fabs(in[0]) < 16.0f)
            ? static_cast<int32_t>(std::lround(in[0]))
            : -1;
    c.strategy = (strat == static_cast<int32_t>(video::LoopStrategy::kPingPong))
                     ? video::LoopStrategy::kPingPong
                     : video::LoopStrategy::kWraparoundCrossfade;
    c.periodSec = in[1];
    c.blendSec = in[2];
    c.scaleAmp = in[3];
    c.rotateAmpRad = in[4];
    c.translateAmp = in[5];
    c.hueTurns = in[6];
    c.glowIntensity = in[7];
    c.breath = in[8];
    c.noiseScale = in[9];
    c.noiseAmount = in[10];
    c.grain = in[11];
    c.vignette = in[12];
    c.colorPrimary[0] = in[13];
    c.colorPrimary[1] = in[14];
    c.colorPrimary[2] = in[15];
    sanitizeConfig(&c);
    *cfg = c;
}

}  // namespace streamify::agsl
