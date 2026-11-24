package com.streamify.supabase

actual fun resolvePlatformDeviceInfo(): PlatformDeviceInfo {
    return PlatformDeviceInfo(
        platform = "web",
        deviceModel = "Browser",
        osVersion = "Web Browser",
        architecture = "wasm",
        buildFlavor = "web"
    )
}
