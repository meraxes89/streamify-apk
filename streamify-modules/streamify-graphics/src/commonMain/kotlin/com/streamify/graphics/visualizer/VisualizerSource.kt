package com.streamify.graphics.visualizer

/**
 * Machine-consumed form of the canonical `VisualizerShader` shader
 * (src/commonMain/resources/shaders/VisualizerShader.sksl, docs/gpu/
 * ARCHITECTURE.md section 4, docs/gpu/PHASED_PLAN.md Phase 3 Track A).
 *
 * The `.sksl` file is the canonical, human-reviewed source of truth; this
 * constant is what the platform adapters will feed to Skia
 * `RuntimeEffect.makeForShader` (desktop / wasmJs) and — through
 * [AgslDialect][com.streamify.graphics.engine.adapter.AgslDialect]
 * transpilation — to Android's AGSL `RuntimeShader` on API 33+. The two
 * are welded byte-for-byte by
 * `desktopTest/.../visualizer/VisualizerShaderTest.kt`, which loads the
 * resource and fails CI on any drift in either direction — the exact
 * enforcement pattern the Phase 1 `LiquidGlassSource` /
 * `AgslDialectEquivalenceTest` pair established.
 *
 * Rationale for embedding rather than runtime resource loading (same as
 * LiquidGlassSource): the shader is code, identical on every target, and
 * resource packaging differs per platform (classpath on JVM, assets dance
 * on Android, bundled resources on wasmJs). A compile-time constant keeps
 * the adapters I/O-free and makes the weld test the single enforcement
 * point.
 */
internal object VisualizerSource {

    val SKSL: String = """
// VisualizerShader — canonical Sovereign GPU Engine audio visualizer shader.
//
// Single source of truth: docs/gpu/ARCHITECTURE.md §4 and
// docs/gpu/PHASED_PLAN.md Phase 3 (Track A). Waveforms, spectrum bars and
// the glowing aura are synthesized entirely inside the fragment shader by
// sampling the FFT data texture — no geometry, no per-frame uniforms beyond
// the six below, and strictly zero recomposition on the audio render path.
//
// Data-texture contract (staged by visualizer/FftDataTexture.kt):
//   * 1024x1 RGBA_8888, opaque (A = 255), no color space (raw data bytes).
//   * Texel k's R channel carries the normalized magnitude [0, 1] of FFT
//     bin k; G and B stay zero (reserved); x maps linearly to the bin axis.
//   * Child shader coordinates are image pixels — texel k's center sits at
//     (k + 0.5, 0.5). Bind CLAMP/CLAMP with linear filtering so continuous
//     bin-axis reads interpolate neighboring bins (single-channel encoding
//     stays correct under linear filtering).
//
// Style semantics of `style`:
//   0  waveform fluid line  — dual-harmonic carrier, FFT-scaled envelope
//   1  spectrum bars        — 64 bars x 16 bins, accent cap riding the peak
//   2  glowing aura         — bass-weighted radial ring + halo
//
// The dialect variances between AGSL (Android, setInputBuffer /
// setIntUniform) and SkSL (Skia, child("fftTexture", ...)) are resolved
// inside the platform ShaderProvider adapters, never by editing this file.
uniform shader fftTexture;   // Frequency magnitudes along x (R = magnitude)
uniform float2 resolution;   // Drawing surface size in pixels
uniform float  time;         // Seconds since visualizer start (animation phase)
uniform half4  primaryColor; // Body color of the visualization
uniform half4  accentColor;  // Peak / high-energy accent color
uniform int    style;        // 0 = waveform fluid line, 1 = spectrum bars, 2 = glowing aura

// Normalized magnitude at continuous bin coordinate `bin` in [0, 1023].
// +0.5 lands on texel centers; CLAMP protects the 1-texel edges.
float magnitudeAtBin(float bin) {
    return float(fftTexture.eval(float2(bin + 0.5, 0.5)).r);
}

half4 main(float2 fragCoord) {
    float2 uv = fragCoord / resolution;

    if (style == 0) {
        // Style 0 — waveform fluid line. A dual-harmonic carrier snakes
        // across the viewport; the local FFT magnitude scales its envelope,
        // so the line dances with the spectrum without any vertex buffers.
        float mag = magnitudeAtBin(uv.x * 1023.0);
        float envelope = 0.10 + 0.45 * mag;
        float carrier = sin(uv.x * 9.42477796 + time * 2.6) * 0.35
                      + sin(uv.x * 23.24778976 - time * 4.1) * 0.12;
        float lineY = 0.5 + carrier * envelope;
        float d = abs(uv.y - lineY);
        float core = 1.0 - smoothstep(0.0, 0.014, d);  // crisp center line
        float glow = exp(-d * 9.0) * 0.45;             // soft aura falloff
        float4 c = float4(primaryColor) * (core + glow)
                 + float4(accentColor) * glow * mag;
        return half4(c.rgb, 1.0);
    } else if (style == 1) {
        // Style 1 — spectrum bars. 64 bars over 1024 bins (16 bins per
        // bar). Each bar reads its exact center bin ((column + 0.5) * 16
        // is texel-center aligned, no cross-blur), with a 10% inter-bar
        // gutter and a bright accent cap riding the peak height.
        float barCount = 64.0;
        float column = floor(uv.x * barCount);
        float mag = magnitudeAtBin((column + 0.5) * 16.0);
        float inBar = fract(uv.x * barCount);
        float barMask = step(0.10, inBar) * step(inBar, 0.90);
        float barHeight = 0.03 + 0.92 * mag;           // 3% silence floor
        float body = (1.0 - step(barHeight, uv.y)) * barMask;
        float cap = (1.0 - smoothstep(0.0, 0.02, abs(uv.y - barHeight))) * barMask;
        float4 c = mix(float4(primaryColor), float4(accentColor), mag) * (body * 0.80 + cap);
        return half4(c.rgb, 1.0);
    } else {
        // Style 2 — glowing aura. The eight lowest bins (bass) drive the
        // ring radius and intensity; a slow angular shimmer keeps the
        // halo alive even during quiet passages.
        float bass = 0.0;
        for (int i = 0; i < 8; i++) {
            bass += magnitudeAtBin(float(i));
        }
        bass = bass / 8.0;
        float2 p = (uv - 0.5) * float2(resolution.x / max(resolution.y, 1.0), 1.0);
        float r = length(p);
        float radius = 0.16 + 0.20 * bass;
        float dr = (r - radius) * 9.0;
        float ring = exp(-dr * dr) * (0.30 + 0.70 * bass);
        float halo = exp(-r * 3.0) * (0.10 + 0.35 * bass);
        float shimmer = 0.60 + 0.40 * sin(atan(p.y, p.x) * 6.0 + time * 1.9);
        float4 c = float4(primaryColor) * (ring + halo)
                 + float4(accentColor) * ring * shimmer;
        return half4(c.rgb, 1.0);
    }
}
""".trimIndent()
}
