#ifndef STREAMIFY_AMBIENT_GLOW_SHADER_H
#define STREAMIFY_AMBIENT_GLOW_SHADER_H
// ============================================================================
//  AmbientGlowShader.h — AGSL ambient-glow RuntimeShader for the Now-Playing
//  Canvas background (Phase 3, BEHIND.md #45)
// ============================================================================
//
//  Three artifacts in one module:
//
//  1. ambientGlowShaderSource() — the AGSL (SKSL) program, ready for
//     RuntimeShader(sources). It renders a breathing dual-radial ambient
//     glow tinted by the palette colors, with a domain-warped fbm noise
//     layer, an 8-second hue drift, vignette and dither grain. Every
//     animated term is either (a) a pure sin/cos of 2*pi*loopPhase (exactly
//     periodic by identity) or (b) a linear scroll layer blended against its
//     lagged copy with the CPU-side boundary crossfade weights (seamless by
//     the CanvasLoopMath construction).
//
//  2. Uniform tables — kUniformNames/kUniformArities describe the flat
//     float packing of GlowShaderParams so Kotlin can bind uniform-by-name
//     with the right arity (float2/float3 vs float).
//
//  3. AmbientGlowRuntime — the zero-allocation per-frame parameter
//     generator: (monotonicMs, surface size, config) -> GlowShaderParams.
//     It calls CanvasLoopMath internally; all outputs stay finite for ANY
//     input (NaN/Inf hostile-time safe), and params(t=0) == params(t=T)
//     bit-exactly.
//
//  Palette hookup: colorPrimary/colorSecondary/colorAccent are meant to be
//  fed from native/palette (BitmapPaletteExtractor) — the ambient glow then
//  matches the album art automatically. The colors are sRGB floats [0,1].
//
//  Zero allocation: everything is static functions over PODs.
// ============================================================================

#include "../video/CanvasLoopMath.h"

#include <cstdint>

namespace streamify::agsl {

// Look + loop tunables. Plain floats; JNI-packable as 16 floats (see
// kConfigFloatCount and AmbientGlowRuntime::configToFloats).
struct AmbientGlowConfig {
    // Loop
    video::LoopStrategy strategy = video::LoopStrategy::kWraparoundCrossfade;
    float periodSec = 8.0f;
    float blendSec = 1.0f;
    // Motion amplitudes (CanvasLoopMath inputs)
    float scaleAmp = 0.012f;
    float rotateAmpRad = 0.035f;
    float translateAmp = 0.015f;
    float hueTurns = 1.0f;
    // Look
    float glowIntensity = 1.0f;   // master multiplier, ~[0.25, 1.5]
    float breath = 0.5f;          // breathing depth [0,1]
    float noiseScale = 2.2f;      // fbm feature size
    float noiseAmount = 0.055f;   // chroma wobble
    float grain = 0.012f;         // dither amplitude (anti-banding)
    float vignette = 0.45f;       // edge falloff
    // Palette (sRGB [0,1])
    float colorPrimary[3] = {0.55f, 0.16f, 0.42f};
    float colorSecondary[3] = {0.11f, 0.09f, 0.24f};
    float colorAccent[3] = {0.95f, 0.55f, 0.28f};
};

// Flat uniform packing — order matches kUniformNames/kUniformArities.
// kFloatCount floats total; trivially copyable.
struct GlowShaderParams {
    float resolution[2];
    float loopPhase;
    float crossfade;
    float lagPhase;
    float intensity;
    float breath;
    float hueDriftRad;
    float colorPrimary[3];
    float colorSecondary[3];
    float colorAccent[3];
    float noiseScale;
    float noiseAmount;
    float grain;
    float vignette;

    // 2+1+1+1+1+1+1+3+3+3+1+1+1+1 == 21 floats, matching the 14 uniforms.
    static constexpr int kFloatCount = 21;
};

// The AGSL program text (null-terminated).
const char* ambientGlowShaderSource();
// strlen of the shader source (JNI jstring creation without strlen trips).
int32_t ambientGlowShaderSourceLength();

// Uniform description tables (index-aligned with GlowShaderParams packing:
// 14 uniforms, 21 floats total).
constexpr int kUniformCount = 14;
extern const char* const kUniformNames[kUniformCount];  // "uResolution", ...
extern const int32_t kUniformArities[kUniformCount];    // 2,1,1,1,1,1,1,3,3,3,1,1,1,1

// Config <-> flat float packing (JNI transport). 16 floats:
// [strategy, period, blend, scaleAmp, rotateAmp, translateAmp, hueTurns,
//  intensity, breath, noiseScale, noiseAmount, grain, vignette,
//  r,g,b of colorPrimary]  (secondary/accent keep defaults; full palette is
//  set through the dedicated color setters in Kotlin).
static_assert(sizeof(AmbientGlowConfig) >= 16 * sizeof(float), "pack");
constexpr int kConfigFloatCount = 16;

class AmbientGlowRuntime {
public:
    // Per-frame uniform generator. Never allocates; clamps/neutralizes any
    // non-finite config or time field so hostile inputs cannot poison the
    // GPU uniforms. surfaceW/H <= 0 yields 1x1 (shader-side degenerate but
    // finite). Periodicity: bit-exact at t == t + period (wrapped phase).
    static void computeFrameParams(int64_t monotonicMs, int32_t surfaceW,
                                   int32_t surfaceH,
                                   const AmbientGlowConfig& cfg,
                                   GlowShaderParams* out);

    // Sanitize a config in place (NaN/Inf -> defaults, clamped ranges).
    static void sanitizeConfig(AmbientGlowConfig* cfg);

    // Flat float packing for the JNI layer (sanitizeConfig applied first).
    static void configToFloats(const AmbientGlowConfig& cfg,
                               float out[kConfigFloatCount]);
    static void floatsToConfig(const float in[kConfigFloatCount],
                               AmbientGlowConfig* cfg);
};

}  // namespace streamify::agsl

#endif  // STREAMIFY_AMBIENT_GLOW_SHADER_H
