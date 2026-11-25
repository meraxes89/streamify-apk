package com.streamify.graphics.engine

import java.awt.GraphicsEnvironment

/**
 * desktopMain actuals of the skiko platform signal seam — S24 Tracks C
 * and D (docs/systems/S24-sovereign-graphics.md).
 */

/**
 * Renderer description for the [CapabilityProbe]: reports the headless
 * JVM state as an environment diagnostic. Deliberately free of the
 * `llvmpipe`/`SwiftShader`/`Software` marker vocabulary — a headless JVM
 * still validates `RuntimeEffect` through Skia's CPU raster surfaces
 * (exactly how the offscreen golden suite runs on CI), so it stays on the
 * full capability matrix; the markers are reserved for genuinely forced
 * software rasterization (CI_TESTING_SPEC.md Job 4).
 */
actual fun skikoRendererDescription(): String =
    runCatching {
        if (GraphicsEnvironment.isHeadless()) {
            "headless JVM (CPU raster offscreen lane)"
        } else {
            ""
        }
    }.getOrDefault("")

/**
 * HiDPI density from the JDK's `sun.java2d.uiScale` property (2.0 on
 * Retina macOS JVMs): the Tier 2 engine downsamples its backdrop capture
 * toward logical 1x on such hosts. Unknown / unparseable → 1.0
 * (conservative: no downsample).
 */
actual fun skikoDisplayDensityScale(): Float =
    System.getProperty("sun.java2d.uiScale")
        ?.toFloatOrNull()
        ?.takeIf { it > 0f && !it.isNaN() }
        ?.coerceIn(1f, 4f)
        ?: 1f
