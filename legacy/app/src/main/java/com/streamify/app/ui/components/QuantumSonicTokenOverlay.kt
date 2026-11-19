package com.streamify.app.ui.components

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.streamify.app.ui.theme.ActiveControl
import com.streamify.app.ui.theme.BgSurfaceElevated
import com.streamify.app.ui.theme.Primary
import com.streamify.app.ui.theme.TextMain
import com.streamify.app.ui.theme.TextSecondary
import com.streamify.app.weft.compose.weftDraw
import com.streamify.app.weft.compose.weftGraphicsLayer
import kotlinx.coroutines.isActive

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * QUANTUM SONIC TOKEN — WEFT CONTINUOUS-STATE FLIGHT RENDERER (CONSUMER)
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * Two-Plane Architecture (Engineering Directive: Real-Device Integration of
 * the Weft Continuous-State Plane):
 *
 *  • PLANE 1 (Reactive, < 10 Hz) — card content (title, artist, status,
 *    artwork) lives as ordinary composables, composed ONCE per flight and
 *    re-laid-out only when their content changes (a cold event). Nothing
 *    enters or leaves composition inside the animation envelope: the
 *    capsule subtree is permanently composed and alpha-gated by the
 *    channel's visibility flag.
 *
 *  • PLANE 2 (Weft, 60–120 Hz) — every per-frame value the renderer needs
 *    (position, stretch, rotation, pitch/roll, impact progress, docked/
 *    bloom flags) crosses to the render thread through the Triad channel's
 *    single atomic exchange:
 *
 *      ┌────────────────────────────┐    ┌──────────────────────────────┐
 *      │ PRODUCER (stepSimulation)  │    │ RENDER (Compose draw phase)  │
 *      │ writes pose into w_work    │    │ claims r_work ONCE per frame │
 *      │ zero allocs, zero locks    │    │ reads in draw/layer scopes   │
 *      └─────────────┬──────────────┘    └───────────────▲──────────────┘
 *                    └────► [ single atomic ] ───────────┘
 *
 *    SINGLE-READER DISCIPLINE: the Triad is single-reader by design — the
 *    impact-bloom Canvas (drawn first, below) performs THE claim of the
 *    frame via [weftDraw]; the capsule's [weftGraphicsLayer] block then
 *    reads the same reader-held buffer with a zero-copy, zero-allocation
 *    bulk read ([Weft.rLiveWords] — kernel Law 2). Both consumers observe
 *    the exact same frame: one atomic exchange, no ping-pong staleness.
 *
 *    [controller.frameTick] is the only reactive seam: it is read
 *    exclusively inside draw/graphicsLayer scopes, so each simulation step
 *    invalidates DRAWING ONLY — zero recompositions, zero re-measure, zero
 *    re-layout on the root Scaffold.
 *
 * Zero-allocation draw loop (kernel Law 2):
 *  • The sweep-gradient ring brush, capsule shape, particle paint, ARGB
 *    colors, stroke and radius scalars are remembered ONCE outside the
 *    loop — the 120 Hz path allocates nothing, sustained.
 *  • The capsule layer block reads the pose through a pooled IntArray
 *    ([Weft.rLiveWords] + Float.fromBits) — no ByteBuffer wrappers per frame.
 *  • Text: all strings are Compose [Text] nodes — glyph layout runs once
 *    per CONTENT change in the layout pass with a cached TextLayoutResult.
 *    Paint.measureText() is never called anywhere, in any phase; row/column
 *    layout derives the artist-suffix offset structurally, off-loop.
 */
