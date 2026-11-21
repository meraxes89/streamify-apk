package com.streamify.app.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import com.streamify.app.ui.theme.ActiveControl
import com.streamify.app.ui.theme.Primary
import com.streamify.app.util.StreamifyHapticEngine

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StreamifyPullToRefreshContainer(
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val state = rememberPullToRefreshState()

    if (state.isRefreshing) {
        LaunchedEffect(true) {
            onRefresh()
        }
    }

    LaunchedEffect(isRefreshing) {
        if (isRefreshing) {
            state.startRefresh()
        } else {
            state.endRefresh()
        }
    }

    val progress = state.progress
    LaunchedEffect(progress) {
        if (progress > 0f) {
            StreamifyHapticEngine.evaluatePull(progress)
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .nestedScroll(state.nestedScrollConnection)
    ) {
        content()

        if (state.progress > 0f || isRefreshing) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 16.dp),
                contentAlignment = Alignment.Center
            ) {
                NeonOrbitalIndicator(
                    progress = state.progress.coerceIn(0f, 1f),
                    isRefreshing = isRefreshing
                )
            }
        }
    }
}

@Composable
fun NeonOrbitalIndicator(
    progress: Float,
    isRefreshing: Boolean,
    modifier: Modifier = Modifier
) {
    if (isRefreshing) {
        RefreshingOrbitalSpinner(modifier = modifier)
    } else {
        PullArcIndicator(progress = progress, modifier = modifier)
    }
}

/**
 * Continuous spinner shown only while a refresh is actually in flight.
 * Owning the infinite transition inside this composable means the
 * Choreographer animation loop only runs while [isRefreshing] is true —
 * not during the whole pull gesture.
 */
@Composable
private fun RefreshingOrbitalSpinner(modifier: Modifier = Modifier) {
    val infiniteTransition = rememberInfiniteTransition(label = "NeonOrbitalTransition")

    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 750, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "NeonRotation"
    )

    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.85f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "NeonPulse"
    )

    Canvas(modifier = modifier.size(42.dp)) {
        val diameter = size.minDimension
        val stroke = diameter / 9f
        val centerOffset = Offset(size.width / 2f, size.height / 2f)

        // 1. Background Track Ring
        drawArc(
            color = Color.White.copy(alpha = 0.08f),
            startAngle = 0f,
            sweepAngle = 360f,
            useCenter = false,
            style = Stroke(width = stroke, cap = StrokeCap.Round)
        )

        // 2. GPU Radial Luminescent Bloom
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    ActiveControl.copy(alpha = 0.45f),
                    Primary.copy(alpha = 0.15f),
                    Color.Transparent
                ),
                center = centerOffset,
                radius = (diameter / 1.5f) * pulseScale
            ),
            radius = (diameter / 1.5f) * pulseScale,
            center = centerOffset
        )

        // 3. Continuous 360° Neon Pulse Spinner
        rotate(rotation, pivot = centerOffset) {
            drawArc(
                brush = Brush.sweepGradient(
                    listOf(
                        Color.Transparent,
                        Primary,
                        ActiveControl
                    )
                ),
                startAngle = 0f,
                sweepAngle = 300f,
                useCenter = false,
                style = Stroke(width = stroke, cap = StrokeCap.Round)
            )
        }
    }
}

/** Kinetic pull-driven arc morph (0° -> 360°). No animation loop —
 *  the arc geometry is a pure function of the pull [progress]. */
@Composable
private fun PullArcIndicator(
    progress: Float,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier.size(42.dp)) {
        val diameter = size.minDimension
        val stroke = diameter / 9f
        val centerOffset = Offset(size.width / 2f, size.height / 2f)

        // Background Track Ring
        drawArc(
            color = Color.White.copy(alpha = 0.08f),
            startAngle = 0f,
            sweepAngle = 360f,
            useCenter = false,
            style = Stroke(width = stroke, cap = StrokeCap.Round)
        )

        val sweep = progress * 360f
        rotate(-90f, pivot = centerOffset) {
            drawArc(
                color = ActiveControl,
                startAngle = 0f,
                sweepAngle = sweep,
                useCenter = false,
                style = Stroke(width = stroke, cap = StrokeCap.Round)
            )
        }
    }
}
