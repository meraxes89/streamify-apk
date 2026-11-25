package com.streamify.graphics.engine

import androidx.compose.ui.graphics.ImageBitmap
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Image

/**
 * iOS actuals of the Compose ⇄ Skia interop seams (S24 Track B).
 *
 * The offscreen DRS pass is the desktop lane of the spectrum; iOS keeps
 * the stopgap composite, so both seams resolve to `null`.
 */
internal actual fun ImageBitmap.toSkiaBitmapOrNull(): Bitmap? = null

internal actual fun Image.toComposeImageBitmapOrNull(): ImageBitmap? = null
