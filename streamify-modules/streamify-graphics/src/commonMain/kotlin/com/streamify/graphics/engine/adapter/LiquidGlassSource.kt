package com.streamify.graphics.engine.adapter

/**
 * Shared in-memory representation of `LiquidGlassCore.sksl` for multiplatform
 * targets that embed shaders as compile-time string constants.
 *
 * Byte-for-byte mirrors the canonical resource file at
 * `streamify-modules/streamify-graphics/src/commonMain/resources/shaders/LiquidGlassCore.sksl`.
 *
 * Cross-platform consistency between this source string and the file on disk
 * is pinned by `DialectTranspileTest.canonicalSkslMatchesSourceResource()`.
 */
internal object LiquidGlassSource {

    val SKSL: String = """
// Streamify Sovereign Graphics — LiquidGlassCore
// Canonical SkSL surface shader (Phase 1, Track B)
//
// Dialect notes: this file is canonical SkSL. AGSL variances (e.g.
// uniform half4 promotion) and Skia runtime variances (e.g.
// child("backdrop", ...)) are resolved inside the platform ShaderProvider
// adapters, never by editing this file.
//
// Precision contract (Track A, docs/gpu/ARCHITECTURE.md §2a):
//   float (highp) — the normal vector and the geometry feeding it (uv, p,
//     d, e, off, and the Schlick exponent): 32-bit mantissas keep
//     edge-curved normals smooth and the refraction offset sub-pixel
//     accurate at any panel density. On a 2560px panel a mediump uv would
//     quantize to ~1.25px steps — visible banding — so the geometric
//     chain stays highp end to end.
//   half (mediump) — every color-path value: the sampled backdrop
//     channels (c, a), the Fresnel scalar fr once computed, the tint mix
//     and the specular add. 8-bit output depth cannot resolve a 10-bit
//     mantissa, so the downgrade is visually free; on unified-ALU mobile
//     parts (Adreno 5xx, Mali Midgard/Bifrost) half-typed color math runs
//     at 2x the highp ALU rate, and on modern GPUs it costs nothing.
//   The highp -> mediump boundary is the `half fr =` declaration: the
//   Schlick exponent is evaluated on the proven float overload (one
//   highp scalar op) and stored onto the half path, after which every
//   arithmetic op in the composite is half-typed.
//
// Kyant optical parity (S24): this shader implements the five optical
// pillars of the io.github.kyant0:backdrop catalog look —
//   1. Directional 45° specular rim: light from the top-left
//      (L = normalize(float2(-1.0, -1.0))), cosine falloff on the lit
//      boundary, subtle ambient rim on the opposite side.
//   2. Vibrancy: luminance-preserving saturation matrix (1.5x) applied to
//      the sampled backdrop BEFORE the tint mix, so the glass never washes
//      out into milky white fog.
//   3. Edge-only curvature: refraction engages strictly inside the
//      lensRadius band along the boundary; the flat core keeps typography
//      and icons perfectly crisp.
//   4. Adaptive luminance scrim: the "đục đen" ramp — the platform
//      adapters evaluate lerp(0.12, 0.50, clamp((luminance - 0.3) / 0.5))
//      on the CPU and bind the result as `darkenAmount`.
//   5. Fluid spring & touch-tracking glow: press swell (1.12x, spring
//      damping 0.5 / stiffness 300) and the radial Plus-blend glow live in
//      the Modifier.streamifyGlass facade, not in this shader.
//
// Tier semantics of `chromaticSpread`:
//   Tier 1 (Premium)      > 0.0  → 3-tap chromatic dispersion (R/G/B offsets)
//   Tier 2 (Balanced)    == 0.0  → single-tap refraction (offsets collapse)
//   Tier 3 (Constrained)         → fallback acrylic/tint path (bypasses shader)

uniform shader backdrop;
uniform float2 resolution;        // Surface dimensions in physical pixels
uniform float  lensRadius;        // Edge refraction band thickness
uniform float  refractionIndex;   // IOR (e.g. 1.25)
uniform float  chromaticSpread;   // Dispersion delta (Tier 1 only)
uniform half4  tintColor;
uniform float  vibrancy;          // Luminance-preserving saturation multiplier (Kyant parity: 1.5)
uniform float  darkenAmount;      // Adaptive luminance scrim, 0..1 ("đục đen" ramp)
uniform float  specularIntensity; // Directional 45° rim strength (Kyant parity: 0.35)

half4 main(float2 coord) {
    // Geometry: highp — normals and tap offsets must stay sub-pixel.
    float2 uv = coord / resolution;
    float2 p  = uv - 0.5;
    float  d  = length(p);

    // Edge-normal calculation with smooth falloff toward center.
    // Lens curvature band extends `lensRadius` inward from the boundary.
    float  e = smoothstep(0.5 - lensRadius, 0.5, d);
    float3 n  = float3(0.0, 0.0, 1.0);
    if (d > 0.0) {
        float2 radialDir = p / d;
        n = normalize(float3(radialDir * e, 1.0 - e));
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

    // Schlick Fresnel approximation — the highp -> mediump boundary.
    half fr = half(pow(1.0 - max(n.z, 0.0), 3.0));

    // Pillar 2 — vibrancy: the Kyant colorControls saturation pass, applied
    // before the tint mix. c' = luma + (c - luma) * vibrancy preserves
    // luminance while deepening chroma, so bright artwork reads through the
    // glass instead of collapsing into milky white fog.
    half  luma = dot(c, half3(0.2126, 0.7152, 0.0722));
    half3 vibrant = clamp(mix(half3(luma), c, half3(vibrancy)), half3(0.0), half3(1.0));

    // Pillar 1 — directional 45° specular rim. Light travels from the
    // top-left: the boundary whose outward radial direction faces the light
    // receives the full highlight with a cosine falloff (Kyant
    // HighlightStyle.Default: angle 45°, falloff 1.0), while the opposite
    // boundary keeps a subtle ambient rim (25% of the lit term). The Schlick
    // Fresnel term stays as the grazing-angle envelope, so the rim still
    // peaks exactly at the edge and never bleeds into the flat core.
    float2 lightDir = normalize(float2(-1.0, -1.0));
    float2 radial = p / max(d, 1e-6);
    float  lit = clamp(dot(radial, lightDir), 0.0, 1.0);
    half   rim = fr * half(lit + 0.25 * (1.0 - lit)) * half(specularIntensity);

    half3 composite = mix(vibrant, tintColor.rgb, tintColor.a);

    // Pillar 4 — adaptive luminance scrim: the "đục đen" darkening ramp,
    // bound by the platform adapters from the backdrop luminance. Applied
    // after the tint and the rim exactly like Kyant's onDrawSurface scrim
    // rect, so over bright album art the surface deepens instead of washing
    // out and overlaying typography stays legible.
    half3 darkened = clamp((composite + half3(rim)) * half3(1.0 - darkenAmount), half3(0.0), half3(1.0));
    return half4(darkened, a);
}
""".trimIndent()
}
