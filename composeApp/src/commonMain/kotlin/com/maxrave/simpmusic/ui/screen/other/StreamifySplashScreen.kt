package com.maxrave.simpmusic.ui.screen.other

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.maxrave.simpmusic.ui.component.SireenBrandingBadge
import com.maxrave.simpmusic.ui.theme.LocalIsDarkTheme
import com.maxrave.simpmusic.ui.theme.fontFamily
import org.jetbrains.compose.resources.painterResource
import simpmusic.composeapp.generated.resources.Res
import simpmusic.composeapp.generated.resources.circle_app_icon
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin

/**
 * StreamifySplashScreen
 *
 * Theme-adaptive, GPU-accelerated entrance screen aligned with [MaterialTheme.colorScheme]
 * across light, dark, wallpaper, and custom theme modes.
 */
@Composable
fun StreamifySplashScreen(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val timeline = remember { Animatable(0f) }
    val appFontFamily = fontFamily()

    val isDark = LocalIsDarkTheme.current && MaterialTheme.colorScheme.background.luminance() <= 0.5f
    val colorScheme = MaterialTheme.colorScheme
    val bgColor = colorScheme.background
    val wordmarkColor = colorScheme.onBackground
    val accentColor = colorScheme.primary

    LaunchedEffect(Unit) {
        timeline.animateTo(
            targetValue = 1f,
            animationSpec = tween(
                durationMillis = 1400,
                easing = FastOutSlowInEasing,
            ),
        )
        onDismiss()
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(bgColor)
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = {
                        onDismiss()
                    }
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        // 1. Subtle Theme-Derived Ambient Halo & Pulse Ring (Draw-Phase GPU only)
        Canvas(modifier = Modifier.fillMaxSize()) {
            val p = timeline.value

            // --- Phase 1: Soft Radial Halo (0.05f..0.85f) ---
            if (p in 0.05f..0.85f) {
                val bloomProg = ((p - 0.05f) / 0.70f).coerceIn(0f, 1f)
                val bloomAlpha = (sin(bloomProg * PI).toFloat() * (if (isDark) 0.12f else 0.06f)).coerceIn(0f, 1f)
                val bloomRadius = (160.dp.toPx()) * (0.65f + 0.35f * bloomProg)

                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            accentColor.copy(alpha = bloomAlpha),
                            Color.Transparent,
                        ),
                        center = center,
                        radius = bloomRadius,
                    ),
                    radius = bloomRadius,
                    center = center,
                )
            }

            // --- Phase 2: Single Acoustic Pulse Ring (0.15f..0.85f) ---
            if (p in 0.15f..0.85f) {
                val ringProg = ((p - 0.15f) / 0.65f).coerceIn(0f, 1f)
                val ringAlpha = sin(ringProg * PI).toFloat() * (if (isDark) 0.16f else 0.10f)
                val radius = 52.dp.toPx() + (76.dp.toPx() * ringProg)

                drawCircle(
                    color = accentColor.copy(alpha = ringAlpha),
                    radius = radius,
                    center = center,
                    style = Stroke(width = 1.dp.toPx()),
                )
            }
        }

        // 2. Foreground Brand Lockup
        Column(
            modifier = Modifier.graphicsLayer {
                val p = timeline.value
                if (p > 0.84f) {
                    val exitProg = ((p - 0.84f) / 0.16f).coerceIn(0f, 1f)
                    val exitEase = 1f - (1f - exitProg).pow(2f)
                    alpha = 1f - exitEase
                    scaleX = 1f + (0.03f * exitEase)
                    scaleY = 1f + (0.03f * exitEase)
                }
            },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Hero App Logo
            Image(
                painter = painterResource(Res.drawable.circle_app_icon),
                contentDescription = "Streamify Logo",
                modifier = Modifier
                    .size(86.dp)
                    .clip(CircleShape)
                    .graphicsLayer {
                        val p = timeline.value
                        val logoProg = (p / 0.30f).coerceIn(0f, 1f)
                        val ease = 1f - (1f - logoProg).pow(3f)
                        val s = 0.72f + (0.28f * ease)
                        scaleX = s
                        scaleY = s
                        alpha = logoProg
                    },
            )

            Spacer(modifier = Modifier.height(18.dp))

            // Brand Wordmark
            Text(
                text = "Streamify",
                style = TextStyle(
                    color = wordmarkColor,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.5.sp,
                    fontFamily = appFontFamily,
                ),
                modifier = Modifier.graphicsLayer {
                    val p = timeline.value
                    val textProg = ((p - 0.12f) / 0.25f).coerceIn(0f, 1f)
                    val ease = 1f - (1f - textProg).pow(3f)
                    alpha = ease
                    translationY = (1f - ease) * 10.dp.toPx()
                },
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Studio Signet
            Box(
                modifier = Modifier.graphicsLayer {
                    val p = timeline.value
                    val badgeProg = ((p - 0.22f) / 0.25f).coerceIn(0f, 1f)
                    val ease = 1f - (1f - badgeProg).pow(3f)
                    alpha = ease
                    translationY = (1f - ease) * 8.dp.toPx()
                },
            ) {
                SireenBrandingBadge(
                    prefix = "ENGINEERED BY ",
                    author = "SIREEN",
                    tagline = "INDEPENDENT AUDIO LAB",
                )
            }
        }
    }
}
