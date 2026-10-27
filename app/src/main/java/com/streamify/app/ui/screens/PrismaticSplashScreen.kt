package com.streamify.app.ui.screens

import android.graphics.RuntimeShader
import android.os.Build
import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.streamify.app.ui.theme.*
import com.streamify.app.util.StreamifyHapticEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * PRISMATIC GENESIS v2 — Brand-first launch choreography.
 *
 * Design contract (professional splash invariants):
 *  1. The brand lockup (STREAMIFY / DEVELOPED BY SIREEN) is visible for 100%
 *     of the runtime — it never vanishes behind the visual effects.
 *  2. Text legibility never depends on a sweeping gradient position: the base
 *     pass is always solid/bright; the shimmer is a SECOND clipped overlay pass.
 *  3. The full-screen canvas NEVER flies away on exit. Everything dissolves in
 *     place while the wordmark performs a gentle upward ascension hand-off,
 *     revealing the pre-warmed Home screen beneath through an alpha curtain.
 */
@Composable
fun PrismaticSplashScreen(
    onPreWarmComplete: suspend () -> Unit,
    onAnimationComplete: () -> Unit,
    modifier: Modifier = Modifier
) {
    val timeline = remember { Animatable(0f) }
    var touchPos by remember { mutableStateOf(Offset.Zero) }
    val textMeasurer = rememberTextMeasurer()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // 1. Compile AGSL Shader on Android 13+ / RenderNode Fallback
    val runtimeShader = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                RuntimeShader(SPLASH_AGSL_SHADER)
            } catch (e: Exception) {
                null
            }
        } else null
    }

    // 2. Dual Background Pre-Warming Engine & Multi-Sensory Haptic Score
    LaunchedEffect(Unit) {
        // Accessibility: honor system-wide "remove animations" — skip straight
        // to the hand-off while STILL running the full pre-warm pipeline.
        val animatorScale = try {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE, 1f
            )
        } catch (_: Exception) { 1f }
        val animationsDisabled = animatorScale == 0f

        // Haptic Cue 1: Laser Shimmer Ignition
        if (!animationsDisabled) StreamifyHapticEngine.scrubberTick()

        val hapticJob = scope.launch {
            if (animationsDisabled) return@launch
            // Milestone 2: Singularity Ignition detent (~2.1s)
            kotlinx.coroutines.delay(2100)
            StreamifyHapticEngine.tokenImpactDetent()

            // Milestone 3: Prismatic Dispersion flutter (~3.3s)
            kotlinx.coroutines.delay(1200)
            StreamifyHapticEngine.magneticQueueGrab()
        }

        val animJob = async {
            if (animationsDisabled) {
                timeline.snapTo(1f)
            } else {
                timeline.animateTo(
                    targetValue = 1f,
                    animationSpec = tween(durationMillis = 4400, easing = LinearEasing)
                )
            }
        }

        val preWarmJob = async(Dispatchers.IO) {
            onPreWarmComplete()
        }

        awaitAll(animJob, preWarmJob)
        hapticJob.cancel()
        onAnimationComplete()
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = { offset ->
                        touchPos = offset
                        tryAwaitRelease()
                        touchPos = Offset.Zero
                    },
                    onTap = {
                        // Instant Fast-Forward on tap if warmed up
                        scope.launch {
                            timeline.animateTo(
                                targetValue = 1f,
                                animationSpec = tween(durationMillis = 250, easing = FastOutSlowInEasing)
                            )
                            onAnimationComplete()
                        }
                    }
                )
            }
            .graphicsLayer {
                // ASCENSION EXIT CURTAIN: the whole canvas dissolves IN PLACE
                // (never translates/scales) revealing Home beneath seamlessly.
                val curtain = ((timeline.value - 0.86f) / 0.14f).coerceIn(0f, 1f)
                alpha = 1f - curtain
            }
            .drawBehind {
                drawRect(color = Color(0xFF07070A)) // True OLED Base
            },
        contentAlignment = Alignment.Center
    ) {
        val p = timeline.value

        // High-Performance AGSL Shader (Android 13+) with Fallback 2D Canvas
        Canvas(modifier = Modifier.fillMaxSize()) {
            val width = size.width
            val height = size.height
            val center = Offset(width / 2f, height / 2f)

            // 1. Hardware AGSL Shader Layer
            if (runtimeShader != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                try {
                    runtimeShader.setFloatUniform("uResolution", width, height)
                    runtimeShader.setFloatUniform("uTime", p * 4.4f)
                    runtimeShader.setFloatUniform("uProgress", p)
                    runtimeShader.setFloatUniform("uTouch", touchPos.x, touchPos.y)
                    drawRect(brush = ShaderBrush(runtimeShader))
                } catch (e: Throwable) {
                    // Fail-safe: absorb and continue with 2D Canvas
                }
            }

            val p1End = 0.350f
            val p3End = 0.880f

            // Fallback for Pre-Android 13 Devices (Render 2D Prismatic Ribbons)
            if (runtimeShader == null) {
                if (p >= p1End && p < p3End) {
                    val p2 = ((p - p1End) / (p3End - p1End)).coerceIn(0f, 1f)
                    val zoomCurve = 1f - (1f - p2).pow(4f)
                    val badgeRadius = (26.dp.toPx()) * (1f + (zoomCurve * 2.8f))

                    val plasmaAlpha = (0.4f + 0.6f * sin(p2 * PI * 4).toFloat()).coerceIn(0f, 1f)
                    drawCircle(
                        color = Primary.copy(alpha = plasmaAlpha * 0.75f),
                        radius = badgeRadius * 1.35f,
                        center = center,
                        blendMode = BlendMode.Screen
                    )

                    drawCircle(
                        color = Primary,
                        radius = badgeRadius,
                        center = center
                    )

                    val trianglePath = Path().apply {
                        moveTo(center.x - (badgeRadius * 0.3f), center.y - (badgeRadius * 0.4f))
                        lineTo(center.x + (badgeRadius * 0.45f), center.y)
                        lineTo(center.x - (badgeRadius * 0.3f), center.y + (badgeRadius * 0.4f))
                        close()
                    }
                    drawPath(path = trianglePath, color = ActiveControl)
                }

                if (p >= 0.650f && p < 1.000f) {
                    val p3 = ((p - 0.650f) / (1.000f - 0.650f)).coerceIn(0f, 1f)
                    val dispersionCurve = 1f - (1f - p3).pow(3f)

                    val ribbonColors = listOf(
                        Color(0xFF8B5CF6), Color(0xFFA855F7),
                        Color(0xFF06B6D4), Color(0xFF10B981),
                        Color(0xFFF59E0B), Color(0xFFFF0000), Color(0xFFEC4899)
                    )

                    val reusablePath = Path()
                    val maxRibbons = 16

                    for (i in 0 until maxRibbons) {
                        val baseAngle = (i * (360f / maxRibbons)) * (PI / 180f).toFloat()
                        val ribbonLength = (width * 0.90f) * dispersionCurve
                        val strokeWidth = (14.dp.toPx()) * (1f - dispersionCurve).coerceAtLeast(1f)

                        val frequency = 4.5f
                        val amplitude = (18.dp.toPx()) * dispersionCurve
                        val phaseShift = i * 0.45f

                        reusablePath.reset()
                        val segments = 30
                        for (s in 0..segments) {
                            val t = s.toFloat() / segments
                            val dist = ribbonLength * t
                            val waveOffset = sin(t * PI * frequency + phaseShift + (p * PI * 2)).toFloat() * amplitude
                            val x = center.x + cos(baseAngle) * dist + cos(baseAngle + (PI / 2f).toFloat()) * waveOffset
                            val y = center.y + sin(baseAngle) * dist + sin(baseAngle + (PI / 2f).toFloat()) * waveOffset

                            if (s == 0) reusablePath.moveTo(x, y) else reusablePath.lineTo(x, y)
                        }

                        val color = ribbonColors[i % ribbonColors.size]
                        drawPath(
                            path = reusablePath,
                            color = color.copy(alpha = (1f - (p3 * 0.45f)).coerceIn(0f, 1f)),
                            style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
                            blendMode = BlendMode.Screen
                        )
                    }
                }
            }

            // =====================================================================
            // 2. PERSISTENT BRAND LOCKUP — drawn EVERY frame, above all effects.
            // =====================================================================
            val exitT = ((p - 0.92f) / 0.08f).coerceIn(0f, 1f)
            val exitEase = exitT * exitT

            // Anchor journey: center stage -> upper-third throne (Phase 2 onward)
            val anchorProg = ((p - 0.35f) / 0.30f).coerceIn(0f, 1f)
            val anchorEase = 1f - (1f - anchorProg).pow(3f)
            val ascendDrift = -height * 0.155f * anchorEase
            val exitLift = -48.dp.toPx() * exitEase
            val lockupCenterY = center.y - 18.dp.toPx() + ascendDrift + exitLift
            val lockupScale = 1f + (0.04f * anchorEase)

            val word = "STREAMIFY"
            val letterSpacingPx = 8.dp.toPx()
            val wordStyle = TextStyle(
                color = Color.White,
                fontSize = 36.sp,
                fontWeight = FontWeight.Black,
                fontFamily = StreamifyFontFamily
            )

            // Measure every glyph once per frame (TextMeasurer caches layouts).
            val letterLayouts = word.map { ch ->
                textMeasurer.measure(AnnotatedString(ch.toString()), wordStyle)
            }
            val totalWordWidth = letterLayouts.sumOf { it.size.width } +
                    (letterSpacingPx * (word.length - 1)).toInt()
            val wordHeight = letterLayouts.maxOf { it.size.height }

            // Continuous prismatic shimmer band sweeping across the lockup forever.
            val shimmerX = (((p * 1.35f) % 1f) * (width + 620f)) - 310f

            val underlineYPx = 10.dp.toPx()

            withTransform({
                scale(lockupScale, lockupScale, pivot = Offset(center.x, lockupCenterY))
            }) {
                // --- Wordmark: per-letter genesis reveal (rise + fade-in) ---
                var cursorX = center.x - (totalWordWidth / 2f)
                letterLayouts.forEachIndexed { index, layout ->
                    val li = ((p - 0.06f - (index * 0.038f)) / 0.11f).coerceIn(0f, 1f)
                    if (li > 0f) {
                        val ease = 1f - (1f - li).pow(3f)
                        val riseY = (1f - ease) * 12.dp.toPx()
                        val topLeft = Offset(cursorX, lockupCenterY - (wordHeight / 2f) + riseY)

                        // BASE PASS: always-legible solid white (never gradient-clamped)
                        drawText(
                            textLayoutResult = layout,
                            topLeft = topLeft,
                            color = Color.White.copy(alpha = 0.97f * ease)
                        )

                        // SHIMMER PASS: clipped neon band riding over the base
                        clipRect(
                            left = shimmerX - 240f,
                            top = topLeft.y - 20f,
                            right = shimmerX + 240f,
                            bottom = topLeft.y + wordHeight + 20f
                        ) {
                            drawText(
                                textLayoutResult = layout,
                                topLeft = topLeft,
                                brush = Brush.horizontalGradient(
                                    colors = listOf(
                                        Color.White,
                                        Primary,
                                        ActiveControl,
                                        Color.White
                                    ),
                                    startX = shimmerX - 240f,
                                    endX = shimmerX + 240f
                                ),
                                alpha = ease
                            )
                        }
                    }
                    cursorX += layout.size.width + letterSpacingPx
                }

                val wordTop = lockupCenterY - (wordHeight / 2f)
                val underlineY = wordTop + wordHeight + underlineYPx
                val halfWord = totalWordWidth / 2f

                // --- Neon Underline: load-progress level indicator ---
                val sweepT = (p / 0.78f).coerceIn(0f, 1f)
                val sweepEase = 1f - (1f - sweepT).pow(3f)
                val underlineWidth = totalWordWidth * sweepEase
                val breathe = if (sweepT >= 1f) 0.78f + (0.17f * sin(p * PI * 6).toFloat()) else 0.95f

                if (underlineWidth > 2f) {
                    drawLine(
                        brush = Brush.horizontalGradient(
                            colors = listOf(
                                Primary.copy(alpha = 0.25f),
                                ActiveControl.copy(alpha = breathe),
                                Primary.copy(alpha = 0.25f)
                            ),
                            startX = center.x - halfWord,
                            endX = center.x + halfWord
                        ),
                        start = Offset(center.x - halfWord, underlineY),
                        end = Offset(center.x - halfWord + underlineWidth, underlineY),
                        strokeWidth = 2.2.dp.toPx(),
                        cap = StrokeCap.Round
                    )

                    // Leading spark while the level indicator sweeps
                    if (sweepT < 1f) {
                        drawCircle(
                            brush = Brush.radialGradient(
                                colors = listOf(Color.White, ActiveControl.copy(alpha = 0.6f), Color.Transparent),
                                center = Offset(center.x - halfWord + underlineWidth, underlineY),
                                radius = 22f
                            ),
                            radius = 22f,
                            center = Offset(center.x - halfWord + underlineWidth, underlineY)
                        )
                    }
                }

                // --- Signature: DEVELOPED BY SIREEN (rises in, NEVER leaves) ---
                val sireenIn = ((p - 0.16f) / 0.10f).coerceIn(0f, 1f)
                if (sireenIn > 0f) {
                    val sireenEase = 1f - (1f - sireenIn).pow(3f)
                    val sireenRise = (1f - sireenEase) * 10.dp.toPx()
                    val sireenAnnotated = buildAnnotatedString {
                        withStyle(
                            SpanStyle(
                                color = Color.White.copy(alpha = 0.82f),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 4.sp
                            )
                        ) {
                            append("DEVELOPED BY ")
                        }
                        withStyle(
                            SpanStyle(
                                color = Primary,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Black,
                                letterSpacing = 5.5.sp
                            )
                        ) {
                            append("SIREEN")
                        }
                    }
                    val sireenLayout = textMeasurer.measure(
                        text = sireenAnnotated,
                        style = TextStyle(fontFamily = StreamifyFontFamily)
                    )
                    drawText(
                        textLayoutResult = sireenLayout,
                        topLeft = Offset(
                            center.x - (sireenLayout.size.width / 2f),
                            underlineY + 16.dp.toPx() + sireenRise
                        ),
                        alpha = sireenEase
                    )
                }

                // --- Capability tagline: one elegant cycle during Dispersion ---
                val tagAlpha = (((p - 0.60f) / 0.07f).coerceIn(0f, 1f)) *
                        ((1f - ((p - 0.84f) / 0.06f)).coerceIn(0f, 1f))
                if (tagAlpha > 0.01f) {
                    val tagLayout = textMeasurer.measure(
                        text = AnnotatedString("120 FPS · SPATIAL DSP · NEURAL RADIO"),
                        style = TextStyle(
                            color = TextSecondary,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Medium,
                            letterSpacing = 2.5.sp,
                            fontFamily = StreamifyFontFamily
                        )
                    )
                    drawText(
                        textLayoutResult = tagLayout,
                        topLeft = Offset(
                            center.x - (tagLayout.size.width / 2f),
                            underlineY + 42.dp.toPx()
                        ),
                        alpha = tagAlpha
                    )
                }
            }
        }
    }
}

