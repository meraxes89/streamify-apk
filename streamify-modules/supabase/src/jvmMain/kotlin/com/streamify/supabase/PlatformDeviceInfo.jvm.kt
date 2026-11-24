package com.streamify.supabase

actual fun resolvePlatformDeviceInfo(): PlatformDeviceInfo {
    val osName = System.getProperty("os.name") ?: "Desktop"
    val osVersion = System.getProperty("os.version") ?: ""
    val arch = System.getProperty("os.arch") ?: ""
    val host = try {
        java.net.InetAddress.getLocalHost().hostName
    } catch (_: Exception) {
        ""
    }
    val model = if (host.isNotBlank()) "$osName ($host)" else osName
    return PlatformDeviceInfo(
        platform = "desktop",
        deviceModel = model,
        osVersion = osVersion,
        architecture = arch,
        buildFlavor = "desktop"
    )
}