@Composable
fun QuantumSonicTokenOverlay(
    controller: QuantumSonicTokenController,
    modifier: Modifier = Modifier
) {
    // ── PLANE 2 BINDING ────────────────────────────────────────────────────
    val heddle = remember(controller) { controller.tokenHeddle }

    // Caller-owned pooled pose slot (kernel Law 2: per-frame consumers read
    // through a stable destination — zero wrapper allocation per frame).
    val poseWords = remember(controller) { IntArray(QuantumSonicTokenController.TOKEN_FLOATS) }

    // Haptic seam injection: the controller stays free of android.os.Handler
    // (JVM-unit-testable); the UI host supplies the vibrator call. The
    // controller itself defers it one frame off the impact state mutation.
    DisposableEffect(controller) {
        controller.impactHaptic = { com.streamify.app.util.StreamifyHapticEngine.tokenImpact() }
        onDispose { controller.impactHaptic = null }
    }

    val density = LocalDensity.current

    // ── ZERO-ALLOCATION DRAW RESOURCES (baked once, reused every frame) ────
    val particlePaint = remember { Paint().apply { isAntiAlias = true } }
    val primaryArgb = remember { Primary.toArgb() }
    val activeArgb = remember { ActiveControl.toArgb() }
    val bloomStroke = remember(density) { Stroke(width = with(density) { 4.dp.toPx() }) }
    val bloomRadiusUnit = remember(density) { with(density) { 140.dp.toPx() } }
    val bloomCoreUnit = remember(density) { with(density) { 50.dp.toPx() } }

    val capsuleShape = remember { RoundedCornerShape(16.dp) }
    val capsuleRingBrush = remember {
        Brush.sweepGradient(
            listOf(
                Color.Transparent,
                Primary.copy(alpha = 0.9f),
                ActiveControl,
                Color.White.copy(alpha = 0.8f),
                Color.Transparent
            )
        )
    }

    // ── PRODUCER PACING (VSYNC) ────────────────────────────────────────────
    // Steps only while a flight is live; each step publishes the pose into
    // the Weft channel and ticks the draw-phase invalidation clock.
    LaunchedEffect(Unit) {
        var lastFrameNanos = 0L
        while (isActive) {
            withFrameNanos { frameTimeNanos ->
                if (lastFrameNanos != 0L &&
                    (controller.stage == TokenStage.FLYING || controller.stage == TokenStage.IMPACT)
                ) {
                    val dt = ((frameTimeNanos - lastFrameNanos) / 1_000_000_000f).coerceIn(0.001f, 0.033f)
                    controller.stepSimulation(dt)
                }
                lastFrameNanos = frameTimeNanos
            }
        }
    }

    Box(modifier = modifier.fillMaxSize().zIndex(100f)) {

        // ── IMPACT BLOOM + FLUID PARTICLES (Plane 2, weftDraw consumer) ────
        //
        // Drawn FIRST (zIndex 1 → below the capsule): this pass performs THE
        // claim of the frame — weftDraw exchanges r_work with latest and
        // hands the draw lambda a zero-copy payload-relative view. The
        // lambda's ONLY reactive read is controller.frameTick (a draw-phase
        // read → draw invalidation only, never recomposition).
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .zIndex(1f)
                .weftDraw(heddle) { buf ->
                    val tick = controller.frameTick
                    if (!controller.isRenderable || tick == 0L) return@weftDraw
                    if (buf.getFloat(QuantumSonicTokenController.OFF_IS_IMPACT_BLOOM) < 0.5f) return@weftDraw

                    val p = buf.getFloat(QuantumSonicTokenController.OFF_IMPACT_PROGRESS)
                    val center = if (controller.destination != Offset.Zero) controller.destination
                    else Offset(size.width / 2f, size.height - 100f)

                    drawCircle(
                        color = Primary.copy(alpha = ((1f - p) * 0.85f).coerceIn(0f, 1f)),
                        radius = bloomRadiusUnit * p,
                        center = center,
                        style = bloomStroke
                    )
                    drawCircle(
                        color = Color.White.copy(alpha = ((1f - (p * 1.5f)).coerceIn(0f, 1f)) * 0.9f),
                        radius = bloomCoreUnit * (p * 1.2f).coerceAtMost(1f),
                        center = center
                    )

                    // Particles: preallocated pool (see controller docs —
                    // deliberate non-Weft routing for stateful integrators),
                    // single cached Paint, colors as precomputed ARGB ints.
                    val pbuf = controller.particleBuffer
                    val nc = drawContext.canvas.nativeCanvas
                    var i = 0
                    while (i < controller.particleCount) {
                        val base = i * 6
                        val alpha = pbuf[base + 5]
                        if (alpha > 0.01f) {
                            particlePaint.color = if (i % 2 == 0) primaryArgb else activeArgb
                            particlePaint.alpha = (alpha * 255).toInt().coerceIn(0, 255)
                            nc.drawCircle(pbuf[base], pbuf[base + 1], pbuf[base + 4] * (1f - p * 0.4f), particlePaint)
                        }
                        i++
                    }
                }
        ) {}

        // ── FLYING CAPSULE (Plane 2, weftGraphicsLayer consumer) ───────────
        //
        // The custom canvas matrix drawing (withTransform translate/scale) is
        // replaced by a real graphicsLayer whose transform block defers ALL
        // reads to the draw phase: the block re-executes per VSYNC with zero
        // recomposition, zero re-measure, zero re-layout. The card subtree
        // beneath it is permanently composed and alpha-gated by the channel's
        // visibility flag — nothing enters or leaves composition inside the
        // animation envelope.
        //
        // Drawn SECOND (zIndex 2 → above the bloom): reads the SAME frame the
        // bloom claimed this pass, via the zero-allocation bulk read.
        val cardW = controller.cardWidthPx
        val cardH = controller.cardHeightPx

        Box(
            modifier = Modifier
                .size(
                    width = with(density) { cardW.toDp() },
                    height = with(density) { cardH.toDp() }
                )
                .zIndex(2f)
                .weftGraphicsLayer(heddle) { weft ->
                    // Draw-phase heartbeat: reading frameTick here re-executes
                    // ONLY this layer block on every simulation step.
                    val tick = controller.frameTick
                    if (tick == 0L) {
                        // Null-frame baseline (before the first publish):
                        // hold the capsule fully transparent — the payload of
                        // an unpublished channel is the litmus pattern, not a
                        // valid pose, and must never be drawn.
                        alpha = 0f
                        return@weftGraphicsLayer
                    }

                    // ZERO-ALLOCATION bulk read (kernel Law 2): 14 pose words
                    // into the pooled caller-owned slot, little-endian, no
                    // ByteBuffer wrappers, no boxing, no temporaries.
                    weft.rLiveWords(poseWords)

                    translationX = (Float.fromBits(poseWords[0]) - cardW / 2f).coerceIn(
                        8f,
                        (controller.screenWidthPx - cardW - 8f).coerceAtLeast(8f)
                    )
                    translationY = (Float.fromBits(poseWords[1]) - cardH / 2f).coerceAtLeast(8f)
                    scaleX = Float.fromBits(poseWords[2])
                    scaleY = Float.fromBits(poseWords[3])
                    rotationZ = Math.toDegrees(Float.fromBits(poseWords[4]).toDouble()).toFloat()

                    // 3D tilt (6 DoF): pitch/roll are pure GPU layer rotations
                    // — applied only when the device can afford them.
                    if (controller.enable3D) {
                        rotationX = Float.fromBits(poseWords[6])
                        rotationY = Float.fromBits(poseWords[5])
                    }

                    // Visibility gate (idle → alpha 0 → the layer is skipped
                    // by the renderer entirely) + impact fade-out.
                    val visible = Float.fromBits(poseWords[10])
                    alpha = if (Float.fromBits(poseWords[11]) > 0.5f) {
                        ((1f - Float.fromBits(poseWords[7]) * 1.4f).coerceIn(0f, 1f)) * visible
                    } else {
                        visible
                    }
                }
                .background(BgSurfaceElevated, capsuleShape)
                .border(width = 2.dp, brush = capsuleRingBrush, shape = capsuleShape)
        ) {
            TokenCardContent(controller)
        }
    }
}

