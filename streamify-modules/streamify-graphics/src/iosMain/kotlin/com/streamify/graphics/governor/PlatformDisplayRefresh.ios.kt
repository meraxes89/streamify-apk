package com.streamify.graphics.governor

/**
 * iOS actual of the display refresh rate seam (S24 Track C).
 *
 * 60.0 conservative default; the ProMotion display-link lane keeps its
 * own budgeting point outside this module.
 */
internal actual fun platformDisplayRefreshRateHz(): Double = 60.0
