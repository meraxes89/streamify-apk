package com.maxrave.simpmusic.ui.component

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.maxrave.simpmusic.ui.theme.LocalIsDarkTheme

/**
 * High-Performance Glassy Shimmering Brand Badge: "DEVELOPED BY SIREEN"
 *
 * Transferred from legacy Streamify with dynamic Light/Dark theme adaptation and
 * smooth chromatic laser shimmer sweep across the character glyphs.
 */
@Composable
fun SireenBrandingBadge(
    modifier: Modifier = Modifier,
) {
    val isDark = LocalIsDarkTheme.current
    val primaryColor = MaterialTheme.colorScheme.primary

    val shimmerProgress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        shimmerProgress.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = 2800, easing = LinearEasing),
        )
    }

    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current

    val prefixColor = if (isDark) Color.White.copy(alpha = 0.75f) else Color.Black.copy(alpha = 0.70f)
    val secondaryText = if (isDark) Color(0xFFAAAAAA) else Color(0xFF666666)

    val annotatedText =
        remember(prefixColor, primaryColor) {
            buildAnnotatedString {
                withStyle(
                    SpanStyle(
                        color = prefixColor,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.6.sp,
                    ),
                ) {
                    append("DEVELOPED BY ")
                }
                withStyle(
                    SpanStyle(
                        color = primaryColor,
                        fontSize = 9.5.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 2.2.sp,
                    ),
                ) {
                    append("SIREEN")
                }
            }
        }

    val textLayout =
        remember(annotatedText) {
            textMeasurer.measure(
                text = annotatedText,
                style = TextStyle(),
            )
        }

    val badgeWidthDp = with(density) { textLayout.size.width.toDp() }
    val badgeHeightDp = with(density) { textLayout.size.height.toDp() }

    val surfaceBg = if (isDark) Color(0xFF212121).copy(alpha = 0.65f) else Color.White.copy(alpha = 0.85f)
    val borderCol = if (isDark) Color(0xFF383838).copy(alpha = 0.55f) else Color(0xFFE0E0E0).copy(alpha = 0.85f)

    Surface(
        shape = RoundedCornerShape(10.dp),
        color = surfaceBg,
        border = BorderStroke(0.8.dp, borderCol),
        modifier = modifier,
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.5.dp),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(
                modifier = Modifier.size(width = badgeWidthDp, height = badgeHeightDp),
            ) {
                val width = size.width
                val shimmerX = (shimmerProgress.value * (width + 200f)) - 100f

                val sweepBrush =
                    Brush.horizontalGradient(
                        colors =
                            listOf(
                                secondaryText.copy(alpha = 0.50f),
                                if (isDark) Color.White.copy(alpha = 0.90f) else Color.Black.copy(alpha = 0.85f),
                                primaryColor,
                                Color(0xFF00E5FF),
                                if (isDark) Color.White else Color.Black,
                                secondaryText.copy(alpha = 0.50f),
                            ),
                        startX = shimmerX - 70f,
                        endX = shimmerX + 70f,
                    )

                if (shimmerProgress.value < 1f) {
                    // Shimmer sweep in flight: paint glyphs with the moving gradient.
                    drawText(
                        textLayoutResult = textLayout,
                        topLeft = Offset.Zero,
                        brush = sweepBrush,
                    )
                } else {
                    // Resting state: branded colors from the annotated string.
                    drawText(
                        textLayoutResult = textLayout,
                        topLeft = Offset.Zero,
                    )
                }
            }
        }
    }
}
