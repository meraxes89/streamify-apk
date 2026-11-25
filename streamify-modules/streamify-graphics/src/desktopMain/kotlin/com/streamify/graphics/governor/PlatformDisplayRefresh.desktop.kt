package com.streamify.graphics.governor

import com.streamify.graphics.engine.SkikoDesktopBackendConfig

/**
 * Desktop JVM actual of the display refresh rate seam (S24 Track C):
 * the default screen device's live AWT display mode, 60.0 on headless or
 * unknown displays.
 */
internal actual fun platformDisplayRefreshRateHz(): Double =
    SkikoDesktopBackendConfig.currentDisplayRefreshRateHz()
