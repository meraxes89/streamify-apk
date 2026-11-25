package com.streamify.graphics.engine

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import com.streamify.graphics.tokens.PrimitiveTokens
import org.jetbrains.skia.FilterTileMode
import org.jetbrains.skia.Image
import org.jetbrains.skia.Matrix33
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.SamplingMode
import org.jetbrains.skia.Shader
import org.jetbrains.skia.Surface

/**
 * Dynamic Resolution Scaling (DRS) offscreen refraction pass for the skiko
 * targets (S24 Track B; docs/gpu/ARCHITECTURE.md section 3).
 *
 * Runs the canonical `LiquidGlassCore` runtime shader over a backdrop
 * snapshot on an **offscreen raster surface whose pixel dimensions are the
 * draw scope's physical buffer dimensions times [passScale]**:
 *
 *  * **Tier 1 (Ultra)** — [passScale] 1.0: full-resolution refraction with
 *    chromatic dispersion, the RTX 40 / Radeon 7000 lane.
 *  * **Tier 2 (Balanced)** — [passScale] [TIER2_BALANCED_SCALE] (0.5×
 *    width/height): the refraction pass evaluates at one quarter of the
 *    pixels and the engine upscales the result bilinearly on composite.
 *    This is the UMA iGPU lane: full-resolution multi-pass SkSL on Intel HD
 *    2000–630 / Iris / AMD Vega iGPUs saturates shared DDR3/DDR4 bandwidth
 *    — a 0.5× pass cuts sampling traffic by ~75% so those parts hold
 *    60 FPS inside Tier 2 instead of dropping to the Tier 3 scrim.
 *
 * ## Physical-pixel resolution contract (S24 Track D)
 *
 * The `uniform float2 resolution` binds to **this surface's device pixel
 * dimensions** — never a logical/dp size. Compose Desktop records its
 * picture at `componentWidth * contentScale` (skiko `SkiaLayer.awt.kt`:
 * `pictureWidth = backedLayer.width * contentScale`), so a draw scope's
 * pixel size already IS the physical buffer size on fractional-scale
 * Wayland / X11 / Windows displays; multiplying by density on top of that
 * would double-scale and warp the lens falloff at 125%/150%. The pass
 * therefore sizes its surface from the caller-reported physical pixel
 * extent, and the golden test pins exactly this at 1.25/1.5 densities.
 *
 * ## Allocation contract
 *
 * All buffers are cached and rebuilt only on change: the Compose→Skia
 * backdrop conversion caches by snapshot identity, the [Surface]
 * reallocates only when the pass dimensions change, and the rendered
 * result is reused while the backdrop stays unchanged. A steady-state
 * frame (same backdrop, same size) performs an identity check and returns
 * the cached image — zero allocation on the draw path. Fresh work happens
 * only when the captured backdrop changes (at most once per capture tick,
 * and the capture itself runs off the draw phase in
 * [rememberGlassBackdrop][com.streamify.graphics.api.rememberGlassBackdrop]).
 */
