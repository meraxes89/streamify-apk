package com.streamify.graphics.governor

import platform.UIKit.UIScreen

/**
 * iosMain actual of [platformDisplayRefreshRateHz] — S24 Track B
 * (docs/systems/S24-sovereign-graphics.md).
 *
 * `UIScreen.mainScreen.maximumFramesPerSecond` reports the display link's
 * maximum cadence: 120 on ProMotion panels (iPhone 13 Pro-16 Pro, iPad
 * Pro, ProMotion MacBook Pro mirror), 60 on standard panels. The governor
 * budgets the cold start against it (`forRefreshRate`); later cadence
 * shifts are reported by the host through
 * [TierGovernor.onDisplayRefreshRateChanged].
 *
 * Headless/simulator safety: guarded with `runCatching` (a test host or
 * an early boot call must never crash) and sanitized to [30, 240] — an
 * unknown answer falls back to the conservative 60 Hz lane.
 */
internal actual fun platformDisplayRefreshRateHz(): Double {
    val reported = runCatching {
        UIScreen.mainScreen.maximumFramesPerSecond.toDouble()
    }.getOrDefault(TierGovernor.DEFAULT_REFRESH_RATE_HZ)
    return if (reported in 30.0..240.0) reported else TierGovernor.DEFAULT_REFRESH_RATE_HZ
}
