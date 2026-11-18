package com.maxrave.simpmusic.ui.screen.other

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.maxrave.simpmusic.ui.theme.LocalIsDarkTheme
import com.maxrave.simpmusic.ui.theme.fontFamily
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.painterResource
import simpmusic.composeapp.generated.resources.Res
import simpmusic.composeapp.generated.resources.circle_app_icon
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * StreamifySplashScreen — Brand splash intro with dynamic animated waves and app logo.
 *
 * Adaptive Theme:
 * - If dark mode: deep dark backdrop (#07070A), luminous glowing neon wave ribbons, crisp white wordmark.
 * - If light mode: bright crisp backdrop (#F8F9FD), vibrant saturated pastel wave ribbons, high-contrast dark wordmark.
 *
 * Logo:
 * - Swaps the legacy triangle canvas logo with the current app's circle_app_icon (Res.drawable.circle_app_icon).
 *
 * Duration:
 * - ~2.5 seconds total runtime with tap-to-skip.
 */
@Composable
fun StreamifySplashScreen(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val timeline = remember { Animatable(0f) }
    val textMeasurer = rememberTextMeasurer()

    // Determine theme mode
    val isDark = LocalIsDarkTheme.current && MaterialTheme.colorScheme.background.luminance() <= 0.5f

    // Theme adaptive colors
    val bgColor = if (isDark) Color(0xFF07070A) else Color(0xFFF8F9FD)
    val wordmarkColor = if (isDark) Color.White else Color(0xFF141416)
    val signatureColor = if (isDark) Color.White.copy(alpha = 0.85f) else Color(0xFF26262A)
    val taglineColor = if (isDark) Color(0xFF8E8E93) else Color(0xFF6B6B76)
    val primaryAccent = if (isDark) Color(0xFF8B5CF6) else Color(0xFF7C3AED)
    val activeControl = if (isDark) Color(0xFF06B6D4) else Color(0xFF0284C7)

    val ribbonColors = if (isDark) {
        listOf(
            Color(0xFF8B5CF6), Color(0xFFA855F7),
            Color(0xFF06B6D4), Color(0xFF10B981),
            Color(0xFFF59E0B), Color(0xFFFF3366),
            Color(0xFFEC4899)
        )
    } else {
        listOf(
            Color(0xFF7C3AED), Color(0xFF9333EA),
            Color(0xFF0284C7), Color(0xFF059669),
            Color(0xFFD97706), Color(0xFFE11D48),
            Color(0xFFDB2777)
        )
    }

    LaunchedEffect(Unit) {
        timeline.animateTo(
            targetValue = 1f,
            animationSpec = tween(
                durationMillis = 2600,
                easing = FastOutSlowInEasing,
            ),
        )
        onDismiss()
    }

    val p = timeline.value

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
        // 1. Dynamic Waves & Brand Lockup Canvas
        Canvas(modifier = Modifier.fillMaxSize()) {
            val width = size.width
            val height = size.height

            // --- Animated Radiating Wave Ribbons (from center outward) ---
            if (p >= 0.15f && p < 1.0f) {
                val waveProg = ((p - 0.15f) / 0.85f).coerceIn(0f, 1f)
                val dispersionCurve = 1f - (1f - waveProg).pow(3f)

                val reusablePath = Path()
                val maxRibbons = 16

                for (i in 0 until maxRibbons) {
                    val baseAngle = (i * (360f / maxRibbons)) * (PI / 180f).toFloat()
                    val ribbonLength = (width.coerceAtLeast(height) * 0.95f) * dispersionCurve
                    val strokeWidth = (14.dp.toPx()) * (1f - dispersionCurve).coerceAtLeast(1.5f)

                    val frequency = 4.2f
                    val amplitude = (20.dp.toPx()) * dispersionCurve
                    val phaseShift = i * 0.42f

                    reusablePath.reset()
                    val segments = 32
                    for (s in 0..segments) {
                        val t = s.toFloat() / segments
                        val dist = ribbonLength * t
                        val waveOffset = sin(t * PI * frequency + phaseShift + (p * PI * 2)).toFloat() * amplitude
                        val x = center.x + cos(baseAngle) * dist + cos(baseAngle + (PI / 2f).toFloat()) * waveOffset
                        val y = center.y + sin(baseAngle) * dist + sin(baseAngle + (PI / 2f).toFloat()) * waveOffset

                        if (s == 0) reusablePath.moveTo(x, y) else reusablePath.lineTo(x, y)
                    }

                    val color = ribbonColors[i % ribbonColors.size]
                    val alpha = if (isDark) {
                        (1f - (waveProg * 0.5f)).coerceIn(0f, 0.85f)
                    } else {
                        (1f - (waveProg * 0.6f)).coerceIn(0f, 0.65f)
                    }

                    drawPath(
                        path = reusablePath,
                        color = color.copy(alpha = alpha),
                        style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
                    )
                }
            }

            // --- Central Pulse Aura around Logo ---
            if (p in 0.05f..0.85f) {
                val pLogo = ((p - 0.05f) / 0.80f).coerceIn(0f, 1f)
                val pulseRadius = 55.dp.toPx() * (1f + (pLogo * 1.6f))
                val auraAlpha = sin(pLogo * PI).toFloat().coerceIn(0f, 1f)

                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            primaryAccent.copy(alpha = auraAlpha * (if (isDark) 0.45f else 0.25f)),
                            activeControl.copy(alpha = auraAlpha * (if (isDark) 0.20f else 0.10f)),
                            Color.Transparent,
                        ),
                        center = center,
                        radius = pulseRadius,
                    ),
                    radius = pulseRadius,
                    center = center,
                )
            }

            // --- Persistent Brand Lockup ---
            val anchorProg = ((p - 0.25f) / 0.35f).coerceIn(0f, 1f)
            val anchorEase = 1f - (1f - anchorProg).pow(3f)
            val ascendDrift = -height * 0.16f * anchorEase
            val lockupCenterY = center.y + 60.dp.toPx() + ascendDrift
            val lockupScale = 1f + (0.03f * anchorEase)

            val word = "STREAMIFY"
            val letterSpacingPx = 8.dp.toPx()
            val wordStyle = TextStyle(
                color = wordmarkColor,
                fontSize = 34.sp,
                fontWeight = FontWeight.Black,
                fontFamily = fontFamily(),
            )

            val letterLayouts = word.map { ch ->
                textMeasurer.measure(AnnotatedString(ch.toString()), wordStyle)
            }
            val totalWordWidth = letterLayouts.sumOf { it.size.width } +
                (letterSpacingPx * (word.length - 1)).toInt()
            val wordHeight = letterLayouts.maxOf { it.size.height }

            val shimmerX = (((p * 1.35f) % 1f) * (width + 600f)) - 300f
            val underlineYPx = 10.dp.toPx()

            withTransform({
                scale(lockupScale, lockupScale, pivot = Offset(center.x, lockupCenterY))
            }) {
                var cursorX = center.x - (totalWordWidth / 2f)
                letterLayouts.forEachIndexed { index, layout ->
                    val li = ((p - 0.05f - (index * 0.035f)) / 0.12f).coerceIn(0f, 1f)
                    if (li > 0f) {
                        val ease = 1f - (1f - li).pow(3f)
                        val riseY = (1f - ease) * 12.dp.toPx()
                        val topLeft = Offset(cursorX, lockupCenterY - (wordHeight / 2f) + riseY)

                        // Base text pass
                        drawText(
                            textLayoutResult = layout,
                            topLeft = topLeft,
                            color = wordmarkColor.copy(alpha = 0.98f * ease),
                        )

                        // Shimmer sweep pass
                        clipRect(
                            left = shimmerX - 220f,
                            top = topLeft.y - 20f,
                            right = shimmerX + 220f,
                            bottom = topLeft.y + wordHeight + 20f,
                        ) {
                            drawText(
                                textLayoutResult = layout,
                                topLeft = topLeft,
                                brush = Brush.horizontalGradient(
                                    colors = listOf(
                                        wordmarkColor,
                                        primaryAccent,
                                        activeControl,
                                        wordmarkColor,
                                    ),
                                    startX = shimmerX - 220f,
                                    endX = shimmerX + 220f,
                                ),
                                alpha = ease,
                            )
                        }
                    }
                    cursorX += layout.size.width + letterSpacingPx
                }

                val wordTop = lockupCenterY - (wordHeight / 2f)
                val underlineY = wordTop + wordHeight + underlineYPx
                val halfWord = totalWordWidth / 2f

                // Underline progress sweep
                val sweepT = (p / 0.75f).coerceIn(0f, 1f)
                val sweepEase = 1f - (1f - sweepT).pow(3f)
                val underlineWidth = totalWordWidth * sweepEase
                val breathe = if (sweepT >= 1f) 0.78f + (0.17f * sin(p * PI * 6).toFloat()) else 0.95f

                if (underlineWidth > 2f) {
                    drawLine(
                        brush = Brush.horizontalGradient(
                            colors = listOf(
                                primaryAccent.copy(alpha = 0.3f),
                                activeControl.copy(alpha = breathe),
                                primaryAccent.copy(alpha = 0.3f),
                            ),
                            startX = center.x - halfWord,
                            endX = center.x + halfWord,
                        ),
                        start = Offset(center.x - halfWord, underlineY),
                        end = Offset(center.x - halfWord + underlineWidth, underlineY),
                        strokeWidth = 2.4.dp.toPx(),
                        cap = StrokeCap.Round,
                    )
                }

                // Subtitle / Developer signature
                val subIn = ((p - 0.16f) / 0.12f).coerceIn(0f, 1f)
                if (subIn > 0f) {
                    val subEase = 1f - (1f - subIn).pow(3f)
                    val subRise = (1f - subEase) * 8.dp.toPx()
                    val signatureAnnotated = buildAnnotatedString {
                        withStyle(
                            SpanStyle(
                                color = signatureColor.copy(alpha = 0.85f),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 3.5.sp,
                            ),
                        ) {
                            append("DEVELOPED BY ")
                        }
                        withStyle(
                            SpanStyle(
                                color = primaryAccent,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Black,
                                letterSpacing = 4.5.sp,
                            ),
                        ) {
                            append("SIREEN")
                        }
                    }
                    val signatureLayout = textMeasurer.measure(
                        text = signatureAnnotated,
                        style = TextStyle(fontFamily = fontFamily()),
                    )
                    drawText(
                        textLayoutResult = signatureLayout,
                        topLeft = Offset(
                            center.x - (signatureLayout.size.width / 2f),
                            underlineY + 16.dp.toPx() + subRise,
                        ),
                        alpha = subEase,
                    )
                }

                // Tagline during dispersion
                val tagAlpha = (((p - 0.50f) / 0.10f).coerceIn(0f, 1f)) *
                    ((1f - ((p - 0.88f) / 0.08f)).coerceIn(0f, 1f))
                if (tagAlpha > 0.01f) {
                    val tagLayout = textMeasurer.measure(
                        text = AnnotatedString("NEXT-GEN AUDIO STREAMING"),
                        style = TextStyle(
                            color = taglineColor,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = 2.5.sp,
                            fontFamily = fontFamily(),
                        ),
                    )
                    drawText(
                        textLayoutResult = tagLayout,
                        topLeft = Offset(
                            center.x - (tagLayout.size.width / 2f),
                            underlineY + 44.dp.toPx(),
                        ),
                        alpha = tagAlpha,
                    )
                }
            }
        }

        // 2. Center Streamify Logo Image (swapping legacy triangle)
        val logoScaleEase = ((p - 0.05f) / 0.25f).coerceIn(0f, 1f)
        val logoScale = if (p < 0.05f) 0f else (1f - (1f - logoScaleEase).pow(3f)) * (1f + (0.06f * sin(p * PI * 2).toFloat()))
        val anchorProg = ((p - 0.25f) / 0.35f).coerceIn(0f, 1f)
        val anchorEase = 1f - (1f - anchorProg).pow(3f)

        if (logoScale > 0.01f) {
            Image(
                painter = painterResource(Res.drawable.circle_app_icon),
                contentDescription = "Streamify Logo",
                modifier = Modifier
                    .size(80.dp)
                    .offset {
                        IntOffset(
                            x = 0,
                            y = (-80.dp.toPx() * anchorEase).roundToInt(),
                        )
                    }
                    .scale(logoScale)
                    .clip(CircleShape),
            )
        }
    }
}