internal class SkikoOffscreenGlassPass(
    private val program: SkikoLiquidGlassProgram,
    private val chromaticSpread: Float,
    private val passScale: Float,
) {

    /** Cached offscreen target — reallocated only when the pass dimensions change. */
    private var surface: Surface? = null

    private var passWidth: Int = 0
    private var passHeight: Int = 0

    /** Last rendered result (as drawn by the engine), reused while the backdrop is unchanged. */
    private var cachedResult: ImageBitmap? = null

    /** Skia image the current [cachedResult] was rendered from. */
    private var renderedFrom: Image? = null

    /** Compose snapshot [convertedImage] was converted from (identity cache). */
    private var convertedFrom: ImageBitmap? = null

    /** Skia image for [convertedFrom] — reused across renders of the same snapshot. */
    private var convertedImage: Image? = null

    /**
     * Renders the refracted glass composite for [backdrop] at the physical
     * pixel extent ([widthPx], [heightPx]) and returns a draw-ready image:
     * full-resolution input sampling, [passScale]-scaled pass output.
     *
     * The [tint] is folded into the shader's own `tintColor` mix, so the
     * returned image is the *complete* glass surface fill; the engine adds
     * only the specular rim on top. Returns the cached image while
     * [backdrop] is the same instance with unchanged dimensions, and
     * `null` — never an exception — when any stage fails, in which case
     * the engine falls back to its stopgap composite.
     */
    fun render(
        backdrop: ImageBitmap,
        widthPx: Int,
        heightPx: Int,
        tint: Color,
        darkenAmount: Float = AdaptiveLuminanceScrim.alphaFor(PrimitiveTokens.defaultBackdropLuminance),
    ): ImageBitmap? {
        val w = passWidthFor(widthPx)
        val h = passHeightFor(heightPx)
        if (w <= 0 || h <= 0) return null

        // Steady state: same snapshot, same pass dimensions — cached result,
        // zero allocation, zero Skia work.
        if (backdrop === convertedFrom &&
            renderedFrom != null &&
            cachedResult != null &&
            passWidth == w && passHeight == h
        ) {
            return cachedResult
        }

        val skiaBackdrop = convertedImage.takeIf { backdrop === convertedFrom }
            ?: backdrop.toSkiaBitmapOrNull()?.let { bitmap ->
                runCatching { Image.makeFromBitmap(bitmap) }.getOrNull()
            }
        if (skiaBackdrop == null) return null
        convertedFrom = backdrop
        convertedImage = skiaBackdrop

        val result = runCatching { renderFresh(skiaBackdrop, w, h, tint, darkenAmount) }.getOrNull()
        if (result != null) {
            renderedFrom = skiaBackdrop
            cachedResult = result
        }
        return result
    }

    private fun renderFresh(
        backdrop: Image,
        w: Int,
        h: Int,
        tint: Color,
        darkenAmount: Float,
    ): ImageBitmap? {
        // The backdrop child samples the FULL-resolution source; the local
        // matrix rescales pass coordinates (0..w) onto source pixels
        // (0..widthPx) — this is exactly the offscreen downsampling step,
        // LINEAR-filtered so the 0.5x downsample does not alias.
        val backdropShader: Shader = backdrop.makeShader(
            FilterTileMode.CLAMP,
            FilterTileMode.CLAMP,
            SamplingMode.LINEAR,
            Matrix33(1f / passScale, 0f, 0f, 0f, 1f / passScale, 0f, 0f, 0f, 1f),
        )

        program.bindBackdrop(backdropShader)
        program.bindUniforms(
            width = w.toFloat(),
            height = h.toFloat(),
            chromaticSpread = chromaticSpread,
            tintColorR = tint.red,
            tintColorG = tint.green,
            tintColorB = tint.blue,
            tintColorA = tint.alpha,
            darkenAmount = darkenAmount,
        )

        val target = surface?.takeIf { passWidth == w && passHeight == h }
            ?: Surface.makeRasterN32Premul(w, h).also {
                surface?.close()
                surface = it
                passWidth = w
                passHeight = h
            }
        target.canvas.drawRect(
            Rect.makeWH(w.toFloat(), h.toFloat()),
            Paint().apply { shader = program.makeShader() },
        )
        return target.makeImageSnapshot().toComposeImageBitmapOrNull()
    }

    private fun passWidthFor(widthPx: Int): Int = scaledDim(widthPx)
    private fun passHeightFor(heightPx: Int): Int = scaledDim(heightPx)

    private fun scaledDim(dim: Int): Int {
        val scaled = kotlin.math.ceil(dim.toFloat() * passScale).toInt()
        return scaled.coerceAtLeast(1)
    }

    companion object {
        /**
         * Tier 2 (Balanced) DRS factor — 0.5x width/height = ~25% of the
         * full-resolution sample traffic (~75% UMA bandwidth saving), per
         * the S24 desktop-spectrum matrix.
         */
        const val TIER2_BALANCED_SCALE = 0.5f
    }
}
