package com.streamify.app.ui.components.yt
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.streamify.app.ui.theme.*
import com.streamify.app.util.DurationFormatter
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Clean, Responsive YouTube Music Style Player Seekbar with Universal Tap & Drag Scrubbing.
 *
 * 120HZ ZERO-JANK CONTRACT:
 * - The hot position flow is collected ONCE into a [State] that is never read
 *   during this composable's composition phase.
 * - The playhead (track line, halo, thumb) is drawn inside [Modifier.drawWithCache]
 *   via a lambda state provider — playback ticks and scrub gestures invalidate
 *   the DRAW PHASE ONLY: no recomposition, no remeasure, no parent invalidation.
 * - The elapsed-time label is a leaf-scoped composable reading quantized
 *   whole seconds, so it recomposes at most 1Hz during playback (and only
 *   itself — never this Column, never the FullPlayerSheet above it).
 */
@Composable
fun YtPlayerSeekBar(
    positionFlow: StateFlow<Long>,
    durationMs: Long,
    onSeek: (Float) -> Unit,
    modifier: Modifier = Modifier,
    activeColor: Color = ActiveControl,
    trackColor: Color = Divider
) {
    val scope = rememberCoroutineScope()
    // Phase 5 haptic engine (falls back to nothing pre-init; scrub ticks
    // bucket to every 5s of scrubbed audio and are throttle-guarded).
    val hapticManager = com.streamify.app.ui.util.HapticFeedbackManager.get()
    // Collected but NOT read in this composition scope — reads are deferred
    // into the draw lambda and the leaf time-label below.
    val positionState = positionFlow.collectAsState()
    val isDragging = remember { mutableStateOf(false) }
    val dragPositionMs = remember { mutableLongStateOf(0L) }
    val latchedPositionMs = remember { mutableStateOf<Long?>(null) }
    val currentOnSeek by rememberUpdatedState(onSeek)

    // Clear UI latch when external playback position converges near the target
    LaunchedEffect(Unit) {
        snapshotFlow { positionState.value }.collect { live ->
            latchedPositionMs.value?.let { latched ->
                if (kotlin.math.abs(live - latched) < 400L) {
                    latchedPositionMs.value = null
                }
            }
        }
    }

    val totalDuration = if (durationMs > 0L) durationMs else 1000L

    // ── Lambda state provider: every State read below happens inside the
    // DRAW phase. 5Hz playback ticks re-execute only the draw lambda.
    val playheadFractionProvider: () -> Float = {
        val display = when {
            isDragging.value -> dragPositionMs.value
            latchedPositionMs.value != null -> latchedPositionMs.value ?: positionState.value
            else -> positionState.value
        }
        (display.toFloat() / totalDuration.toFloat()).coerceIn(0f, 1f)
    }

    // Hardware-Accelerated Animatable Thumb Physics
    val thumbScale = remember { Animatable(1f) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onPress = {
                            scope.launch {
                                thumbScale.animateTo(
                                    targetValue = 1.8f,
                                    animationSpec = spring(
                                        dampingRatio = Spring.DampingRatioMediumBouncy,
                                        stiffness = Spring.StiffnessMedium
                                    )
                                )
                            }
                            tryAwaitRelease()
                            scope.launch {
                                thumbScale.animateTo(
                                    targetValue = 1f,
                                    animationSpec = spring(
                                        dampingRatio = Spring.DampingRatioMediumBouncy,
                                        stiffness = Spring.StiffnessMedium
                                    )
                                )
                            }
                        },
                        onTap = { offset ->
                            val targetFrac = (offset.x / size.width.toFloat()).coerceIn(0f, 1f)
                            val targetMs = (targetFrac * totalDuration).toLong()
                            latchedPositionMs.value = targetMs
                            hapticManager?.onScrubPositionChanged(targetMs)
                            hapticManager?.resetBuckets()
                            currentOnSeek(targetFrac)
                        }
                    )
                }
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            isDragging.value = true
                            val startFrac = (offset.x / size.width.toFloat()).coerceIn(0f, 1f)
                            dragPositionMs.value = (startFrac * totalDuration).toLong()
                            latchedPositionMs.value = dragPositionMs.value
                            hapticManager?.resetBuckets()
                            hapticManager?.onScrubPositionChanged(dragPositionMs.value)
                            scope.launch {
                                thumbScale.animateTo(
                                    targetValue = 2.0f,
                                    animationSpec = spring(
                                        dampingRatio = Spring.DampingRatioMediumBouncy,
                                        stiffness = Spring.StiffnessMedium
                                    )
                                )
                            }
                        },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            val curFrac = (dragPositionMs.value.toFloat() / totalDuration.toFloat()).coerceIn(0f, 1f)
                            val newFrac = (curFrac + (dragAmount.x / size.width.toFloat())).coerceIn(0f, 1f)
                            dragPositionMs.value = (newFrac * totalDuration).toLong()
                            // Phase 5 — micro-tick pulses every 5 seconds of
                            // audio scrubbed (bucketed + throttled), replacing
                            // the legacy 30-step pixel detents.
                            hapticManager?.onScrubPositionChanged(dragPositionMs.value)
                            latchedPositionMs.value = dragPositionMs.value
                        },
                        onDragEnd = {
                            val finalTargetMs = dragPositionMs.value
                            val finalFrac = (finalTargetMs.toFloat() / totalDuration.toFloat()).coerceIn(0f, 1f)
                            latchedPositionMs.value = finalTargetMs
                            isDragging.value = false
                            hapticManager?.resetBuckets()
                            currentOnSeek(finalFrac)
                            scope.launch {
                                thumbScale.animateTo(
                                    targetValue = 1f,
                                    animationSpec = spring(
                                        dampingRatio = Spring.DampingRatioMediumBouncy,
                                        stiffness = Spring.StiffnessMedium
                                    )
                                )
                            }
                        },
                        onDragCancel = {
                            isDragging.value = false
                            latchedPositionMs.value = null
                            scope.launch {
                                thumbScale.animateTo(
                                    targetValue = 1f,
                                    animationSpec = spring(
                                        dampingRatio = Spring.DampingRatioMediumBouncy,
                                        stiffness = Spring.StiffnessMedium
                                    )
                                )
                            }
                        }
                    )
                }
                // Draw-phase-only playhead: State reads (position, drag,
                // latch, thumb scale) happen inside onDrawBehind — ticks and
                // scrubs never recompose or remeasure this node's parents.
                .drawWithCache {
                    val trackHeightPx = 3.dp.toPx()
                    val thumbBaseRadius = 5.dp.toPx()
                    val haloBaseRadius = 16.dp.toPx()
                    onDrawBehind {
                        val yPos = size.height / 2

                        // Background Inactive Track
                        drawLine(
                            color = trackColor,
                            start = Offset(0f, yPos),
                            end = Offset(size.width, yPos),
                            strokeWidth = trackHeightPx,
                            cap = StrokeCap.Round
                        )

                        // Active Progress Track (lambda state provider read)
                        val fraction = playheadFractionProvider()
                        val activeEndX = size.width * fraction
                        if (activeEndX > 0f) {
                            drawLine(
                                color = activeColor,
                                start = Offset(0f, yPos),
                                end = Offset(activeEndX, yPos),
                                strokeWidth = trackHeightPx,
                                cap = StrokeCap.Round
                            )
                        }

                        // Ambient Halo Glow when dragging
                        if (thumbScale.value > 1.2f) {
                            drawCircle(
                                color = activeColor.copy(alpha = 0.25f),
                                radius = haloBaseRadius * (thumbScale.value / 2.0f),
                                center = Offset(activeEndX, yPos)
                            )
                        }

                        // Spring Magnified Thumb
                        drawCircle(
                            color = activeColor,
                            radius = thumbBaseRadius * thumbScale.value,
                            center = Offset(activeEndX, yPos)
                        )
                    }
                }
        )

        Spacer(modifier = Modifier.height(2.dp))

        // Time Labels — leaf-scoped: the ONLY nodes in this seekbar that
        // recompose for playback ticks, and only when the whole second flips.
        SeekTimeLabels(
            positionState = positionState,
            isDragging = isDragging,
            dragPositionMs = dragPositionMs,
            latchedPositionMs = latchedPositionMs,
            totalDuration = totalDuration
        )
    }
}

/**
 * Leaf time labels. Reads the position [State] internally and derives a
 * whole-second readout via [derivedStateOf]: recomposition is scoped to THIS
 * Row only, fires at most once per second during playback, and per drag
 * event while scrubbing (a user-driven rate). The parent seekbar and the
 * sheet above it stay composition-silent.
 */
@Composable
private fun SeekTimeLabels(
    positionState: State<Long>,
    isDragging: State<Boolean>,
    dragPositionMs: State<Long>,
    latchedPositionMs: State<Long?>,
    totalDuration: Long
) {
    val displaySeconds by remember(totalDuration) {
        derivedStateOf {
            val display = when {
                isDragging.value -> dragPositionMs.value
                latchedPositionMs.value != null -> latchedPositionMs.value ?: positionState.value
                else -> positionState.value
            }
            (display / 1000L).coerceIn(0L, totalDuration / 1000L)
        }
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = DurationFormatter.formatMs(displaySeconds * 1000L),
            style = LocalAppTypography.current.seekbarTime,
            color = TextSecondary
        )
        Text(
            text = DurationFormatter.formatMs(totalDuration),
            style = LocalAppTypography.current.seekbarTime,
            color = TextSecondary
        )
    }
}
