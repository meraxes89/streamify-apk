package com.streamify.app.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

@Composable
fun FluidSyllableLine(
    text: String,
    isActive: Boolean,
    progressFraction: Float, // 0.0f to 1.0f sweep progress
    modifier: Modifier = Modifier,
    activeColor: Color = Color.White,
    inactiveColor: Color = Color.White.copy(alpha = 0.35f),
    glowColor: Color = MaterialTheme.colorScheme.primary
) {
    val activeStyle = TextStyle(
        fontSize = 24.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = (-0.5).sp
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer {
                // Phase 3 GPU Isolation: Zero recomposition during sweeps
                compositingStrategy = CompositingStrategy.Offscreen
                scaleX = if (isActive) 1.02f else 1.0f
                scaleY = if (isActive) 1.02f else 1.0f
            }
            .drawWithContent {
                // 1. Draw base inactive/dimmed text layer
                drawContent()

                // 2. Draw glowing active sweep layer with feathered gradient edge
                if (isActive && progressFraction > 0f) {
                    val sweepWidthPx = size.width * progressFraction.coerceIn(0f, 1f)
                    val featherPx = 18f

                    clipRect(
                        left = 0f,
                        top = 0f,
                        right = sweepWidthPx,
                        bottom = size.height
                    ) {
                        this@drawWithContent.drawContent()

                        // 3. Draw horizontal dynamic glow bloom leading the sweep edge
                        drawRect(
                            brush = Brush.horizontalGradient(
                                0.0f to Color.Transparent,
                                0.7f to glowColor.copy(alpha = 0.45f),
                                1.0f to glowColor.copy(alpha = 0.85f),
                                startX = (sweepWidthPx - featherPx).coerceAtLeast(0f),
                                endX = sweepWidthPx
                            ),
                            topLeft = Offset((sweepWidthPx - featherPx).coerceAtLeast(0f), 0f),
                            size = Size(featherPx, size.height),
                            blendMode = BlendMode.SrcAtop
                        )
                    }
                }
            }
    ) {
        Text(
            text = text,
            style = activeStyle,
            color = if (isActive) activeColor else inactiveColor
        )
    }
}
