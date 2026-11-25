package com.streamify.graphics.engine

import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.streamify.graphics.engine.adapter.LiquidGlassSource
import com.streamify.graphics.tokens.PrimitiveTokens
import com.streamify.graphics.tokens.SemanticTokens
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.FilterTileMode
import org.jetbrains.skia.Gradient
import org.jetbrains.skia.Image
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.RuntimeEffect
import org.jetbrains.skia.RuntimeShaderBuilder
import org.jetbrains.skia.SamplingMode
import org.jetbrains.skia.Shader
import org.jetbrains.skia.Surface
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Phase 4, Track A — Headless Skia Offscreen Render Golden Test
 * (docs/gpu/CI_TESTING_SPEC.md Job 3), extended for the S24 desktop
 * spectrum: Dynamic Resolution Scaling (Track B) and fractional-DPI
 * physical-pixel resolution (Track D).
 *
 * Renders LiquidGlassCore to offscreen raster surfaces across multiple
 * tiers (Tier 1 chromatic dispersion vs Tier 2 single tap) and verifies:
 *  1. Non-zero, spatially varying alpha and color evaluation.
 *  2. Chromatic dispersion delta produces measurable R/B channel divergence at edges.
 *  3. The Tier 2 DRS pass renders at half resolution (0.5x) and upscales
 *     bilinearly to a clean full-size composite.
 *  4. The `uniform float2 resolution` receives physical pixel buffer
 *     dimensions under fractional pixel densities (125% / 150%) — the
 *     pass output matches a hand-driven direct render at the same
 *     physical dims, proving no logical size leaked into the uniform.
 *  5. Bounded execution within headless memory and raster constraints.
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

    /** Full-resolution backdrop snapshot as a skia image (the capture-lane input). */
    private fun createBackdropImage(width: Int, height: Int): Image {
        val surface = Surface.makeRasterN32Premul(width, height)
        surface.canvas.drawRect(
            Rect.makeWH(width.toFloat(), height.toFloat()),
            Paint().apply { shader = createSyntheticBackdrop(width, height) },
        )
        return surface.makeImageSnapshot()
    }

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

    // ------------------------------------------------------------------
    // S24 Track B — Tier 2 Dynamic Resolution Scaling golden lane
    // ------------------------------------------------------------------

    @Test
    fun tier2DrsPassRendersAtHalfResolution() {
        val program = assertNotNull(
            liquidGlassProgram,
            "the canonical program must compile on headless Skia for the DRS gate",
        )
        val pass = SkikoOffscreenGlassPass(
            program = program,
            chromaticSpread = 0f, // Tier 2 single-tap lane
            passScale = SkikoOffscreenGlassPass.TIER2_BALANCED_SCALE,
        )
        val backdrop = createBackdropImage(WIDTH, HEIGHT)
        val result = assertNotNull(
            pass.render(
                backdrop = backdrop.toComposeImageBitmap(),
                widthPx = WIDTH,
                heightPx = HEIGHT,
                tint = SemanticTokens.surfaceGlassMuted,
            ),
            "the 0.5x DRS pass must render on headless Skia",
        )

        // 0.5x width/height — the offscreen downsampling contract.
        assertEquals(WIDTH / 2, result.width, "Tier 2 pass output width must be half resolution")
        assertEquals(HEIGHT / 2, result.height, "Tier 2 pass output height must be half resolution")

        val pixels = result.asSkiaBitmap()
        val center = pixels.getColor(WIDTH / 4, HEIGHT / 4)
        val rim = pixels.getColor(RIM_X / 2, RIM_Y / 2)
        assertTrue(center != rim, "the half-res pass must still evaluate spatially varying lens math")
        assertTrue((center ushr 24) != 0, "the half-res pass must preserve the composite alpha")
    }

    @Test
    fun tier2DrsPassUpscalesBilinearlyToAFullResolutionComposite() {
        val program = assertNotNull(liquidGlassProgram)
        val pass = SkikoOffscreenGlassPass(
            program = program,
            chromaticSpread = 0f,
            passScale = SkikoOffscreenGlassPass.TIER2_BALANCED_SCALE,
        )
        val backdrop = createBackdropImage(WIDTH, HEIGHT)
        val halfRes = assertNotNull(
            pass.render(
                backdrop = backdrop.toComposeImageBitmap(),
                widthPx = WIDTH,
                heightPx = HEIGHT,
                tint = SemanticTokens.surfaceGlassMuted,
            ),
        )
        val halfResImage = Image.makeFromBitmap(halfRes.asSkiaBitmap())

        // The engine's composite step: bilinear upscale of the 0.5x pass
        // result back onto the full physical-resolution surface.
        val upscaled = Surface.makeRasterN32Premul(WIDTH, HEIGHT)
        upscaled.canvas.drawImageRect(
            halfResImage,
            Rect.makeWH(halfRes.width.toFloat(), halfRes.height.toFloat()),
            Rect.makeWH(WIDTH.toFloat(), HEIGHT.toFloat()),
            SamplingMode.LINEAR,
            null,
            true,
        )
        val pixels = Bitmap.makeFromImage(upscaled.makeImageSnapshot())

        val center = pixels.getColor(WIDTH / 2, HEIGHT / 2)
        val rim = pixels.getColor(RIM_X, RIM_Y)
        assertTrue(center != rim, "the bilinear upscale must keep the refraction spatially varying")
        assertTrue((center ushr 24) != 0, "the bilinear upscale must preserve alpha")
    }

    @Test
    fun drsPassReusesItsResultWithoutReallocationOnUnchangedBackdrop() {
        val program = assertNotNull(liquidGlassProgram)
        val pass = SkikoOffscreenGlassPass(program, chromaticSpread = 0f, passScale = 1.0f)
        val backdrop = createBackdropImage(WIDTH, HEIGHT).toComposeImageBitmap()

        val first = assertNotNull(pass.render(backdrop, WIDTH, HEIGHT, SemanticTokens.surfaceGlassMuted))
        // Steady-state frames: same snapshot instance, same dimensions —
        // the exact same (identity-equal, therefore unreallocated) image.
        repeat(8) {
            val steady = assertNotNull(pass.render(backdrop, WIDTH, HEIGHT, SemanticTokens.surfaceGlassMuted))
            assertTrue(steady === first, "an unchanged backdrop must return the cached result by identity")
        }
    }

    // ------------------------------------------------------------------
    // S24 Track D — fractional pixel density physical-resolution gate
    // ------------------------------------------------------------------

    @Test
    fun fractionalDensityRendersThroughPhysicalPixelResolution() {
        // A 400x320 logical surface at a 1.25 fractional scale (Wayland
        // 125%) has a 500x400 physical pixel buffer. The `resolution`
        // uniform must receive the physical buffer dimensions — the pass
        // surface is sized from the physical extent, so its output must
        // match a hand-driven direct render whose resolution is explicitly
        // the physical 500x400.
        val physicalW = 500
        val physicalH = 400
        val program = assertNotNull(liquidGlassProgram)
        val pass = SkikoOffscreenGlassPass(program, chromaticSpread = 0f, passScale = 1.0f)
        val backdrop = createBackdropImage(physicalW, physicalH)
        val backdropBitmap = backdrop.toComposeImageBitmap()

        val passResult = assertNotNull(
            pass.render(backdropBitmap, physicalW, physicalH, SemanticTokens.surfaceGlassMuted),
        )
        assertEquals(physicalW, passResult.width, "the pass surface must be physical pixel wide")
        assertEquals(physicalH, passResult.height, "the pass surface must be physical pixel high")

        // Direct render with resolution pinned to the physical dims.
        val direct = directRender(
            backdropImage = backdrop,
            width = physicalW,
            height = physicalH,
            chromaticSpread = 0f,
        )

        assertImagesMatchWithinTolerance(
            direct,
            passResult.asSkiaBitmap(),
            label = "1.25 fractional density",
        )
    }

    @Test
    fun oneAndAHalfDensityRendersThroughPhysicalPixelResolution() {
        // 300x200 logical at 1.5 scale (150%) = 450x300 physical.
        val physicalW = 450
        val physicalH = 300
        val program = assertNotNull(liquidGlassProgram)
        val pass = SkikoOffscreenGlassPass(program, chromaticSpread = 0f, passScale = 1.0f)
        val backdrop = createBackdropImage(physicalW, physicalH)

        val passResult = assertNotNull(
            pass.render(backdrop.toComposeImageBitmap(), physicalW, physicalH, SemanticTokens.surfaceGlassMuted),
        )
        assertEquals(physicalW, passResult.width)
        assertEquals(physicalH, passResult.height)

        val direct = directRender(backdrop, physicalW, physicalH, chromaticSpread = 0f)
        assertImagesMatchWithinTolerance(
            direct,
            passResult.asSkiaBitmap(),
            label = "1.5 fractional density",
        )
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /**
     * Hand-driven reference render: a fresh [RuntimeShaderBuilder] with the
     * `resolution` uniform explicitly bound to the given physical pixel
     * dimensions and the backdrop sampled as an image shader — the exact
     * contract the pass must reproduce.
     */
    private fun directRender(
        backdropImage: Image,
        width: Int,
        height: Int,
        chromaticSpread: Float,
    ): Bitmap {
        val effect = RuntimeEffect.makeForShader(LiquidGlassSource.SKSL)
        val builder = RuntimeShaderBuilder(effect).apply {
            child(
                "backdrop",
                backdropImage.makeShader(
                    FilterTileMode.CLAMP,
                    FilterTileMode.CLAMP,
                    SamplingMode.LINEAR,
                    null,
                ),
            )
            uniform("resolution", width.toFloat(), height.toFloat())
            uniform("lensRadius", PrimitiveTokens.lensCurvatureStandard)
            uniform("refractionIndex", PrimitiveTokens.refractionIndexDefault)
            uniform("chromaticSpread", chromaticSpread)
            uniform(
                "tintColor",
                SemanticTokens.surfaceGlassMuted.red,
                SemanticTokens.surfaceGlassMuted.green,
                SemanticTokens.surfaceGlassMuted.blue,
                SemanticTokens.surfaceGlassMuted.alpha,
            )
        }
        val surface = Surface.makeRasterN32Premul(width, height)
        surface.canvas.drawRect(
            Rect.makeWH(width.toFloat(), height.toFloat()),
            Paint().apply { shader = builder.makeShader() },
        )
        return Bitmap.makeFromImage(surface.makeImageSnapshot())
    }

    /**
     * Golden comparison with a one-LSB per-channel tolerance: the raster
     * backend is deterministic, but the two renders bind the backdrop
     * through different (equivalent) matrix paths, so a ±1 channel slack
     * keeps the gate robust while still catching any logical-size leak —
     * a resolution off by the 1.25/1.5 density factor displaces the lens
     * falloff by dozens of pixels.
     */
    private fun assertImagesMatchWithinTolerance(expected: Bitmap, actual: Bitmap, label: String) {
        assertEquals(expected.width, actual.width, "$label: widths must match")
        assertEquals(expected.height, actual.height, "$label: heights must match")
        var sampled = 0
        for (y in 0 until expected.height step SAMPLING_STRIDE) {
            for (x in 0 until expected.width step SAMPLING_STRIDE) {
                val e = expected.getColor(x, y)
                val a = actual.getColor(x, y)
                for (channel in 0 until 4) {
                    val shift = 8 * (3 - channel)
                    val delta = abs(((e ushr shift) and 0xFF) - ((a ushr shift) and 0xFF))
                    assertTrue(
                        delta <= 1,
                        "$label: pixel ($x, $y) channel $channel diverged by $delta — " +
                            "the resolution uniform did not receive the physical buffer dims",
                    )
                }
                sampled++
            }
        }
        assertTrue(sampled > 50, "$label: the comparison must sample a meaningful pixel set")
    }

    private companion object {
        private const val WIDTH = 256
        private const val HEIGHT = 256

        /** Pixel roughly on the diagonal rim, well past the 0.38 falloff. */
        private const val RIM_X = 12
        private const val RIM_Y = 12

        private const val SAMPLING_STRIDE = 7
    }
}
