package com.streamify.supabase

import kotlinx.browser.window

actual fun resolvePlatformDeviceInfo(): PlatformDeviceInfo {
    val userAgent = try {
        window.navigator.userAgent
    } catch (_: Throwable) {
        "Wasm Browser"
    }
    return PlatformDeviceInfo(
        platform = "web",
        deviceModel = "Browser",
        osVersion = userAgent,
        architecture = "wasm",
        buildFlavor = "web"
    )
}
