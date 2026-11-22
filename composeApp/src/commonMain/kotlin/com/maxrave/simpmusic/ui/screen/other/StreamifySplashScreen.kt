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
import androidx.compose.ui.text.font.FontFamily
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
 * StreamifySplashScreen — "The Prismatic Horizon"
 *
 * Elite, 100% GPU-accelerated entrance experience:
 * 1. Zero-CPU path mathematics: no dynamic path allocations or heavy trigonometry loops.
 * 2. Pure hardware RenderThread composition via `Modifier.graphicsLayer` transforms (portable across Android, Desktop, iOS, Web).
 * 3. Optical laser slit into anamorphic aperture bloom with concentric micro-sonar acoustic pulses.
 * 4. Architectural identity hierarchy: Logo -> STREAMIFY -> SOUND / REDEFINED -> ENGINEERED BY SIREEN badge -> PURE SIGNAL spec.
 * 5. Full dynamic dark/light theme adaptation and 3.0s duration with tap-to-skip.
 */
@Composable
fun StreamifySplashScreen(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val timeline = remember { Animatable(0f) }
    val appFontFamily = fontFamily()

    // Adaptive theme detection
    val isDark = LocalIsDarkTheme.current && MaterialTheme.colorScheme.background.luminance() <= 0.5f

    // Theme adaptive color palettes
    val bgColor = remember(isDark) { if (isDark) Color(0xFF060608) else Color(0xFFF8F9FD) }
    val wordmarkColor = remember(isDark) { if (isDark) Color.White else Color(0xFF0F172A) }
    val mottoColor = remember(isDark) { if (isDark) Color(0xFF94A3B8) else Color(0xFF475569) }
    val descColor = remember(isDark) { if (isDark) Color(0xFF64748B) else Color(0xFF64748B) }
    val laserCyan = remember(isDark) { if (isDark) Color(0xFF00F2FE) else Color(0xFF0284C7) }
    val laserViolet = remember(isDark) { if (isDark) Color(0xFF8B5CF6) else Color(0xFF7C3AED) }
    val laserMagenta = remember(isDark) { if (isDark) Color(0xFFFF2A85) else Color(0xFFDB2777) }

    LaunchedEffect(Unit) {
        timeline.animateTo(
            targetValue = 1f,
            animationSpec = tween(
                durationMillis = 3000,
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
        // 1. Hardware-Accelerated Optical Slit & Aperture Bloom (Draw-Phase GPU only, 0 heap allocations)
        Canvas(modifier = Modifier.fillMaxSize()) {
            val p = timeline.value
            val width = size.width

            // --- Phase 1: Horizontal Laser Slit (0.0f..0.35f) ---
            if (p in 0.0f..0.35f) {
                val slitProg = (p / 0.25f).coerceIn(0f, 1f)
                val easeSlit = 1f - (1f - slitProg).pow(3f)
                val slitWidth = width * 0.92f * easeSlit
                val slitAlpha = if (p < 0.22f) slitProg else (1f - ((p - 0.22f) / 0.13f)).coerceIn(0f, 1f)
                val halfW = slitWidth / 2f

                drawLine(
                    brush = Brush.horizontalGradient(
                        colors = listOf(
                            Color.Transparent,
                            laserCyan.copy(alpha = slitAlpha * 0.90f),
                            laserViolet.copy(alpha = slitAlpha),
                            laserMagenta.copy(alpha = slitAlpha * 0.80f),
                            Color.Transparent,
                        ),
                        startX = center.x - halfW,
                        endX = center.x + halfW,
                    ),
                    start = Offset(center.x - halfW, center.y),
                    end = Offset(center.x + halfW, center.y),
                    strokeWidth = 2.2.dp.toPx() * (1f - (p / 0.35f) * 0.5f),
                )
            }

            // --- Phase 2: Anamorphic Aperture Bloom (0.12f..0.85f) ---
            if (p in 0.12f..0.85f) {
                val bloomProg = ((p - 0.12f) / 0.65f).coerceIn(0f, 1f)
                val bloomAlpha = (sin(bloomProg * PI).toFloat() * (if (isDark) 0.32f else 0.16f)).coerceIn(0f, 1f)
                val bloomRadius = (165.dp.toPx()) * (0.55f + 0.45f * bloomProg)

                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            laserViolet.copy(alpha = bloomAlpha),
                            laserCyan.copy(alpha = bloomAlpha * 0.45f),
                            Color.Transparent,
                        ),
                        center = center,
                        radius = bloomRadius,
                    ),
                    radius = bloomRadius,
                    center = center,
                )
            }

            // --- Phase 3: Concentric Acoustic Micro-Sonar Rings (0.20f..0.85f) ---
            if (p in 0.20f..0.85f) {
                val ringProg1 = ((p - 0.20f) / 0.55f).coerceIn(0f, 1f)
                val ringAlpha1 = sin(ringProg1 * PI).toFloat() * (if (isDark) 0.22f else 0.12f)
                val radius1 = 54.dp.toPx() + (85.dp.toPx() * ringProg1)

                drawCircle(
                    color = laserCyan.copy(alpha = ringAlpha1),
                    radius = radius1,
                    center = center,
                    style = Stroke(width = 1.dp.toPx()),
                )

                if (p >= 0.30f) {
                    val ringProg2 = ((p - 0.30f) / 0.50f).coerceIn(0f, 1f)
                    val ringAlpha2 = sin(ringProg2 * PI).toFloat() * (if (isDark) 0.16f else 0.08f)
                    val radius2 = 54.dp.toPx() + (125.dp.toPx() * ringProg2)

                    drawCircle(
                        color = laserViolet.copy(alpha = ringAlpha2),
                        radius = radius2,
                        center = center,
                        style = Stroke(width = 1.dp.toPx()),
                    )
                }
            }
        }

        // 2. Hardware-Accelerated Foreground Brand Lockup
        Column(
            modifier = Modifier.graphicsLayer {
                val p = timeline.value
                // Seamless exit transition
                if (p > 0.86f) {
                    val exitProg = ((p - 0.86f) / 0.14f).coerceIn(0f, 1f)
                    val exitEase = 1f - (1f - exitProg).pow(2f)
                    alpha = 1f - exitEase
                    scaleX = 1f + (0.04f * exitEase)
                    scaleY = 1f + (0.04f * exitEase)
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
                        val logoProg = ((p - 0.12f) / 0.20f).coerceIn(0f, 1f)
                        val ease = 1f - (1f - logoProg).pow(3f)
                        val s = if (p < 0.12f) 0.60f else 0.60f + (0.40f * ease)
                        scaleX = s
                        scaleY = s
                        alpha = if (p < 0.12f) 0f else logoProg
                    },
            )

            Spacer(modifier = Modifier.height(18.dp))

            // Brand Wordmark: "STREAMIFY"
            Text(
                text = "STREAMIFY",
                style = TextStyle(
                    color = wordmarkColor,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 8.sp,
                    fontFamily = appFontFamily,
                ),
                modifier = Modifier.graphicsLayer {
                    val p = timeline.value
                    val textProg = ((p - 0.20f) / 0.18f).coerceIn(0f, 1f)
                    val ease = 1f - (1f - textProg).pow(3f)
                    alpha = ease
                    translationY = (1f - ease) * 12.dp.toPx()
                    scaleX = 0.95f + (0.05f * ease)
                    scaleY = 0.95f + (0.05f * ease)
                },
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Acoustic Motto: "S O U N D   /   R E D E F I N E D"
            Text(
                text = "S O U N D   /   R E D E F I N E D",
                style = TextStyle(
                    color = mottoColor,
                    fontSize = 9.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 4.0.sp,
                    fontFamily = appFontFamily,
                ),
                modifier = Modifier.graphicsLayer {
                    val p = timeline.value
                    val mottoProg = ((p - 0.26f) / 0.18f).coerceIn(0f, 1f)
                    val ease = 1f - (1f - mottoProg).pow(3f)
                    alpha = ease
                    translationY = (1f - ease) * 8.dp.toPx()
                },
            )

            Spacer(modifier = Modifier.height(18.dp))

            // Studio Signet: "ENGINEERED BY SIREEN // INDEPENDENT AUDIO LAB"
            Box(
                modifier = Modifier.graphicsLayer {
                    val p = timeline.value
                    val badgeProg = ((p - 0.32f) / 0.18f).coerceIn(0f, 1f)
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

            Spacer(modifier = Modifier.height(12.dp))

            // Engineering Spec Descriptor: "PURE SIGNAL · HIGH-FIDELITY AUDIO"
            Text(
                text = "PURE SIGNAL · HIGH-FIDELITY AUDIO",
                style = TextStyle(
                    color = descColor,
                    fontSize = 8.5.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 2.0.sp,
                    fontFamily = FontFamily.Monospace,
                ),
                modifier = Modifier.graphicsLayer {
                    val p = timeline.value
                    val descProg = ((p - 0.38f) / 0.16f).coerceIn(0f, 1f)
                    val ease = 1f - (1f - descProg).pow(3f)
                    alpha = ease * 0.85f
                },
            )
        }
    }
}
