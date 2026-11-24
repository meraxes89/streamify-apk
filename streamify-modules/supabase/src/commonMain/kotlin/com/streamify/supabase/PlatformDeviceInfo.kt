package com.streamify.supabase

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class PlatformDeviceInfo(
    val platform: String,
    @SerialName("device_model")
    val deviceModel: String,
    @SerialName("os_version")
    val osVersion: String,
    val architecture: String,
    @SerialName("build_flavor")
    val buildFlavor: String = "standard"
)

expect fun resolvePlatformDeviceInfo(): PlatformDeviceInfo
