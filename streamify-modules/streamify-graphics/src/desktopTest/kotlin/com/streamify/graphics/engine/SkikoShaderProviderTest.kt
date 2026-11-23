package com.streamify.graphics.engine

import com.streamify.graphics.governor.RenderTier
import com.streamify.graphics.governor.ShaderCapability
import com.streamify.graphics.tokens.SemanticTokens
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Color4f
import org.jetbrains.skia.FilterTileMode
import org.jetbrains.skia.Gradient
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Shader
import org.jetbrains.skia.Surface

/**
 * Skiko ShaderProvider gate (Phase 2-A Track A; docs/gpu/CI_TESTING_SPEC.md
 * section 2 — the desktop half of the adapter matrix).
 *
 * Runs against the REAL skiko backend on this host (the same lane the
 * Phase 1-A DialectTranspileTest and CI's ubuntu runners exercise):
 *
 *  * **probe** — the capability axis on non-Android targets is a live Skia
 *    `RuntimeEffect` compile of the canonical source; a healthy host
 *    classifies RUNTIME_SHADER with a Tier 1 ceiling.
 *  * **tier mapping** — engineFor covers all four tiers with the ladder's
 *    engines and never throws.
 *  * **clean render** — the production program (`liquidGlassProgram`, the
 *    exact pipeline the engine holds) binds the backdrop child + all five
 *    uniforms and renders to a raster Skia canvas without error, producing
 *    spatially varying, alpha-preserving output in both the Tier 1
 *    (dispersion) and Tier 2 (single-tap) configurations.
 */
class SkikoShaderProviderTest {

    @Test
    fun probeValidatesRuntimeShaderCompilationOnThisHost() {
        val probe = ShaderProvider.probe()
        assertTrue(
            probe.runtimeShaderCompileSupported,
            "the canonical LiquidGlassCore source must compile through " +
                "RuntimeEffect.makeForShader() on a healthy skiko host",
        )
        assertEquals(ShaderCapability.RUNTIME_SHADER, probe.shaderCapability)
        assertEquals(RenderTier.Tier1_Ultra, probe.hardwareCeiling)
    }

    @Test
    fun engineForCoversEveryTierWithoutThrowing() {
        for (tier in RenderTier.entries) {
            assertNotNull(ShaderProvider.engineFor(tier), tier.toString())
        }
    }

    @Test
    fun engineForMapsTiersToTheSkikoLadderEngines() {
        assertEquals(LegacyRedirectGlassEngine, ShaderProvider.engineFor(RenderTier.Tier4_Safe))
        assertEquals(AcrylicScrimGlassEngine, ShaderProvider.engineFor(RenderTier.Tier3_Legacy))
        assertTrue(ShaderProvider.engineFor(RenderTier.Tier1_Ultra) is SkikoRuntimeShaderEngine)
        assertTrue(ShaderProvider.engineFor(RenderTier.Tier2_Balanced) is SkikoRuntimeShaderEngine)
    }

    @Test
    fun liquidGlassProgramRendersCleanlyToTheSkiaCanvasTier1Dispersion() {
        val pixels = renderThroughProgram(CHROMATIC_SPREAD_TIER1)
        assertCleanRender(pixels)
    }

    @Test
    fun liquidGlassProgramRendersCleanlyToTheSkiaCanvasTier2SingleTap() {
        val pixels = renderThroughProgram(CHROMATIC_SPREAD_TIER2)
        assertCleanRender(pixels)
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /**
     * Renders through the exact production pipeline: the provider's live
     * [SkikoLiquidGlassProgram] with the backdrop child shader bound and
     * all five non-child uniforms set from the token layer.
     */
    private fun renderThroughProgram(chromaticSpread: Float): Bitmap {
        val program = assertNotNull(
            liquidGlassProgram,
            "the canonical program failed to compile on this host — the lane " +
                "would degrade to the scrim engine",
        )

        program.bindBackdrop(backdropGradient())
        program.bindUniforms(
            width = WIDTH.toFloat(),
            height = HEIGHT.toFloat(),
            chromaticSpread = chromaticSpread,
            tintColorR = SemanticTokens.surfaceGlassMuted.red,
            tintColorG = SemanticTokens.surfaceGlassMuted.green,
            tintColorB = SemanticTokens.surfaceGlassMuted.blue,
            tintColorA = SemanticTokens.surfaceGlassMuted.alpha,
        )

        val surface = Surface.makeRasterN32Premul(WIDTH, HEIGHT)
        val paint = Paint().apply { shader = program.makeShader() }
        surface.canvas.drawRect(Rect.makeWH(WIDTH.toFloat(), HEIGHT.toFloat()), paint)
        return Bitmap.makeFromImage(surface.makeImageSnapshot())
    }

    /**
     * "Clean" = the render completed, the lens math is spatially varying
     * (rim refraction differs from the undisplaced center) and the alpha
     * channel survived the composite.
     */
    private fun assertCleanRender(pixels: Bitmap) {
        val center = pixels.getColor(WIDTH / 2, HEIGHT / 2)
        val rim = pixels.getColor(RIM_X, RIM_Y)
        assertTrue(center != rim, "center and rim pixels are identical — the lens math is not evaluating")
        assertTrue((center ushr 24) != 0, "alpha collapsed to zero — the composite lost its backdrop")
    }

    /** A horizontal gradient backdrop: distinct colors across x, so the
     * refraction offset shows up as a visible pixel delta. */
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
        private const val WIDTH = 256
        private const val HEIGHT = 256

        private const val CHROMATIC_SPREAD_TIER1 = 0.02f
        private const val CHROMATIC_SPREAD_TIER2 = 0.0f

        /** Pixel roughly on the diagonal rim, well past the 0.38 falloff. */
        private const val RIM_X = 12
        private const val RIM_Y = 12
    }
}
