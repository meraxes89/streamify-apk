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
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.highlight.Highlight
import com.maxrave.simpmusic.expect.ui.PlatformBackdrop
import com.maxrave.simpmusic.ui.theme.LocalIsDarkTheme
import com.maxrave.simpmusic.ui.theme.LocalLiquidGlassEnabled
import com.maxrave.simpmusic.ui.theme.fontFamily
import kotlinx.coroutines.launch

/**
 * High-Performance Brand Badge: "ENGINEERED BY SIREEN"
 *
 * Designed with luxury studio aesthetic:
 * - Liquid-glass shader support with backdrop refraction and specular rim highlight.
 * - Graceful translucent fallback for low-spec or disabled glass modes.
 * - Continuous pill curvature matching the top bar glass islands.
 * - High-contrast theme adaptation (never washes out in light or dark mode).
 * - Subtle micro-spectrum acoustic instrument beacon (non-distracting).
 * - Compact mode support for mobile single-row app bars to prevent horizontal overcrowding.
 */
@Composable
fun SireenBrandingBadge(
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    backdrop: PlatformBackdrop? = null,
    prefix: String = "DEVELOPED BY ",
    author: String = "SIREEN",
    tagline: String = "",
    onBadgeClick: (() -> Unit)? = null,
) {
    val brandFont = fontFamily()
    val isDark = LocalIsDarkTheme.current && MaterialTheme.colorScheme.background.luminance() <= 0.5f
    val primaryColor = MaterialTheme.colorScheme.primary
    val scope = rememberCoroutineScope()
    val glassOn = LocalLiquidGlassEnabled.current && backdrop != null

    // Interactive glint on click
    val glintAlpha = remember { Animatable(0f) }
    fun triggerInteractiveGlint() {
        scope.launch {
            glintAlpha.snapTo(0.35f)
            glintAlpha.animateTo(
                targetValue = 0f,
                animationSpec = tween(durationMillis = 600, easing = CubicBezierEasing(0.16f, 1f, 0.3f, 1f)),
            )
        }
    }

    // Micro-spectrum animated equalizer bars (gentle, non-distracting)
    val infiniteTransition = rememberInfiniteTransition(label = "microSpectrum")
    val bar1Phase by infiniteTransition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.85f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "bar1",
    )
    val bar2Phase by infiniteTransition.animateFloat(
        initialValue = 0.80f,
        targetValue = 0.35f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 720, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "bar2",
    )
    val bar3Phase by infiniteTransition.animateFloat(
        initialValue = 0.40f,
        targetValue = 0.95f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1050, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "bar3",
    )

    // Pill geometry matching CreateActions / ChipGroup
    val pillShape = CircleShape
    val colorScheme = MaterialTheme.colorScheme

    // Adaptive border and container colors matching mood pills (ChipGroup)
    val borderColor =
        if (isDark) {
            if (glassOn) Color.White.copy(alpha = 0.12f) else colorScheme.outlineVariant
        } else {
            Color.Black.copy(alpha = 0.14f)
        }

    val containerColor =
        if (isDark) {
            if (glassOn) Color.Transparent else colorScheme.surfaceContainer.copy(alpha = 0.5f)
        } else {
            Color.Black.copy(alpha = 0.04f)
        }

    // Text & Beacon Colors
    val prefixColor = if (isDark) Color(0xFF94A3B8) else Color(0xFF475569)
    val authorColor = MaterialTheme.colorScheme.onSurface
    val beaconColor1 = primaryColor.copy(alpha = 0.85f)
    val beaconColor2 = if (isDark) Color(0xFF00E5FF).copy(alpha = 0.85f) else Color(0xFF0284C7).copy(alpha = 0.85f)

    // Mini TM-Style Tagline Colors (only when tagline is provided)
    val tmBadgeBg = if (isDark) primaryColor.copy(alpha = 0.16f) else primaryColor.copy(alpha = 0.10f)
    val tmBadgeBorder = if (isDark) primaryColor.copy(alpha = 0.38f) else primaryColor.copy(alpha = 0.28f)
    val tmBadgeTextColor = if (isDark) Color(0xFF93C5FD) else Color(0xFF1D4ED8)

    val badgeContent: @Composable () -> Unit = {
        Row(
            modifier = Modifier.padding(
                horizontal = if (compact) 9.dp else 12.dp,
                vertical = if (compact) 5.dp else 6.5.dp,
            ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 1. Micro-Spectrum Acoustic Beacon
            Canvas(modifier = Modifier.size(width = 8.dp, height = 8.5.dp)) {
                val barW = 1.5.dp.toPx()
                val gap = 1.3.dp.toPx()
                val maxH = size.height

                drawRoundRect(
                    color = beaconColor1,
                    topLeft = Offset(0f, maxH * (1f - bar1Phase)),
                    size = Size(barW, maxH * bar1Phase),
                    cornerRadius = CornerRadius(1.dp.toPx()),
                )
                drawRoundRect(
                    color = beaconColor2,
                    topLeft = Offset(barW + gap, maxH * (1f - bar2Phase)),
                    size = Size(barW, maxH * bar2Phase),
                    cornerRadius = CornerRadius(1.dp.toPx()),
                )
                drawRoundRect(
                    color = beaconColor1,
                    topLeft = Offset((barW + gap) * 2, maxH * (1f - bar3Phase)),
                    size = Size(barW, maxH * bar3Phase),
                    cornerRadius = CornerRadius(1.dp.toPx()),
                )
            }

            Spacer(modifier = Modifier.width(if (compact) 4.5.dp else 5.dp))

            // 2. Prefix: "ENGINEERED BY " (omitted in compact mode)
            if (!compact && prefix.isNotBlank()) {
                Text(
                    text = prefix,
                    style = TextStyle(
                        color = prefixColor,
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Medium,
                        letterSpacing = 0.8.sp,
                        fontFamily = brandFont,
                    ),
                )
            }

            // 3. Author Name: "SIREEN"
            Text(
                text = author,
                style = TextStyle(
                    color = authorColor,
                    fontSize = if (compact) 8.5.sp else 9.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = if (compact) 1.2.sp else 1.4.sp,
                    fontFamily = brandFont,
                ),
            )

            // 4. Optional Studio Tagline (only shown when explicitly provided, omitted in compact mode)
            if (!compact && tagline.isNotBlank()) {
                Spacer(modifier = Modifier.width(4.5.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(3.5.dp))
                        .background(tmBadgeBg)
                        .border(0.5.dp, tmBadgeBorder, RoundedCornerShape(3.5.dp))
                        .padding(horizontal = 3.5.dp, vertical = 1.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = tagline,
                        style = TextStyle(
                            color = tmBadgeTextColor,
                            fontSize = 6.2.sp,
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = 0.6.sp,
                            fontFamily = brandFont,
                        ),
                        maxLines = 1,
                    )
                }
            }
        }
    }

    val shapeModifier =
        if (glassOn && backdrop != null) {
            Modifier.liquidGlass(
                backdrop = backdrop,
                shape = pillShape,
                highlight = Highlight(width = 1.dp),
            )
        } else {
            Modifier.clip(pillShape)
        }

    Row(
        modifier =
            modifier
                .then(shapeModifier)
                .background(containerColor, pillShape)
                .border(1.dp, borderColor, pillShape)
                .clip(pillShape)
                .clickable {
                    triggerInteractiveGlint()
                    onBadgeClick?.invoke()
                },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        badgeContent()
    }
}
