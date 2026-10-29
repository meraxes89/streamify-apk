package com.streamify.desktop.shell

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.PaintingStyle
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import com.streamify.shared.theme.StreamifyPalette

/**
 * Phase 5 (MP-UI04 / Beta): the application / tray icon, drawn
 * programmatically at runtime.
 *
 * Zero binary assets by policy: the packaged distribution stays free of
 * icon PNGs (the MP-HAL10 size budget and the asset-hygiene gate both
 * benefit), and the same painter serves the window chrome and the AWT
 * system-tray surface. Brand geometry: OLED graphite tile, brand-red disc,
 * white play glyph — the shared palette in miniature.
 */
object AppIcon {

    /** Renders the Streamify glyph into a fresh painter. */
    fun create(sizePx: Int = 32): Painter {
        val bitmap = ImageBitmap(sizePx, sizePx)
        val canvas = Canvas(bitmap)
        val s = sizePx.toFloat()

        // OLED graphite tile.
        canvas.drawRect(
            rect = Rect(left = 0f, top = 0f, right = s, bottom = s),
            paint = Paint().apply { color = StreamifyPalette.BgSurface },
        )

        // Brand-red disc.
        canvas.drawCircle(
            center = Offset(s / 2f, s / 2f),
            radius = s * 0.38f,
            paint = Paint().apply { color = StreamifyPalette.Primary },
        )

        // White play glyph (a hair wider than tall — optically centered).
        val glyph = Path().apply {
            val cx = s * 0.545f
            val cy = s * 0.5f
            val halfH = s * 0.19f
            val halfW = s * 0.15f
            moveTo(cx - halfW, cy - halfH)
            lineTo(cx + halfW, cy)
            lineTo(cx - halfW, cy + halfH)
            close()
        }
        canvas.drawPath(
            path = glyph,
            paint = Paint().apply {
                color = Color.White
                style = PaintingStyle.Fill
            },
        )

        return BitmapPainter(bitmap)
    }
}
