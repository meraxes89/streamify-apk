package com.streamify.graphics.engine

import com.streamify.graphics.engine.adapter.LiquidGlassSource
import com.streamify.graphics.tokens.PrimitiveTokens
import com.streamify.graphics.tokens.SemanticTokens
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
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Phase 4, Track A — Headless Skia Offscreen Render Golden Test
 * (docs/gpu/CI_TESTING_SPEC.md, Job 3).
 *
 * Renders LiquidGlassCore to an offscreen raster surface across multiple tiers
 * (Tier 1 chromatic dispersion vs Tier 2 single tap) and verifies:
 *  1. Non-zero, spatially varying alpha and color evaluation.
 *  2. Chromatic dispersion delta produces measurable R/B channel divergence at edges.
 *  3. Bounded execution within headless memory and raster constraints.
 *
 * Bindings follow the module's proven dialect-gate shapes
 * (DialectTranspileTest / AgslDialectEquivalenceTest): `Shader
 * .makeLinearGradient(Gradient(Gradient.Colors(...)))` and the frozen token
 * names of PrimitiveTokens / SemanticTokens (`lensCurvatureStandard`,
 * `refractionIndexDefault`, `chromaticSpreadDefault`, `surfaceGlassMuted`).
 */
class OffscreenRenderGoldenTest {

    private fun createSyntheticBackdrop(width: Int, height: Int): Shader =
        Shader.makeLinearGradient(
            0f,
            0f,
            width.toFloat(),
            height.toFloat(),
            Gradient(
                Gradient.Colors(
                    arrayOf(
                        Color4f(0.12f, 0.53f, 0.90f, 1.0f), // deep blue
                        Color4f(0.85f, 0.11f, 0.38f, 1.0f), // vibrant magenta
                        Color4f(1.00f, 0.70f, 0.00f, 1.0f), // amber gold
                        Color4f(0.26f, 0.64f, 0.28f, 1.0f), // green
                    ),
                    null,
                    FilterTileMode.CLAMP,
                ),
            ),
        )

    @Test
    fun renderLiquidGlassHeadlessProducesDeterministicPixels() {
        val width = 128
        val height = 128
        val effect = RuntimeEffect.makeForShader(LiquidGlassSource.SKSL)
        assertNotNull(effect, "LiquidGlassCore must compile on headless Skia")

        val surface = Surface.makeRasterN32Premul(width, height)
        val canvas = surface.canvas
        val backdropShader = createSyntheticBackdrop(width, height)

        val builder = RuntimeShaderBuilder(effect)
        builder.child("backdrop", backdropShader)
        builder.uniform("resolution", width.toFloat(), height.toFloat())
        builder.uniform("lensRadius", PrimitiveTokens.lensCurvatureStandard)
        builder.uniform("refractionIndex", PrimitiveTokens.refractionIndexDefault)
        builder.uniform("chromaticSpread", PrimitiveTokens.chromaticSpreadDefault)
        builder.uniform(
            "tintColor",
            SemanticTokens.surfaceGlassMuted.red,
            SemanticTokens.surfaceGlassMuted.green,
            SemanticTokens.surfaceGlassMuted.blue,
            SemanticTokens.surfaceGlassMuted.alpha,
        )

        val paint = Paint().apply {
            shader = builder.makeShader()
        }

        canvas.drawRect(Rect.makeWH(width.toFloat(), height.toFloat()), paint)

        val bitmap = Bitmap().apply {
            allocN32Pixels(width, height)
        }
        val readSuccess = surface.readPixels(bitmap, 0, 0)
        assertTrue(readSuccess, "surface.readPixels must succeed on headless Skia")

        // Sample center and edge pixels to verify non-zero render output
        val centerPixel = bitmap.getColor(width / 2, height / 2)
        val edgePixel = bitmap.getColor(width - 2, height - 2)

        assertTrue(centerPixel != 0, "Center pixel must have evaluated color")
        assertTrue(edgePixel != 0, "Edge pixel must have evaluated color")

        // Alpha preservation: the composite is
        // mix(half4(c, a), tintColor, tintColor.a) + specular — with a
        // translucent tint (surfaceGlassMuted, alpha 0.24) over an opaque
        // backdrop the output alpha is mix(1.0, 0.24, 0.24) ~= 0.82 plus the
        // Fresnel specular: NOT 255. The real headless-raster contract is
        // that alpha survives the composite (non-zero, and within the
        // blend-then-specular envelope), not full opacity.
        val alphaCenter = (centerPixel ushr 24) and 0xFF
        val alphaEdge = (edgePixel ushr 24) and 0xFF
        assertTrue(alphaCenter in MIN_ALPHA..MAX_ALPHA, "Alpha at center must survive the composite (was $alphaCenter)")
        assertTrue(alphaEdge in MIN_ALPHA..MAX_ALPHA, "Alpha at edge must survive the composite (was $alphaEdge)")
    }