/**
 * Plane-1 card content — composed once per flight, re-laid-out only when its
 * own content changes (cold events: trigger, art decode, stage transition).
 * No text measurement happens anywhere in the draw loop: Compose caches each
 * TextLayoutResult, and Row/Column derive the artist-suffix offset
 * structurally instead of measuring string widths per frame.
 */
@Composable
private fun TokenCardContent(controller: QuantumSonicTokenController) {
    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Artwork: pre-decoded software Bitmap, swapped via a single cold
        // state write when the Coil decode completes.
        Box(
            modifier = Modifier
                .size(46.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color.White.copy(alpha = 0.08f))
        ) {
            val bmp = controller.artBitmap
            if (bmp != null) {
                val image = remember(bmp) { bmp.asImageBitmap() }
                Image(
                    bitmap = image,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                // Placeholder ring — static modifiers, zero draw-loop cost.
                Box(
                    Modifier
                        .fillMaxSize()
                        .border(2.dp, Color.White.copy(alpha = 0.18f), CircleShape)
                )
            }
        }

        Spacer(Modifier.width(12.dp))

        Column(Modifier.weight(1f)) {
            Text(
                text = controller.trackTitle,
                color = TextMain,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Row {
                Text(
                    text = if (controller.stage == TokenStage.FLYING) "Connecting…" else "Ready",
                    color = Primary,
                    fontSize = 11.sp
                )
                if (controller.trackArtist.isNotBlank()) {
                    Text(
                        text = " • ${controller.trackArtist}",
                        color = TextSecondary,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}
