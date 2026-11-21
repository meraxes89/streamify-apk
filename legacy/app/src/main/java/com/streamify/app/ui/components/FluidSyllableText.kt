package com.streamify.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.graphics.Paint
import android.graphics.Typeface

/**
 * Karaoke sweep renderer.
 *
 * PERF CONTRACT: the playhead is supplied as a PROVIDER and is only ever read
 * inside the draw phase. Compose invalidates just this node's redraw when the
 * underlying state ticks — zero recomposition, zero per-frame allocation.
 */
@Composable
fun FluidSyllableText(
    text: String,
    progressFractionProvider: () -> Float, // [0..1], read in draw phase only
    isActiveLine: Boolean,
    baseColor: Color = Color(0x66FFFFFF),
    highlightColor: Color = Color(0xFFFFFFFF),
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val fontSizePx = with(density) { 24.sp.toPx() }

    // Reusable text paint; re-created only when metrics actually change.
    val textPaint = remember(fontSizePx) {
        Paint().apply {
            isAntiAlias = true
            textSize = fontSizePx
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
    }
    // Cache glyph measurement per text content (was re-measured every frame).
    val measuredWidth = remember(text, fontSizePx) { textPaint.measureText(text) }

    val baseArgb = remember(baseColor, isActiveLine) {
        (if (isActiveLine) baseColor else Color(0x33FFFFFF)).toArgb()
    }
    val highlightArgb = remember(highlightColor) { highlightColor.toArgb() }

    Canvas(
        modifier = modifier.fillMaxWidth().height(48.dp)
    ) {
        val canvasHeight = size.height
        val textY = canvasHeight / 2f + (fontSizePx / 3f)

        // 1. Base un-highlighted pass.
        textPaint.color = baseArgb
        drawContext.canvas.nativeCanvas.drawText(text, 0f, textY, textPaint)

        // 2. Clipped highlight sweep — playhead read happens HERE, in draw.
        val progress = progressFractionProvider()
        if (isActiveLine && progress > 0f) {
            val currentSweepX = (measuredWidth * progress).coerceIn(0f, measuredWidth)
            clipRect(
                left = 0f,
                top = 0f,
                right = currentSweepX,
                bottom = canvasHeight
            ) {
                textPaint.color = highlightArgb
                drawContext.canvas.nativeCanvas.drawText(text, 0f, textY, textPaint)
            }
        }
    }
}

@Composable
fun FluidSyllableText(
    text: String,
    lineStartMs: Long,
    lineEndMs: Long,
    playbackMsProvider: () -> Long, // read in draw phase ONLY — never in composition
    isActive: Boolean,
    isPast: Boolean,
    onClick: () -> Unit = {},
    modifier: Modifier = Modifier,
    baseColor: Color = Color(0x66FFFFFF),
    highlightColor: Color = Color(0xFFFFFFFF)
) {
    FluidSyllableText(
        text = text,
        progressFractionProvider = {
            if (isActive) {
                val duration = (lineEndMs - lineStartMs).coerceAtLeast(1L)
                ((playbackMsProvider() - lineStartMs).toFloat() / duration.toFloat()).coerceIn(0f, 1f)
            } else if (isPast) {
                1f
            } else {
                0f
            }
        },
        isActiveLine = isActive,
        baseColor = if (isPast) Color(0xAAFFFFFF) else baseColor,
        highlightColor = highlightColor,
        modifier = modifier.clickable { onClick() }
    )
}
