package com.streamify.graphics.engine.adapter

/**
 * Machine-consumed form of the canonical `LiquidGlassCore` shader
 * (src/commonMain/resources/shaders/LiquidGlassCore.sksl, docs/gpu/
 * ARCHITECTURE.md section 2).
 *
 * The `.sksl` file is the canonical, human-reviewed source of truth; this
 * constant is what the platform adapters actually feed to AGSL
 * `RuntimeShader` (Android) and Skia `RuntimeEffect.makeForShader`
 * (desktop / wasmJs). The two are welded byte-for-byte by
 * `desktopTest/.../adapter/AgslDialectEquivalenceTest.kt`, which loads the
 * resource and fails CI on any drift in either direction.
 *
 * Rationale for embedding rather than runtime resource loading: the shader
 * is code, identical on every target, and resource packaging differs per
 * platform (classpath on JVM, assets dance on Android, bundled resources on
 * wasmJs). A compile-time constant keeps the adapters I/O-free and makes
 * the welding test the single enforcement point.
 */
internal object LiquidGlassSource {

    val SKSL: String = """
// LiquidGlassCore — canonical Sovereign GPU Engine refraction shader.
//
// Single source of truth: docs/gpu/ARCHITECTURE.md §2. The dialect
// variances between AGSL (Android, setInputBuffer) and SkSL (Skia,
// child("backdrop", ...)) are resolved inside the platform ShaderProvider
// adapters, never by editing this file.
//
// Tier semantics of `chromaticSpread`:
//   Tier 1 (Premium)      > 0.0  → 3-tap chromatic dispersion (R/G/B offsets)
//   Tier 2 (Balanced)     0.0    → single-tap refraction, dispersion collapses
//   Tier 3/4 (Legacy/MD)  shader bypassed entirely (cached acrylic scrim)
uniform shader backdrop;          // Captured, pre-blurred backdrop layer
uniform float2 resolution;
uniform float  lensRadius;        // Edge curvature boundary
uniform float  refractionIndex;   // IOR (e.g. 1.25)
uniform float  chromaticSpread;   // Dispersion delta (Tier 1 only)
uniform half4  tintColor;

half4 main(float2 coord) {
    float2 uv = coord / resolution;
    float2 p  = uv - 0.5;
    float  d  = length(p);
    float3 n  = float3(0.0, 0.0, 1.0);

    if (d > 0.5 - lensRadius) {
        float e = (d - (0.5 - lensRadius)) / lensRadius;
        n = normalize(float3(p * e, 1.0 - e));
    }

    float2 off = n.xy * (refractionIndex - 1.0) * 0.05 * resolution;

    // Tier 1: 3-tap chromatic dispersion
    // Tier 2: Single-tap refraction (chromaticSpread set to 0.0)
    half3 c = half3(
        backdrop.eval(coord + off * (1.0 + chromaticSpread)).r,
        backdrop.eval(coord + off).g,
        backdrop.eval(coord + off * (1.0 - chromaticSpread)).b
    );
    half a = backdrop.eval(coord + off).a;

    // Schlick Fresnel approximation
    float fr = pow(1.0 - max(n.z, 0.0), 3.0);
    return mix(half4(c, a), tintColor, tintColor.a) + half4(1.0) * fr * 0.35;
}
""".trimIndent()
}
