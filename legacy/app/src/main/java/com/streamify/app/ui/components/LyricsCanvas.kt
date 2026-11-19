package com.streamify.app.ui.components

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import com.streamify.app.ui.lyrics.LyricsEngine

@Composable
fun LyricsCanvas(
    modifier: Modifier = Modifier,
    progressProvider: () -> Long
) {
    val lyrics = LyricsEngine.lyricLines
    if (lyrics.isEmpty()) return

    val activeIndexState = remember { mutableIntStateOf(0) }
    val scrollOffset = remember { Animatable(0f) }

    val activePaint = remember {
        Paint().apply {
            color = android.graphics.Color.WHITE
            textSize = 54f
            isAntiAlias = true
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
    }

    val inactivePaint = remember {
        Paint().apply {
            color = android.graphics.Color.argb(120, 255, 255, 255)
            textSize = 46f
            isAntiAlias = true
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        }
    }

    // 120 FPS frame ticker driven by native Choreographer / withFrameNanos
    LaunchedEffect(lyrics) {
        while (true) {
            withFrameNanos {
                val rawMs = progressProvider()
                // NOTE: latency compensation scaffold (LatencyProbe) was removed in
                // the v1.1.0 refactor — it was never bound to the audio path and its
                // estimate was permanently 0. Re-introduce an audio-output latency
                // probe here if karaoke drift compensation becomes a requirement.
                val newIndex = LyricsEngine.getActiveIndex(rawMs)

                if (newIndex != activeIndexState.intValue) {
                    activeIndexState.intValue = newIndex
                }
            }
        }
    }

    LaunchedEffect(activeIndexState.intValue) {
        val targetY = activeIndexState.intValue * 130f
        scrollOffset.animateTo(
            targetValue = targetY,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioLowBouncy,
                stiffness = Spring.StiffnessLow
            )
        )
    }

    Canvas(modifier = modifier.fillMaxSize()) {
        drawIntoCanvas { canvas ->
            val nativeCanvas = canvas.nativeCanvas
            nativeCanvas.save()

            val centerY = size.height * 0.45f
            nativeCanvas.translate(0f, centerY - scrollOffset.value)

            val activeIdx = activeIndexState.intValue
            for (i in lyrics.indices) {
                val line = lyrics[i]
                val isActive = (i == activeIdx)
                val paint = if (isActive) activePaint else inactivePaint

                val yPos = i * 130f
                // Culling offscreen lines
                if (yPos - scrollOffset.value > -centerY - 200f && yPos - scrollOffset.value < size.height + 200f) {
                    nativeCanvas.drawText(line, 48f, yPos, paint)
                }
            }

            nativeCanvas.restore()
        }
    }
}
