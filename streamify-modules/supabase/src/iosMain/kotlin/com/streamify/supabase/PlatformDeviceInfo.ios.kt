package com.streamify.supabase

actual fun resolvePlatformDeviceInfo(): PlatformDeviceInfo {
    return PlatformDeviceInfo(
        platform = "ios",
        deviceModel = "Apple Device",
        osVersion = "iOS",
        architecture = "arm64",
        buildFlavor = "standard"
    )
}
