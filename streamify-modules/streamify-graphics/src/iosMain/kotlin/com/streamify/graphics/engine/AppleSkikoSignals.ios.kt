package com.streamify.graphics.engine

import platform.Foundation.NSProcessInfo
import platform.UIKit.UIScreen

/**
 * iosMain actuals of the skiko platform signal seam — S24 Tracks C and D
 * (docs/systems/S24-sovereign-graphics.md).
 */

/**
 * Renderer description for the [CapabilityProbe]: the iOS Simulator's GPU
 * is the host's Metal device (paravirtualized) — NOT a software
 * rasterizer — so the string is deliberately free of the
 * `llvmpipe`/`SwiftShader`/`Software` marker vocabulary that would pin
 * Tier 4. Simulator detection runs through the `NSProcessInfo`
 * environment (the simulator runtime injects `SIMULATOR_DEVICE_NAME`),
 * guarded so a headless test host never crashes.
 */
actual fun skikoRendererDescription(): String {
    val environment = runCatching { NSProcessInfo.processInfo.environment }.getOrNull()
        ?: return "Apple GPU (iOS, environment unavailable)"
    val runningInSimulator = environment.containsKey(SIMULATOR_ENVIRONMENT_MARKER)
    return if (runningInSimulator) {
        "Apple GPU (iOS Simulator — host Metal, paravirtualized)"
    } else {
        "Apple GPU (Apple Silicon device)"
    }
}

/**
 * `UIScreen.mainScreen.scale` — 2.0 / 3.0 on Retina-class displays (3.0 on
 * the iPhone Pro Max), 1.0 on standard-density external displays. This is
 * the density axis of
 * [com.streamify.graphics.governor.BackdropResolutionPolicy]: at 3x the
 * Tier 2 engine downsamples its backdrop capture to logical 1x (0.33x)
 * before the blur/refraction pass, cutting TBDR tile flushes by ~75%.
 * Guarded + clamped — a headless host gets the conservative 2.0 Retina
 * default (so the DRS lane is exercised) rather than a crash.
 */
actual fun skikoDisplayDensityScale(): Float {
    val scale = runCatching { UIScreen.mainScreen.scale.toFloat() }.getOrDefault(2f)
    return scale.coerceIn(1f, 4f)
}

private const val SIMULATOR_ENVIRONMENT_MARKER = "SIMULATOR_DEVICE_NAME"
