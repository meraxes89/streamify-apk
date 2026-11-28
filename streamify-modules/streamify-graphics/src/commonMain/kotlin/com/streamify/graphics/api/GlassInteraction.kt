package com.streamify.graphics.api

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.positionChange
import com.streamify.graphics.tokens.PrimitiveTokens
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Press/hold state holder for a single Sovereign glass surface — the S24
 * Track C port of Kyant's catalog interaction, mirrored from the reference
 * implementation in
 * `composeApp/.../ui/component/LiquidGlassContainer.kt`.
 *
 * Project-local and dependency-light by design: instead of the
 * library-internal AGSL highlight shader, the facade drives a plain
 * `Brush.radialGradient` glow from [touchPosition] and the
 * spring-animated [pressProgress] (the same signal scales the surface —
 * the "liquid" press swell). The drag detection is observe-only, so
 * wrapped buttons keep receiving their own clicks.
 *
 * Spring constants are the Kyant press spec, frozen into
 * [com.streamify.graphics.tokens.PrimitiveTokens]: damping ratio 0.5
 * (underdamped — the surface overshoots slightly), stiffness 300,
 * visibility threshold 0.001.
 */
class GlassInteraction(
    private val animationScope: CoroutineScope,
) {
    private val pressSpec = spring(
        dampingRatio = PrimitiveTokens.pressSpringDampingRatio,
        stiffness = PrimitiveTokens.pressSpringStiffness,
        visibilityThreshold = PRESS_SPRING_VISIBILITY_THRESHOLD,
    )
    private val pressAnimation = Animatable(0f, PRESS_SPRING_VISIBILITY_THRESHOLD)

    /** 0f at rest, animating to 1f while pressed. Read in draw/effect/layer blocks. */
    val pressProgress: Float get() = pressAnimation.value

    /** Local-space touch point used as the centre of the press glow. */
    var touchPosition by mutableStateOf(Offset.Zero)
        private set

    suspend fun detectPress(pointer: PointerInputScope) =
        with(pointer) {
            inspectDragGestures(
                onDragStart = { down ->
                    touchPosition = down.position
                    animationScope.launch { pressAnimation.animateTo(1f, pressSpec) }
                },
                onDragEnd = { animationScope.launch { pressAnimation.animateTo(0f, pressSpec) } },
                onDragCancel = { animationScope.launch { pressAnimation.animateTo(0f, pressSpec) } },
            ) { change, _ ->
                touchPosition = change.position
            }
        }

    private companion object {
        private const val PRESS_SPRING_VISIBILITY_THRESHOLD = 0.001f
    }
}

/**
 * Remembers one [GlassInteraction] per composition site, backed by the
 * composition's coroutine scope. Used by
 * [Modifier.streamifyInteractiveGlass][streamifyInteractiveGlass]; pass the
 * returned instance to [Modifier.streamifyGlass] directly for custom wiring.
 */
@Composable
fun rememberGlassInteraction(): GlassInteraction {
    val scope = rememberCoroutineScope()
    return remember(scope) { GlassInteraction(scope) }
}

/**
 * Observe-only drag/press recogniser ported from Kyant's catalog
 * `DragGestureInspector` (same shape as the composeApp reference). It never
 * consumes events, so a glass surface can react to a press while the
 * buttons it wraps still handle their own taps.
 */
internal suspend fun PointerInputScope.inspectDragGestures(
    onDragStart: (down: PointerInputChange) -> Unit = {},
    onDragEnd: (change: PointerInputChange) -> Unit = {},
    onDragCancel: () -> Unit = {},
    onDrag: (change: PointerInputChange, dragAmount: Offset) -> Unit,
) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)

        val down = awaitFirstDown(requireUnconsumed = false)

        onDragStart(down)
        onDrag(down, Offset.Zero)
        val upEvent =
            drag(
                pointerId = down.id,
                onDrag = { onDrag(it, it.positionChange()) },
            )
        if (upEvent == null) {
            onDragCancel()
        } else {
            onDragEnd(upEvent)
        }
    }
}

private suspend inline fun AwaitPointerEventScope.drag(
    pointerId: PointerId,
    onDrag: (PointerInputChange) -> Unit,
): PointerInputChange? {
    val isPointerUp = currentEvent.changes.firstOrNull { it.id == pointerId }?.pressed != true
    if (isPointerUp) {
        return null
    }
    var pointer = pointerId
    while (true) {
        val change = awaitDragOrUp(pointer) ?: return null
        if (change.isConsumed) {
            return null
        }
        if (change.changedToUpIgnoreConsumed()) {
            return change
        }
        onDrag(change)
        pointer = change.id
    }
}

private suspend inline fun AwaitPointerEventScope.awaitDragOrUp(pointerId: PointerId): PointerInputChange? {
    var pointer = pointerId
    while (true) {
        val event = awaitPointerEvent()
        val dragEvent = event.changes.firstOrNull { it.id == pointer } ?: return null
        if (dragEvent.changedToUpIgnoreConsumed()) {
            val otherDown = event.changes.firstOrNull { it.pressed }
            if (otherDown == null) {
                return dragEvent
            } else {
                pointer = otherDown.id
            }
        } else {
            val hasDragged = dragEvent.previousPosition != dragEvent.position
            if (hasDragged) {
                return dragEvent
            }
        }
    }
}
