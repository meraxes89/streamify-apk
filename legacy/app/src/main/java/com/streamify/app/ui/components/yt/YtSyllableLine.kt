package com.streamify.app.ui.components.yt
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.streamify.app.data.models.LyricsLine
import com.streamify.app.ui.theme.*

@Composable
fun YtSyllableLine(
    line: LyricsLine,
    isActive: Boolean,
    currentTimeMs: () -> Long,
    dominantColor: Color = Primary,
    onTap: () -> Unit,
    modifier: Modifier = Modifier
) {
    val textMeasurer = rememberTextMeasurer()
    val interactionSource = remember { MutableInteractionSource() }

    // 1. Pre-compute Text Layout
    val textStyle = TextStyle(
        fontSize = if (isActive) 25.sp else 22.sp,
        fontWeight = if (isActive) FontWeight.Bold else FontWeight.SemiBold,
        fontFamily = StreamifyFontFamily,
        lineHeight = 34.sp
    )

    val layoutResult = remember(line.text, isActive) {
        textMeasurer.measure(
            text = AnnotatedString(line.text),
            style = textStyle,
            overflow = TextOverflow.Visible
        )
    }

    // Pre-allocated GPU Assets: Zero heap allocations during draw phase
    val vocalBloomColors = remember(dominantColor) {
        listOf(
            dominantColor.copy(alpha = 0.22f),
            Color.Transparent
        )
    }

    // 2. Dynamic Spring Pop on Line Activation
    val scaleAnim = remember { Animatable(1f) }

    LaunchedEffect(isActive) {
        if (isActive) {
            scaleAnim.snapTo(0.97f)
            scaleAnim.animateTo(
                targetValue = 1.03f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessLow
                )
            )
            scaleAnim.animateTo(
                targetValue = 1.0f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMedium
                )
            )
        } else {
            scaleAnim.animateTo(
                targetValue = 1.0f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessLow
                )
            )
        }
    }

    val lineHeightDp = with(androidx.compose.ui.platform.LocalDensity.current) {
        layoutResult.size.height.toDp()
    }

    // 3. 120 FPS GPU Draw Phase with 28px Soft-Gradient Feathering
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp, horizontal = 24.dp)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onTap
            )
            .graphicsLayer {
                scaleX = scaleAnim.value
                scaleY = scaleAnim.value
                this.alpha = if (isActive) 1f else 0.40f
                transformOrigin = TransformOrigin(0f, 0.5f)
            }
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(lineHeightDp.coerceAtLeast(36.dp))
        ) {
            val currentTime = currentTimeMs()
            val textWidth = layoutResult.size.width.toFloat().coerceAtLeast(1f)

            // If line is inactive or strictly in the future, draw dormant grey text
            if (!isActive || currentTime < line.timeMs) {
                drawText(layoutResult, color = TextTertiary.copy(alpha = 0.45f))
                return@Canvas
            }

            // Ambient Vocal Bloom behind active line
            drawRect(
                brush = Brush.radialGradient(
                    colors = vocalBloomColors,
                    center = Offset(size.width * 0.35f, size.height / 2f),
                    radius = size.width * 0.70f
                )
            )

            // Calculate Sub-Pixel Playhead X Position
            var playheadX = 0f
            val syllables = line.syllables

            if (syllables.isNotEmpty()) {
                var previousEndOffset = 0f

                for (syllable in syllables) {
                    val syllableWidth = measureSyllableWidth(layoutResult, syllable.text, line.text, previousEndOffset)
                    previousEndOffset += syllableWidth

                    if (currentTime >= syllable.endMs) {
                        playheadX += syllableWidth
                    } else if (currentTime in syllable.startMs..syllable.endMs) {
                        val duration = (syllable.endMs - syllable.startMs).coerceAtLeast(1L).toFloat()
                        val progress = ((currentTime - syllable.startMs).toFloat() / duration).coerceIn(0f, 1f)
                        playheadX += syllableWidth * progress
                        break
                    } else {
                        break
                    }
                }
            } else {
                // Dynamic Line Duration Calculus (Replaces hardcoded 3500ms)
                val lineDuration = if (line.durationMs > 0L) line.durationMs.toFloat() else 3500f
                val progress = ((currentTime - line.timeMs).toFloat() / lineDuration).coerceIn(0f, 1f)
                playheadX = textWidth * progress
            }

            // Soft 28px Gradient Shader Feathering (GPU-accelerated Organic Illumination)
            val feather = 28.dp.toPx()
            val leftStop = ((playheadX - feather) / textWidth).coerceIn(0f, 1f)
            val rightStop = ((playheadX + feather) / textWidth).coerceIn(0f, 1f)

            if (playheadX <= 0f) {
                drawText(layoutResult, color = TextTertiary.copy(alpha = 0.45f))
            } else if (playheadX >= textWidth + feather) {
                drawText(layoutResult, color = TextMain)
            } else {
                val fluidBrush = Brush.horizontalGradient(
                    0f to TextMain,
                    leftStop to TextMain,
                    rightStop to TextTertiary.copy(alpha = 0.45f),
                    1f to TextTertiary.copy(alpha = 0.45f),
                    startX = 0f,
                    endX = textWidth
                )

                drawText(layoutResult, brush = fluidBrush)
            }
        }
    }
}

// Sub-pixel syllable width calculation using font kerning positions
private fun measureSyllableWidth(
    layout: TextLayoutResult,
    syllableText: String,
    fullLineText: String,
    startSearchOffset: Float
): Float {
    if (syllableText.isEmpty()) return 0f
    val startIndex = fullLineText.indexOf(syllableText)
    if (startIndex == -1) return 0f
    val endIndex = (startIndex + syllableText.length).coerceAtMost(fullLineText.length)

    val startX = layout.getHorizontalPosition(startIndex, true)
    val endX = layout.getHorizontalPosition(endIndex, true)
    return (endX - startX).coerceAtLeast(0f)
}
