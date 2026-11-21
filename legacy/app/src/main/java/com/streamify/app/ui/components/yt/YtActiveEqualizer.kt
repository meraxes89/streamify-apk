package com.streamify.app.ui.components.yt
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.streamify.app.ui.theme.ActiveControl

/**
 * Equalizer bars indicator.
 *
 * [isActive] must reflect the *actual* playback state (not just "row is the
 * current track"): when false the bars render as a static frozen frame, which
 * stops the Choreographer animation loop from running while playback is
 * paused.
 */
@Composable
fun YtActiveEqualizer(
    modifier: Modifier = Modifier,
    color: Color = ActiveControl,
    isActive: Boolean = true
) {
    if (isActive) {
        AnimatedEqualizerBars(modifier = modifier, color = color)
    } else {
        Canvas(modifier = modifier.size(16.dp, 16.dp)) {
            val barWidth = size.width / 5f
            val gap = barWidth / 2f
            // Frozen mid-animation silhouette for the paused state.
            val heights = listOf(0.55f, 0.82f, 0.45f)
            heights.forEachIndexed { index, heightFraction ->
                val barHeight = size.height * heightFraction
                val xPos = index * (barWidth + gap)
                val yPos = size.height - barHeight
                drawRoundRect(
                    color = color,
                    topLeft = Offset(x = xPos, y = yPos),
                    size = Size(width = barWidth, height = barHeight),
                    cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f)
                )
            }
        }
    }
}

@Composable
private fun AnimatedEqualizerBars(
    modifier: Modifier,
    color: Color
) {
    val transition = rememberInfiniteTransition(label = "equalizer_transition")

    val bar1 by transition.animateFloat(
        initialValue = 0.2f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(420, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "bar1_height"
    )

    val bar2 by transition.animateFloat(
        initialValue = 0.9f,
        targetValue = 0.3f,
        animationSpec = infiniteRepeatable(
            animation = tween(310, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "bar2_height"
    )

    val bar3 by transition.animateFloat(
        initialValue = 0.4f,
        targetValue = 0.85f,
        animationSpec = infiniteRepeatable(
            animation = tween(480, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "bar3_height"
    )

    Canvas(modifier = modifier.size(16.dp, 16.dp)) {
        val barWidth = size.width / 5f
        val gap = barWidth / 2f
        val heights = listOf(bar1, bar2, bar3)

        heights.forEachIndexed { index, heightFraction ->
            val barHeight = size.height * heightFraction.coerceIn(0.15f, 1f)
            val xPos = index * (barWidth + gap)
            val yPos = size.height - barHeight

            drawRoundRect(
                color = color,
                topLeft = Offset(x = xPos, y = yPos),
                size = Size(width = barWidth, height = barHeight),
                cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f)
            )
        }
    }
}