private const val SPLASH_AGSL_SHADER = """
    uniform float2 uResolution;
    uniform float  uTime;
    uniform float  uProgress;
    uniform float2 uTouch;

    float hash(float2 p) {
        return fract(sin(dot(p, float2(127.1, 311.7))) * 43758.5453);
    }

    float4 main(float2 fragCoord) {
        float2 uv = (fragCoord - 0.5 * uResolution) / min(uResolution.x, uResolution.y);
        float2 touchOffset = (uTouch - 0.5 * uResolution) / min(uResolution.x, uResolution.y);

        // Gravitational lens pull toward touch coordinates
        float touchDist = length(uv - touchOffset);
        uv -= (touchOffset - uv) * 0.08 * exp(-touchDist * 3.0) * smoothstep(0.0, 0.8, uProgress);

        float d = length(uv);
        float angle = atan(uv.y, uv.x);

        // Singularity Core Dynamics
        float coreRadius = 0.18 * smoothstep(0.35, 0.65, uProgress) * (1.0 - smoothstep(0.85, 1.0, uProgress));
        float pulse = 0.03 * sin(uTime * 8.0 + angle * 4.0);
        float core = smoothstep(coreRadius + pulse, coreRadius * 0.2, d);

        // 16 Prismatic Dispersion Filament Ribbons
        float filaments = 0.0;
        if (uProgress > 0.60) {
            float burstProgress = smoothstep(0.60, 0.90, uProgress);
            float spiral = sin(angle * 16.0 + uTime * 4.0 - d * 12.0);
            filaments = smoothstep(0.7, 1.0, spiral) * exp(-d * (3.0 - burstProgress * 2.0)) * burstProgress;
        }

        // Chromatic Aberration Spectrum
        float3 col;
        col.r = core * 0.95 + filaments * 0.85 + (0.04 / (d + 0.08)) * smoothstep(0.0, 0.5, uProgress);
        col.g = core * 0.12 + filaments * 0.45 + (0.02 / (d + 0.12)) * smoothstep(0.4, 0.8, uProgress);
        col.b = core * 0.35 + filaments * 0.95 + (0.06 / (d + 0.06)) * smoothstep(0.2, 0.7, uProgress);

        // Subtle 35mm Analog Film Grain
        float grain = (hash(fragCoord + uTime) - 0.5) * 0.035;
        col += grain;

        // Fade out to deep OLED black at end of sequence
        float globalAlpha = 1.0 - smoothstep(0.90, 1.0, uProgress);
        return float4(col * globalAlpha, globalAlpha);
    }
"""
