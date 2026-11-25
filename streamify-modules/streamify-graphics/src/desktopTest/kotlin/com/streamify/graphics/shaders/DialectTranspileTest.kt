package com.streamify.graphics.shaders

import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Color4f
import org.jetbrains.skia.FilterTileMode
import org.jetbrains.skia.Gradient
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.RuntimeEffect
import org.jetbrains.skia.RuntimeShaderBuilder
import org.jetbrains.skia.Shader
import org.jetbrains.skia.Surface
import com.streamify.graphics.tokens.PrimitiveTokens
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Phase 1, Track A — dialect transpilation gate for the canonical
 * `LiquidGlassCore.sksl` (docs/gpu/ARCHITECTURE.md §2, docs/gpu/PHASED_PLAN.md §3).
 *
 * The single canonical SkSL source must compile through Skia's
 * [RuntimeEffect.makeForShader] on the Desktop JVM lane (skikoCommonMain
 * adapter path), every declared uniform must bind through
 * [RuntimeShaderBuilder] without parser errors, and coordinate evaluation
 * must produce spatially varying output on a headless raster surface.
 * The small dialect deltas (AGSL `RuntimeShader#setInputBuffer` vs Skia
 * `RuntimeShaderBuilder#child`) live in the platform ShaderProvider adapters
 * of Phase 2 — this suite pins the shared source they both consume.
 */
class DialectTranspileTest {

    // -------------------------------------------------------------------------
    // 1. Compilation — the canonical source transpiles into a Skia runtime
    //    effect without a single parser or type-checker error. A malformed
    //    shader makes makeForShader throw (skiko 0.150.x raises
    //    RuntimeException carrying the SkSL error text; the Android AGSL
    //    lane surfaces IllegalArgumentException — both are handled by the
    //    Phase 2 adapters' failure fallback), so reaching a live native
    //    handle is the green path.
    // -------------------------------------------------------------------------
    @Test
    fun liquidGlassCoreCompilesIntoSkiaRuntimeEffect() {
        // A returned live effect IS the compile-success signal: the native
        // layer throws on any parse/type/layout failure instead of returning
        // an error stub.
        val effect = RuntimeEffect.makeForShader(loadCanonicalShader())

        // Exercising the effect through a builder (below) would also compile
        // it; this test pins the bare compilation contract on its own so a
        // uniform-binding regression cannot mask a transpilation regression.
        RuntimeShaderBuilder(effect)
    }

    // Negative control: the gate really does fail on invalid SkSL, so the
    // positive case above cannot pass vacuously.
    @Test
    fun invalidSkslFailsCompilationInsteadOfPassingThrough() {
        assertFailsWith<RuntimeException> {
            RuntimeEffect.makeForShader("uniform float broken; half4 main(float2 c) { return oops; }")
        }
    }

    // -------------------------------------------------------------------------
    // 2. Uniform & child bindings — all nine uniforms declared by the S24
    //    shader (backdrop, resolution, lensRadius, refractionIndex,
    //    chromaticSpread, tintColor, vibrancy, darkenAmount,
    //    specularIntensity) accept values with no parser or layout errors,
    //    and the `backdrop` child shader binds through the Skia dialect's
    //    child("backdrop", ...) seam.
    // -------------------------------------------------------------------------
    @Test
    fun uniformsAndBackdropChildBindWithoutParserErrors() {
        val effect = RuntimeEffect.makeForShader(loadCanonicalShader())
        val builder = RuntimeShaderBuilder(effect)

        builder.uniform("resolution", WIDTH.toFloat(), HEIGHT.toFloat())
        builder.uniform("lensRadius", LENS_RADIUS)
        builder.uniform("refractionIndex", REFRACTIVE_INDEX)
        builder.uniform("chromaticSpread", CHROMATIC_SPREAD_TIER1)
        builder.uniform("tintColor", TINT_R, TINT_G, TINT_B, TINT_A)
        builder.uniform("vibrancy", PrimitiveTokens.vibrancySaturation)
        builder.uniform("darkenAmount", DARKEN_AMOUNT)
        builder.uniform("specularIntensity", PrimitiveTokens.directionalSpecularIntensity)
        builder.child("backdrop", backdropGradient())

        // makeShader() runs the final uniform-layout validation pass.
        builder.makeShader()
    }

    // -------------------------------------------------------------------------
    // 3. Coordinate evaluation — drawn onto a headless raster surface, the
    //    effect must produce spatially varying output: the lens interior
    //    samples the backdrop nearly undisplaced, the curved rim (the
    //    `d > 0.5 - lensRadius` branch) refracts it, and the Schlick Fresnel
    //    term adds a specular rim that does not exist at the center.
    // -------------------------------------------------------------------------
    @Test
    fun coordinateEvaluationProducesSpatiallyVaryingOutput() {
        val pixels = renderTier1LiquidGlass()

        val center = pixels.getColor(WIDTH / 2, HEIGHT / 2)
        val rim = pixels.getColor(RIM_X, RIM_Y)
        val quarter = pixels.getColor(WIDTH / 4, HEIGHT / 2)

        assertTrue(
            center != rim,
            "center and rim pixels are identical — edge-falloff normal math is not evaluating",
        )
        assertTrue(
            center != quarter,
            "center and quarter pixels are identical — refraction offset is not sampling the backdrop gradient",
        )
        assertTrue(
            (center ushr 24) != 0,
            "alpha channel collapsed to zero — backdrop alpha sampling is broken",
        )
    }

