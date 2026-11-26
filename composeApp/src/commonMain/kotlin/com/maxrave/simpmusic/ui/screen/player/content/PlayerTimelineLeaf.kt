package com.maxrave.simpmusic.ui.screen.player.content

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.maxrave.domain.data.model.streams.TimeLine
import com.maxrave.simpmusic.extension.hsvToColor
import kotlinx.coroutines.flow.StateFlow

@Stable
internal data class TimelineProgressState(
    val timeline: TimeLine,
    val sliderValue: Float,
    val onValueChange: (Float) -> Unit,
    val onValueChangeFinished: () -> Unit,
)

/**
 * Pure state machine for seek-bar scrubbing and timeline updates.
 * Keeps [sliderValue] synchronously up-to-date so a tap or quick drag-release
 * in the same frame always commits the latest scrub position rather than a
 * pre-recomposition snapshot.
 */
internal class TimelineScrubController(
    initialSliderValue: Float = 0f,
) {
    var isSliding: Boolean = false
        private set

    var sliderValue: Float = initialSliderValue.coerceIn(0f, 100f)
        private set

    fun onTimelineUpdate(timeline: TimeLine): Float {
        if (!isSliding) {
            sliderValue = progressPercentOf(timeline)
        }
        return sliderValue
    }

    fun onValueChange(newValue: Float): Float {
        isSliding = true
        sliderValue = newValue.coerceIn(0f, 100f)
        return sliderValue
    }

    fun onValueChangeFinished(onSeekCommit: (Float) -> Unit): Float {
        isSliding = false
        val target = sliderValue
        onSeekCommit(target)
        return target
    }

    companion object {
        fun progressPercentOf(timeline: TimeLine): Float =
            if (timeline.total > 0L) {
                (timeline.current.toFloat() * 100f / timeline.total.toFloat()).coerceIn(0f, 100f)
            } else {
                0f
            }
    }
}

/**
 * Collects [timelineFlow] locally inside a leaf composable so 50 ms playback ticks
 * do not recompose the player shell or sibling controls.
 */
@Composable
internal fun rememberTimelineProgressState(
    timelineFlow: StateFlow<TimeLine>,
    onSeekCommit: (Float) -> Unit,
): TimelineProgressState {
    val timeline by timelineFlow.collectAsStateWithLifecycle()
    val controller = remember { TimelineScrubController() }
    var sliderValue by rememberSaveable { mutableFloatStateOf(0f) }

    LaunchedEffect(timeline) {
        sliderValue = controller.onTimelineUpdate(timeline)
    }

    val currentOnSeekCommit by rememberUpdatedState(onSeekCommit)

    return remember(timeline, sliderValue) {
        TimelineProgressState(
            timeline = timeline,
            sliderValue = sliderValue,
            onValueChange = { newValue ->
                sliderValue = controller.onValueChange(newValue)
            },
            onValueChangeFinished = {
                controller.onValueChangeFinished(currentOnSeekCommit)
            },
        )
    }
}

/**
 * Crossfade rainbow color cycling gated behind [isCrossfading] inside the leaf progress bar.
 */
@Composable
internal fun rememberCrossfadeTrackColor(
    isCrossfading: Boolean,
    defaultColor: Color = Color.White,
): Color {
    val targetColor =
        if (isCrossfading) {
            val infiniteTransition = rememberInfiniteTransition(label = "crossfadeRainbow")
            val rainbowHue by infiniteTransition.animateFloat(
                initialValue = 0f,
                targetValue = 360f,
                animationSpec =
                    infiniteRepeatable(
                        animation = tween(1000, easing = LinearEasing),
                        repeatMode = RepeatMode.Restart,
                    ),
                label = "rainbowHue",
            )
            hsvToColor(rainbowHue, 1f, 1f)
        } else {
            defaultColor
        }
    val animated by animateColorAsState(
        targetValue = targetColor,
        animationSpec = tween(300),
        label = "sliderCrossfadeColor",
    )
    return animated
}

/**
 * Leaf progress rail that reads [progressProvider] inside [drawBehind], avoiding layout/composition
 * invalidations on every playback tick.
 */
@Composable
internal fun ProgressRail(
    progressProvider: () -> Float,
    color: Color,
    trackColor: Color = Color.Gray.copy(alpha = 0.4f),
    modifier: Modifier = Modifier,
) {
    Spacer(
        modifier =
            modifier.drawBehind {
                val fraction = progressProvider().coerceIn(0f, 1f)
                val radius = CornerRadius(size.height / 2f, size.height / 2f)
                drawRoundRect(
                    color = trackColor,
                    size = size,
                    cornerRadius = radius,
                )
                if (fraction > 0f) {
                    drawRoundRect(
                        color = color,
                        size = Size(width = size.width * fraction, height = size.height),
                        cornerRadius = radius,
                    )
                }
            },
    )
}
