package com.streamify.graphics.engine

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import com.streamify.graphics.governor.BackdropResolutionPolicy
import com.streamify.graphics.governor.RenderTier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.jetbrains.skia.Color4f
import org.jetbrains.skia.FilterTileMode
import org.jetbrains.skia.Gradient
import org.jetbrains.skia.Shader

/**
 * S24 Track E — the TBDR dynamic resolution scaling (DRS) offscreen pass
 * gate (docs/systems/S24-sovereign-graphics.md, Track C;
 * docs/gpu/CI_TESTING_SPEC.md Job 3 lane).
 *
 * Drives the exact production machinery on the desktop JVM: the shared
 * [liquidGlassProgram] with a bound backdrop child, rendered through
 * [DynamicResolutionOffscreenPass] at the Retina-3x capture scale
 * (1/3) — asserting the reduced raster dimensions, spatially varying
 * alpha-preserving output, steady-size surface reuse, clean size-change
 * reallocation, and the engine wiring that carries the policy's capture
 * scale.
 */
class DynamicResolutionOffscreenPassTest {

    @Test
    fun rendersTheCompositeAtOneThirdResolutionForRetinaThreeX() {
        val program = assertNotNull(
            liquidGlassProgram,
            "the canonical program must compile on this host — the lane would degrade to the scrim engine",
        )
        program.bindBackdrop(backdropGradient())

        val pass = DynamicResolutionOffscreenPass(captureScale = 1f / 3f)
        val composite = assertNotNull(
            pass.render(
                program = program,
                destinationWidthPx = WIDTH.toFloat(),
                destinationHeightPx = HEIGHT.toFloat(),
                chromaticSpread = 0f, // Tier 2 single tap
                tint = Color(0f, 0f, 0f, 0.5f),
            ),
            "the DRS pass must render on a healthy skiko host",
        )

        // The entire TBDR contract in two numbers: the refraction composite
        // rasterized at 1/9th the destination pixels on a 3x display.
        assertEquals(WIDTH / 3, composite.width)
        assertEquals(HEIGHT / 3, composite.height)

        val pixels = composite.toPixelMap()
        val center = pixels[composite.width / 2, composite.height / 2]
        val rim = pixels[1, 1]
        assertTrue(center != rim, "reduced composite must stay spatially varying (lens math evaluating)")
        assertTrue(center.alpha > 0.9f, "alpha must survive the reduced-resolution pass")
    }

    @Test
    fun steadySizeRendersReuseThePassAndSizeChangesReallocateCleanly() {
        val program = assertNotNull(liquidGlassProgram)
        program.bindBackdrop(backdropGradient())

        val pass = DynamicResolutionOffscreenPass(captureScale = 0.5f)

        // Steady-state frames (e.g. during rapid sheet/modal scrolling):
        // same size, surface reused, no failure.
        repeat(5) {
            val composite = assertNotNull(
                pass.render(program, WIDTH.toFloat(), HEIGHT.toFloat(), chromaticSpread = 0f, tint = CLEAR_TINT),
            )
            assertEquals(WIDTH / 2, composite.width)
            assertEquals(HEIGHT / 2, composite.height)
        }

        // Layout transition: the destination size changes; the pass
        // reallocates without throwing and follows the new geometry.
        val resized = assertNotNull(
            pass.render(program, SMALL_WIDTH.toFloat(), SMALL_HEIGHT.toFloat(), chromaticSpread = 0f, tint = CLEAR_TINT),
        )
        assertEquals(SMALL_WIDTH / 2, resized.width)
        assertEquals(SMALL_HEIGHT / 2, resized.height)
    }

    @Test
    fun tier2EngineCarriesThePolicyCaptureScaleForTheHostDensity() {
        val program = assertNotNull(liquidGlassProgram)

        // Direct construction (the Retina row, synthetic density):
        val engine = SkikoRuntimeShaderEngine(
            program = program,
            chromaticSpread = 0f,
            backdropCaptureScale = BackdropResolutionPolicy.captureScale(RenderTier.Tier2_Balanced, 3f),
        )
        assertEquals(1f / 3f, engine.backdropCaptureScale, 1e-6f)

        // The provider's cached engines carry the policy scale for the real
        // host density (1.0 on the CI lane — the Retina rows are pinned by
        // BackdropResolutionPolicyTest).
        val hostDensity = skikoDisplayDensityScale()
        val tier1Engine = ShaderProvider.engineFor(RenderTier.Tier1_Ultra)
        val tier2Engine = ShaderProvider.engineFor(RenderTier.Tier2_Balanced)
        assertTrue(tier1Engine is SkikoRuntimeShaderEngine)
        assertTrue(tier2Engine is SkikoRuntimeShaderEngine)
        assertEquals(
            BackdropResolutionPolicy.captureScale(RenderTier.Tier1_Ultra, hostDensity),
            tier1Engine.backdropCaptureScale,
            1e-6f,
        )
        assertEquals(
            BackdropResolutionPolicy.captureScale(RenderTier.Tier2_Balanced, hostDensity),
            tier2Engine.backdropCaptureScale,
            1e-6f,
        )
    }

    @Test
    fun bindingABackdropArmsTheDynamicResolutionSeam() {
        val program = assertNotNull(liquidGlassProgram)
        assertEquals(false, program.hasBoundBackdrop)
        program.bindBackdrop(backdropGradient())
        assertEquals(true, program.hasBoundBackdrop)
    }

    /** A horizontal gradient backdrop: distinct colors across x, so the
     * refraction offset shows up as a visible pixel delta (the same
     * fixture shape SkikoShaderProviderTest uses). */
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
        /** Retina 3x class destination: 384 logical -> 128 reduced raster. */
        private const val WIDTH = 384
        private const val HEIGHT = 384

        private const val SMALL_WIDTH = 200
        private const val SMALL_HEIGHT = 120

        private val CLEAR_TINT = Color(0f, 0f, 0f, 0f)
    }
}
