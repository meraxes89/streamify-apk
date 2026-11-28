package com.streamify.graphics.engine

import androidx.compose.ui.graphics.ImageBitmap
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Image

/**
 * Compose ⇄ Skia bitmap interop seams for the skiko targets
 * (S24 Track B — offscreen DRS pass plumbing).
 *
 * The DRS pass runs on raw `org.jetbrains.skia` types (visible to the
 * shared `skikoCommonMain` source set through the same skiko artifact that
 * provides `RuntimeEffect`), while the Compose facade captures backdrops
 * as common [ImageBitmap]s. The conversion extensions live in Compose's
 * *platform* graphics variants (`androidx.compose.ui.graphics.asSkiaBitmap`
 * / `toComposeImageBitmap`), which an intermediate source set must not
 * reference — hence these expect/actual seams.
 *
 *  * **desktop JVM** — real conversions.
 *  * **wasmJs / iOS** — `null` (the engine keeps its stopgap composite;
 *    the S24 desktop-spectrum scope explicitly targets the desktop lane).
 *
 * Every actual is total: it returns `null` instead of throwing, so a
 * conversion failure degrades the draw to the stopgap lane.
 */
internal expect fun ImageBitmap.toSkiaBitmapOrNull(): Bitmap?

/** Converts a rendered Skia pass result into the Compose image the engine draws. */
internal expect fun Image.toComposeImageBitmapOrNull(): ImageBitmap?
