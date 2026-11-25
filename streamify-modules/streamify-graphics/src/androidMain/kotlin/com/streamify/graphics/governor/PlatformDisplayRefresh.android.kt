package com.streamify.graphics.governor

/**
 * Android actual of the display refresh rate seam (S24 Track C).
 *
 * Conservative 60.0 default: the Android lane keeps its own
 * display-listener wiring point (`Display.getRefreshRate()` requires a
 * window-context the module never owns), and the S24 desktop-spectrum
 * scope deliberately leaves mobile budgeting untouched.
 */
internal actual fun platformDisplayRefreshRateHz(): Double = 60.0