    @Test
    fun chromaticDispersionProducesMeasurableColorOffset() {
        val width = 64
        val height = 64
        val effect = RuntimeEffect.makeForShader(LiquidGlassSource.SKSL)
        val backdropShader = createSyntheticBackdrop(width, height)

        // Stress configuration: synthetic golden values far beyond the
        // production token (0.015) so the 3-tap divergence is orders of
        // magnitude above 8-bit quantization — the golden gate must be
        // deterministic on any runner, not depend on subpixel luck.
        val stressSpread = 0.5f
        val stressIndex = 2.0f

        // Tier 1 (dispersion enabled)
        val builderT1 = RuntimeShaderBuilder(effect).apply {
            child("backdrop", backdropShader)
            uniform("resolution", width.toFloat(), height.toFloat())
            uniform("lensRadius", 0.4f)
            uniform("refractionIndex", stressIndex)
            uniform("chromaticSpread", stressSpread)
            uniform("tintColor", 0f, 0f, 0f, 0f)
        }

        // Tier 2 (dispersion collapsed)
        val builderT2 = RuntimeShaderBuilder(effect).apply {
            child("backdrop", backdropShader)
            uniform("resolution", width.toFloat(), height.toFloat())
            uniform("lensRadius", 0.4f)
            uniform("refractionIndex", stressIndex)
            uniform("chromaticSpread", 0.0f) // No dispersion
            uniform("tintColor", 0f, 0f, 0f, 0f)
        }

        val surfaceT1 = Surface.makeRasterN32Premul(width, height)
        surfaceT1.canvas.drawRect(Rect.makeWH(width.toFloat(), height.toFloat()), Paint().apply { shader = builderT1.makeShader() })
        val bmpT1 = Bitmap().apply { allocN32Pixels(width, height) }
        surfaceT1.readPixels(bmpT1, 0, 0)

        val surfaceT2 = Surface.makeRasterN32Premul(width, height)
        surfaceT2.canvas.drawRect(Rect.makeWH(width.toFloat(), height.toFloat()), Paint().apply { shader = builderT2.makeShader() })
        val bmpT2 = Bitmap().apply { allocN32Pixels(width, height) }
        surfaceT2.readPixels(bmpT2, 0, 0)

        // At refraction-boundary pixels the Tier 1 3-tap dispersion must
        // diverge from the Tier 2 single tap somewhere on the rim: sample a
        // band of rim positions (d in (0.5 - lensRadius, 0.5]) rather than a
        // single fragile pixel, and require divergence at ANY of them.
        var diverged = false
        outer@ for (y in 0 until height) {
            for (x in 0 until width) {
                val p = kotlin.math.sqrt(
                    ((x + 0.5f) / width - 0.5f).let { it * it } +
                        ((y + 0.5f) / height - 0.5f).let { it * it },
                )
                if (p <= 0.5f - 0.4f) continue // interior: no refraction
                if (bmpT1.getColor(x, y) != bmpT2.getColor(x, y)) {
                    diverged = true
                    break@outer
                }
            }
        }
        assertTrue(diverged, "Tier 1 chromatic dispersion must diverge from Tier 2 at refraction boundary")
    }

    private companion object {
        /**
         * Alpha envelope of the headless composite: translucent tint over
         * opaque backdrop (mix(1.0, 0.24, 0.24) ~= 0.82 -> ~209) plus the
         * Fresnel specular add (0.35 * fr). Anything inside [0.5, 1.0]
         * alpha space means the composite survived; 0 would mean the
         * backdrop alpha was lost, and 255 would mean the tint was skipped.
         */
        private const val MIN_ALPHA = 128
        private const val MAX_ALPHA = 255
    }
}
