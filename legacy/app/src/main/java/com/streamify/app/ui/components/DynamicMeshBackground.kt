package com.streamify.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.streamify.app.ui.theme.BgBase

@Composable
fun DynamicMeshBackground(
    dominantColor: Color,
    accentColor: Color,
    modifier: Modifier = Modifier
) {
    val animDominant by animateColorAsState(
        targetValue = dominantColor,
        animationSpec = tween(durationMillis = 800, easing = LinearEasing),
        label = "DominantColorAnim"
    )

    val animAccent by animateColorAsState(
        targetValue = accentColor,
        animationSpec = tween(durationMillis = 800, easing = LinearEasing),
        label = "AccentColorAnim"
    )

    Canvas(modifier = modifier.fillMaxSize()) {
        val width = size.width
        val height = size.height

        // 1. Deep OLED Base Fill
        drawRect(color = BgBase)

        // 2. Primary Top-Right Ambient Radial Bloom
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    animDominant.copy(alpha = 0.38f),
                    animDominant.copy(alpha = 0.15f),
                    Color.Transparent
                ),
                center = Offset(width * 0.85f, height * 0.20f),
                radius = width * 0.95f
            )
        )

        // 3. Secondary Bottom-Left Dynamic Harmonic Bloom
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    animAccent.copy(alpha = 0.28f),
                    animAccent.copy(alpha = 0.08f),
                    Color.Transparent
                ),
                center = Offset(width * 0.15f, height * 0.75f),
                radius = width * 0.85f
            )
        )

        // 4. Subtle Center Fill Vignette
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(
                    Color.Transparent,
                    BgBase.copy(alpha = 0.65f),
                    BgBase.copy(alpha = 0.92f)
                ),
                startY = 0f,
                endY = height
            )
        )
    }
}
