package com.streamify.graphics.governor

/**
 * wasmJs actual of the display refresh rate seam (S24 Track C).
 *
 * 60.0 conservative default; the browser lane keeps the standard budget
 * calibration.
 */
internal actual fun platformDisplayRefreshRateHz(): Double = 60.0
