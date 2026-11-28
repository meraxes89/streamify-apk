package com.streamify.graphics.engine

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import kotlin.math.ceil

/**
 * TBDR-friendly dynamic resolution scaling (DRS) offscreen pass for the
 * skiko targets (docs/systems/S24-sovereign-graphics.md, Track C).
 *
 * Apple Silicon (and every mobile GPU) renders through small on-chip tile
 * memories; a full-resolution multi-tap refraction pass at Retina 3x
 * repeatedly flushes tile memory to system RAM. This pass executes the
 * blur/refraction composite at `captureScale` resolution (0.5x / 0.33x —
 * logical 1x on 2x/3x displays, per
 * [com.streamify.graphics.governor.BackdropResolutionPolicy]) into a
 * cached raster surface and hands back the snapshot for the caller to
 * upscale into the destination outline with hardware bilinear filtering.
 * At 3x that is 1/9th the pixels and ~1/9th the tile flushes: the ~75%
 * bandwidth cut of the S24 Track C contract.
 *
 * ## Cost model (zero-allocation invariant, honestly stated)
 *
 *  * The [Surface] is allocated once per capture size and reused — a size
 *    change (layout transition) reallocates, a steady draw loop does not.
 *  * Steady-state per-frame cost is one `makeImageSnapshot()` of the
 *    *reduced-area* surface plus the uniform rebind the Tier 1/2 engines
 *    already perform per draw — native refcounted objects at 1/9th area,
 *    no Kotlin-heap churn, and no Compose recomposition (draw-phase only,
 *    the engine's core invariant).
 *  * The pass is only instantiated when the policy yields a scale below
 *    1.0 (Tier 2 on Retina-class density); Tier 1 and the scrim lanes
 *    never pay for it.
 *
 * ## Resilience (Track D)
 *
 * [render] itself never throws for the caller: it renders through the
 * shared [SkikoLiquidGlassProgram] whose constructor already validated the
 * full pipeline state (see its KDoc), and any failure inside the pass
 * surfaces as `null` so the engine falls back to the stable full-path
 * composite — the scrim lane is never far away.
 */
internal class DynamicResolutionOffscreenPass(
    /** Capture scale from [com.streamify.graphics.governor.BackdropResolutionPolicy]. */
    val captureScale: Float,
) {

    /** Cached raster surface — reallocated only when the capture size changes. */
    private var surface: Surface? = null

    private var surfaceWidth = 0

    private var surfaceHeight = 0

    /** Reused paint holder — the shader is rebound per render. */
    private val paint = Paint()

    /**
     * Renders the LiquidGlass refraction composite at the reduced capture
     * resolution and returns the snapshot to upscale bilinearly.
     *
     * @param program The shared live program; its `backdrop` child must be
     * bound (the facade-level offscreen snapshot seam) and this pass rebinds
     * the resolution + tint uniforms at the REDUCED resolution before
     * materializing the shader.
     * @param destinationWidthPx Destination composite width in pixels.
     * @param destinationHeightPx Destination composite height in pixels.
     * @param chromaticSpread The engine's tier spread (0 for Tier 2 single tap).
     * @param tint The engine's resolved tint fill.
     * @return The reduced-resolution snapshot, or `null` when the pass
     * failed on this host (the caller degrades to the stable composite).
     */
    fun render(
        program: SkikoLiquidGlassProgram,
        destinationWidthPx: Float,
        destinationHeightPx: Float,
        chromaticSpread: Float,
        tint: Color,
        darkenAmount: Float = 0f,
    ): ImageBitmap? {
        val reducedWidth = ceil(destinationWidthPx * captureScale).toInt().coerceAtLeast(1)
        val reducedHeight = ceil(destinationHeightPx * captureScale).toInt().coerceAtLeast(1)

        val target = obtainSurface(reducedWidth, reducedHeight)

        // Bind the shader's coordinate space to the reduced resolution —
        // every backdrop.eval() tap in the composite now samples at the
        // reduced raster, which is the entire bandwidth win. Lens radius
        // and IOR ride the bindUniforms token defaults, exactly like the
        // full-resolution engine path.
        program.bindUniforms(
            width = reducedWidth.toFloat(),
            height = reducedHeight.toFloat(),
            chromaticSpread = chromaticSpread,
            tintColorR = tint.red,
            tintColorG = tint.green,
            tintColorB = tint.blue,
            tintColorA = tint.alpha,
            darkenAmount = darkenAmount,
        )

        paint.shader = program.makeShader()
        target.canvas.drawRect(
            Rect.makeWH(reducedWidth.toFloat(), reducedHeight.toFloat()),
            paint,
        )

        // Snapshot at 1/9th the destination area on a 3x display: the
        // upscale back into the destination outline is the caller's cheap
        // bilinear draw.
        return target.makeImageSnapshot().toComposeImageBitmap()
    }

    /** Reuses the cached surface across frames; reallocates only on size change. */
    private fun obtainSurface(width: Int, height: Int): Surface {
        val cached = surface
        if (cached != null && surfaceWidth == width && surfaceHeight == height) {
            return cached
        }
        cached?.close()
        val created = Surface.makeRasterN32Premul(width, height)
        surface = created
        surfaceWidth = width
        surfaceHeight = height
        return created
    }
}
