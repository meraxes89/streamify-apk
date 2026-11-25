package com.streamify.graphics.api

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.layer.GraphicsLayer
import kotlin.concurrent.Volatile

/**
 * Live offscreen backdrop capture feeding the Sovereign GPU Engine's
 * refraction pipeline (S24 Track B).
 *
 * `GraphicsLayer.toImageBitmap()` is a suspend API — layer pixels are only
 * reachable **outside** the draw phase. This holder pairs the captured
 * layer with its latest snapshot: [rememberGlassBackdrop] drives the
 * capture on the composition's frame clock (never inside a draw pass),
 * and the engine's draw phase only performs a volatile read of
 * [latestInternal] — zero allocation, zero querying on the draw thread.
 *
 * Constructed exclusively by [rememberGlassBackdrop].
 */
class GlassBackdropSnapshot internal constructor(
    internal val layer: GraphicsLayer,
) {

    /**
     * Latest captured backdrop pixels — a new [ImageBitmap] instance per
     * capture tick. The engine re-renders its offscreen pass only when
     * this identity changes; unchanged frames reuse the cached result.
     */
    @Volatile
    internal var latestInternal: ImageBitmap? = null

    internal fun update(bitmap: ImageBitmap?) {
        if (bitmap != null) latestInternal = bitmap
    }
}

/**
 * Captures [backdrop] off the draw phase and feeds the engine's offscreen
 * refraction / DRS pipeline (S24 Track B).
 *
 * Pairs with the [Modifier.streamifyGlass] overload that takes a
 * [GlassBackdropSnapshot]:
 *
 * ```kotlin
 * val backdrop = rememberGraphicsLayer()
 * // … draw content into the layer …
 * Modifier.streamifyGlass(rememberGlassBackdrop(backdrop), shape, tint)
 * ```
 *
 * The capture loop rides the composition frame clock: one
 * `GraphicsLayer.toImageBitmap()` per frame, executed in the composition
 * coroutine context — never on the draw thread. An unrecorded or failing
 * layer is silently skipped (the engine draws its stopgap composite), and
 * recomposition count stays zero while the layer is alive: the modifier
 * reads the holder directly, not snapshot state.
 */
@Composable
fun rememberGlassBackdrop(backdrop: GraphicsLayer): GlassBackdropSnapshot {
    val holder = remember(backdrop) { GlassBackdropSnapshot(backdrop) }
    LaunchedEffect(backdrop) {
        while (true) {
            withFrameNanos { }
            // Off the draw phase by contract: this suspend rasterization is
            // the "adapter querying" the engine invariants demand outside
            // the draw pass. Failures (unrecorded layer, disposed layer)
            // keep the previous snapshot — the stopgap lane covers the rest.
            runCatching { backdrop.toImageBitmap() }.getOrNull()
                ?.let(holder::update)
        }
    }
    return holder
}