    // -------------------------------------------------------------------------
    // 4. Tier toggle — `chromaticSpread = 0.0` is the Tier 2 (Balanced)
    //    single-tap configuration: the dispersion delta collapses the three
    //    backdrop taps onto the same coordinate, and the uniform must rebind
    //    without errors on a live builder.
    // -------------------------------------------------------------------------
    @Test
    fun tier2SingleTapToggleCollapsesDispersionWithoutRebindErrors() {
        val effect = RuntimeEffect.makeForShader(loadCanonicalShader())
        val builder = RuntimeShaderBuilder(effect)

        builder.uniform("resolution", WIDTH.toFloat(), HEIGHT.toFloat())
        builder.uniform("lensRadius", LENS_RADIUS)
        builder.uniform("refractionIndex", REFRACTIVE_INDEX)
        builder.uniform("chromaticSpread", 0.0f)
        builder.uniform("tintColor", TINT_R, TINT_G, TINT_B, TINT_A)
        builder.uniform("vibrancy", PrimitiveTokens.vibrancySaturation)
        builder.uniform("darkenAmount", DARKEN_AMOUNT)
        builder.uniform("specularIntensity", PrimitiveTokens.directionalSpecularIntensity)
        builder.child("backdrop", backdropGradient())

        val surface = Surface.makeRasterN32Premul(WIDTH, HEIGHT)
        val paint = Paint().apply { shader = builder.makeShader() }
        surface.canvas.drawRect(Rect.makeWH(WIDTH.toFloat(), HEIGHT.toFloat()), paint)
        val pixels = Bitmap.makeFromImage(surface.makeImageSnapshot())

        // The single-tap render is still coordinate-varying.
        val center = pixels.getColor(WIDTH / 2, HEIGHT / 2)
        val rim = pixels.getColor(RIM_X, RIM_Y)
        assertTrue(center != rim, "Tier 2 single-tap output lost its spatial variation")
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /**
     * Loads the canonical shader source from the module resources.
     *
     * Primary path is the test classloader (the commonMain resource ships on
     * every platform target); the file fallback covers hosts where the KGP
     * target has not merged common resources into the test runtime — the
     * Gradle Test task runs with the module directory as its working
     * directory, so the source-set relative path is stable.
     */
    private fun loadCanonicalShader(): String {
        val resource = javaClass.classLoader.getResource(SHADER_RESOURCE)
        if (resource != null) return resource.readText()

        val fallback = java.io.File("src/commonMain/resources/$SHADER_RESOURCE")
        check(fallback.isFile) {
            "$SHADER_RESOURCE not found on the classpath or at ${fallback.absolutePath}"
        }
        return fallback.readText()
    }

    /** Renders the Tier 1 (3-tap dispersion) configuration to a pixel buffer. */
    private fun renderTier1LiquidGlass(): Bitmap {
        val effect = RuntimeEffect.makeForShader(loadCanonicalShader())
        val builder = RuntimeShaderBuilder(effect)

        builder.uniform("resolution", WIDTH.toFloat(), HEIGHT.toFloat())
        builder.uniform("lensRadius", LENS_RADIUS)
        builder.uniform("refractionIndex", REFRACTIVE_INDEX)
        builder.uniform("chromaticSpread", CHROMATIC_SPREAD_TIER1)
        builder.uniform("tintColor", TINT_R, TINT_G, TINT_B, TINT_A)
        builder.uniform("vibrancy", PrimitiveTokens.vibrancySaturation)
        builder.uniform("darkenAmount", DARKEN_AMOUNT)
        builder.uniform("specularIntensity", PrimitiveTokens.directionalSpecularIntensity)
        builder.child("backdrop", backdropGradient())

        val surface = Surface.makeRasterN32Premul(WIDTH, HEIGHT)
        val paint = Paint().apply { shader = builder.makeShader() }
        surface.canvas.drawRect(Rect.makeWH(WIDTH.toFloat(), HEIGHT.toFloat()), paint)
        return Bitmap.makeFromImage(surface.makeImageSnapshot())
    }

    /**
     * A horizontal gradient backdrop: sampling coordinates map to distinct
     * colors, so any refraction offset shows up as a visible pixel delta.
     */
    private fun backdropGradient(): Shader =
        Shader.makeLinearGradient(
            0f,
            0f,
            WIDTH.toFloat(),
            0f,
            Gradient(
                Gradient.Colors(
                    arrayOf(
                        Color4f(0.05f, 0.25f, 0.15f, 1.0f),
                        Color4f(0.55f, 0.15f, 0.35f, 1.0f),
                    ),
                    null,
                    FilterTileMode.CLAMP,
                ),
            ),
        )

    private companion object {
        private const val SHADER_RESOURCE = "shaders/LiquidGlassCore.sksl"

        private const val WIDTH = 256
        private const val HEIGHT = 256

        /** Curvature boundary: the rim branch engages at d > 0.5 - 0.12 = 0.38. */
        private const val LENS_RADIUS = 0.12f
        private const val REFRACTIVE_INDEX = 1.25f
        private const val CHROMATIC_SPREAD_TIER1 = 0.02f

        private const val TINT_R = 0.95f
        private const val TINT_G = 0.97f
        private const val TINT_B = 1.00f
        private const val TINT_A = 0.08f

        /** Deterministic mid-ramp scrim for the binding gates. */
        private const val DARKEN_AMOUNT = 0.25f

        /** Pixel roughly on the diagonal rim, well past the 0.38 falloff. */
        private const val RIM_X = 12
        private const val RIM_Y = 12
    }
}
