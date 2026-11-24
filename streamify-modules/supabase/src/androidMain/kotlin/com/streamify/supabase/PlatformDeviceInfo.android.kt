package com.streamify.supabase

import android.os.Build

actual fun resolvePlatformDeviceInfo(): PlatformDeviceInfo {
    val model = "${Build.MANUFACTURER} ${Build.MODEL}".trim()
    val os = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
    val arch = Build.SUPPORTED_ABIS.firstOrNull() ?: System.getProperty("os.arch") ?: "unknown"
    return PlatformDeviceInfo(
        platform = "android",
        deviceModel = model,
        osVersion = os,
        architecture = arch,
        buildFlavor = "standard"
    )
}
