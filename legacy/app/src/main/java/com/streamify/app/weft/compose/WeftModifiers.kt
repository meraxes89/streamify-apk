// ─────────────────────────────────────────────────────────────────────────────
// VENDORED into Streamify from zephyr4289/Weft @ 08e3a53
// (android/weft-compose/src/main/kotlin/dev/weft/compose/WeftModifiers.kt) per
// the Engineering Directive: Real-Device Integration of the Weft
// Continuous-State Plane.
//
// Kernel semantics are FROZEN — only the package declaration + the
// dev.weft imports changed.
// ─────────────────────────────────────────────────────────────────────────────

// WeftModifiers.kt — Compose draw-phase and graphicsLayer deferred read modifiers
//
// WHY EXISTS: Defers reading the Weft channel to the Compose Draw phase
// (bypassing composition and layout passes completely) per WHITEPAPER §8.1.
//
// AGSL / RENDER EFFECT INTEGRATION:
// Android 13+ (API 33+) allows setting RenderEffect via Modifier.graphicsLayer.
// Custom AGSL shaders consume the Weft channel's live direct buffer in the
// GPU render pipeline without copying to CPU heap.

package com.streamify.app.weft.compose

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import com.streamify.app.weft.Weft
import java.nio.ByteBuffer

/**
 * Modifier that binds a Weft channel to Compose draw-phase execution.
 *
 * Reads occur strictly on VSYNC ticks inside [onDraw], avoiding recomposition.
 *
 * DRAW-PHASE READ RULE (RFC-0001 §4.3): the draw lambda receives the
 * READER-HELD buffer (r_work) as a payload-relative zero-copy view — the
 * freshest complete frame, exclusively owned by the draw thread until the
 * next claim(). Never read heddle.weft.wBegin() here: that is the writer's
 * scratch buffer, concurrently being filled on the producer thread.
 */
public fun Modifier.weftDraw(
    heddle: WeftHeddle,
    onDraw: DrawScope.(ByteBuffer) -> Unit
): Modifier = this.drawWithContent {
    val weft = heddle.weft
    weft.claim()
    onDraw(weft.rLiveBuf())
    drawContent()
}

/**
 * Modifier that configures a graphicsLayer with deferred draw-phase reads.
 *
 * Can be used with Android 13+ AGSL RuntimeShader / RenderEffect hooks for zero-copy
 * GPU shader parameters.
 */
public fun Modifier.weftGraphicsLayer(
    heddle: WeftHeddle,
    block: GraphicsLayerScope.(Weft) -> Unit
): Modifier = this.graphicsLayer {
    block(heddle.weft)
}
