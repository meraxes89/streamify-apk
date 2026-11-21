package com.maxrave.simpmusic.ui.component

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.maxrave.simpmusic.ui.theme.LocalIsDarkTheme
import kotlinx.coroutines.launch

/**
 * High-Performance Glassy Shimmering Brand Badge: "DEVELOPED BY SIREEN // STUDIO CORE"
 *
 * Combines an active micro-spectrum acoustic instrument beacon with an industrial milled-glass
 * pill and non-linear chromatic laser shimmer sweep across the glyphs.
 */
@Composable
fun SireenBrandingBadge(
    modifier: Modifier = Modifier,
    tagline: String = "STUDIO CORE",
    onBadgeClick: (() -> Unit)? = null,
) {
    val isDark = LocalIsDarkTheme.current
    val primaryColor = MaterialTheme.colorScheme.primary
    val scope = rememberCoroutineScope()

    // Chromatic laser glint animation with organic deceleration
    val shimmerProgress = remember { Animatable(0f) }
    fun triggerLaserGlint() {
        scope.launch {
            shimmerProgress.snapTo(0f)
            shimmerProgress.animateTo(
                targetValue = 1f,
                animationSpec = tween(
                    durationMillis = 2000,
                    easing = CubicBezierEasing(0.16f, 1f, 0.3f, 1f),
                ),
            )
        }
    }

    LaunchedEffect(Unit) {
        triggerLaserGlint()
    }

    // Micro-spectrum animated equalizer bars
    val infiniteTransition = rememberInfiniteTransition(label = "microSpectrum")
    val bar1Phase by infiniteTransition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.85f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 750, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "bar1",
    )
    val bar2Phase by infiniteTransition.animateFloat(
        initialValue = 0.90f,
        targetValue = 0.30f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 580, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "bar2",
    )
    val bar3Phase by infiniteTransition.animateFloat(
        initialValue = 0.40f,
        targetValue = 1.00f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 890, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "bar3",
    )

    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current

    val prefixColor = if (isDark) Color.White.copy(alpha = 0.70f) else Color.Black.copy(alpha = 0.65f)
    val accentTagColor = if (isDark) Color(0xFF90CAF9).copy(alpha = 0.85f) else Color(0xFF1976D2).copy(alpha = 0.85f)
    val secondaryText = if (isDark) Color(0xFFAAAAAA) else Color(0xFF666666)

    val annotatedText =
        remember(prefixColor, primaryColor, tagline, isDark) {
            buildAnnotatedString {
                withStyle(
                    SpanStyle(
                        color = prefixColor,
                        fontSize = 8.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 1.4.sp,
                    ),
                ) {
                    append("DEVELOPED BY ")
                }
                withStyle(
                    SpanStyle(
                        color = primaryColor,
                        fontSize = 9.5.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 2.0.sp,
                    ),
                ) {
                    append("SIREEN")
                }
                withStyle(
                    SpanStyle(
                        color = prefixColor.copy(alpha = 0.35f),
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Normal,
                    ),
                ) {
                    append(" // ")
                }
                withStyle(
                    SpanStyle(
                        color = accentTagColor,
                        fontSize = 8.0.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.6.sp,
                        fontFamily = FontFamily.Monospace,
                    ),
                ) {
                    append(tagline)
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

    val surfaceBg = if (isDark) Color(0xFF161616).copy(alpha = 0.75f) else Color.White.copy(alpha = 0.90f)
    val borderCol = if (isDark) Color(0xFF333333).copy(alpha = 0.75f) else Color(0xFFE0E0E0).copy(alpha = 0.90f)

    Surface(
        shape = RoundedCornerShape(8.dp),
        color = surfaceBg,
        border = BorderStroke(0.75.dp, borderCol),
        modifier =
            modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable {
                    triggerLaserGlint()
                    onBadgeClick?.invoke()
                },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Concept 1: Micro-Spectrum Acoustic Beacon
            Canvas(modifier = Modifier.size(width = 8.dp, height = 9.dp)) {
                val barW = 1.6.dp.toPx()
                val gap = 1.4.dp.toPx()
                val maxH = size.height

                drawRoundRect(
                    color = primaryColor.copy(alpha = 0.85f),
                    topLeft = Offset(0f, maxH * (1f - bar1Phase)),
                    size = Size(barW, maxH * bar1Phase),
                    cornerRadius = CornerRadius(1.dp.toPx()),
                )
                drawRoundRect(
                    color = Color(0xFF00E5FF),
                    topLeft = Offset(barW + gap, maxH * (1f - bar2Phase)),
                    size = Size(barW, maxH * bar2Phase),
                    cornerRadius = CornerRadius(1.dp.toPx()),
                )
                drawRoundRect(
                    color = primaryColor.copy(alpha = 0.85f),
                    topLeft = Offset((barW + gap) * 2, maxH * (1f - bar3Phase)),
                    size = Size(barW, maxH * bar3Phase),
                    cornerRadius = CornerRadius(1.dp.toPx()),
                )
            }

            Spacer(modifier = Modifier.width(6.dp))

            // Concept 2: Laser Glint Text Sweep
            Canvas(
                modifier = Modifier.size(width = badgeWidthDp, height = badgeHeightDp),
            ) {
                val width = size.width
                val shimmerX = (shimmerProgress.value * (width + 220f)) - 110f

                val sweepBrush =
                    Brush.horizontalGradient(
                        colors =
                            listOf(
                                secondaryText.copy(alpha = 0.40f),
                                if (isDark) Color.White.copy(alpha = 0.90f) else Color.Black.copy(alpha = 0.85f),
                                primaryColor,
                                Color(0xFF00E5FF),
                                if (isDark) Color.White else Color.Black,
                                secondaryText.copy(alpha = 0.40f),
                            ),
                        startX = shimmerX - 70f,
                        endX = shimmerX + 70f,
                    )

                if (shimmerProgress.value < 1f) {
                    drawText(
                        textLayoutResult = textLayout,
                        topLeft = Offset.Zero,
                        brush = sweepBrush,
                    )
                } else {
                    drawText(
                        textLayoutResult = textLayout,
                        topLeft = Offset.Zero,
                    )
                }
            }
        }
    }
}
