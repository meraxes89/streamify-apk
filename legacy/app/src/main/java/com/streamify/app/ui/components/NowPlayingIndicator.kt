package com.streamify.app.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.streamify.app.ui.theme.StreamifyColors

/**
 * Mini "now playing" equalizer bars.
 *
 * [isActive] must reflect the *actual* playback state: when false the bars
 * render as a static frozen frame so the infinite animation loop is not
 * scheduled while playback is paused.
 */
@Composable
fun NowPlayingIndicator(
    modifier: Modifier = Modifier,
    isActive: Boolean = true
) {
    if (isActive) {
        AnimatedNowPlayingBars(modifier = modifier)
    } else {
        Row(
            modifier = modifier.size(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom
        ) {
            // Frozen mid-animation silhouette for the paused state.
            StaticBar(heightFraction = 0.62f)
            StaticBar(heightFraction = 0.88f)
            StaticBar(heightFraction = 0.50f)
        }
    }
}

@Composable
private fun StaticBar(heightFraction: Float) {
    Box(
        modifier = Modifier
            .width(4.dp)
            .fillMaxHeight(heightFraction)
            .clip(RoundedCornerShape(2.dp))
            .background(StreamifyColors.Primary)
    )
}

@Composable
private fun AnimatedNowPlayingBars(modifier: Modifier) {
    val transition = rememberInfiniteTransition(label = "eq_transition")

    val bar1Height by transition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(400, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "bar1"
    )

    val bar2Height by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.4f,
        animationSpec = infiniteRepeatable(
            animation = tween(300, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "bar2"
    )

    val bar3Height by transition.animateFloat(
        initialValue = 0.5f,
        targetValue = 0.9f,
        animationSpec = infiniteRepeatable(
            animation = tween(500, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "bar3"
    )

    Row(
        modifier = modifier.size(16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Bottom
    ) {
        Box(
            modifier = Modifier
                .width(4.dp)
                .fillMaxHeight(bar1Height)
                .clip(RoundedCornerShape(2.dp))
                .background(StreamifyColors.Primary)
        )
        Box(
            modifier = Modifier
                .width(4.dp)
                .fillMaxHeight(bar2Height)
                .clip(RoundedCornerShape(2.dp))
                .background(StreamifyColors.Primary)
        )
        Box(
            modifier = Modifier
                .width(4.dp)
                .fillMaxHeight(bar3Height)
                .clip(RoundedCornerShape(2.dp))
                .background(StreamifyColors.Primary)
        )
    }
}
