package com.streamify.graphics.engine

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Image

/**
 * Desktop JVM actuals of the Compose ⇄ Skia interop seams
 * (S24 Track B). Both conversions go through Compose's desktop graphics
 * runtime and are guarded — a non-Skia-backed bitmap (or a Skia failure)
 * degrades to `null` and the engine falls back to its stopgap composite.
 */
internal actual fun ImageBitmap.toSkiaBitmapOrNull(): Bitmap? =
    runCatching { asSkiaBitmap() }.getOrNull()

internal actual fun Image.toComposeImageBitmapOrNull(): ImageBitmap? =
    runCatching { toComposeImageBitmap() }.getOrNull()
