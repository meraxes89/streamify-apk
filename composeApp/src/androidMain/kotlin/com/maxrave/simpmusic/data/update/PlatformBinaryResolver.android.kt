package com.maxrave.simpmusic.data.update

import android.os.Build

actual fun resolveDeviceBinary(
    tag: String,
    artifacts: Map<String, UpdateArtifact>,
): ResolvedPlatformBinary {
    val abis = Build.SUPPORTED_ABIS?.toList().orEmpty().ifEmpty {
        listOfNotNull(
            @Suppress("DEPRECATION")
            Build.CPU_ABI,
            @Suppress("DEPRECATION")
            Build.CPU_ABI2,
        )
    }

    val isArm64 = abis.any { it.contains("arm64", ignoreCase = true) || it.contains("aarch64", ignoreCase = true) }
    val isArmV7 = abis.any { it.contains("armeabi", ignoreCase = true) || it.contains("armv7", ignoreCase = true) }
    val isX86_64 = abis.any { it.contains("x86_64", ignoreCase = true) }

    val isVivo = Build.MANUFACTURER.contains("vivo", ignoreCase = true) ||
        Build.BRAND.contains("vivo", ignoreCase = true) ||
        Build.FINGERPRINT.contains("vivo", ignoreCase = true)

    val releasePageUrl = "https://github.com/meraxes89/streamify-apk/releases/tag/$tag"

    return when {
        isVivo && isArm64 && artifacts.containsKey("android-vivo-arm64") -> {
            val url = artifacts["android-vivo-arm64"]?.url ?: releasePageUrl
            ResolvedPlatformBinary(
                downloadUrl = url,
                buttonLabel = "Download APK (Vivo ARM64)",
                architectureName = "Vivo ARM64",
            )
        }
        isVivo && artifacts.containsKey("android-vivo-universal") -> {
            val url = artifacts["android-vivo-universal"]?.url ?: releasePageUrl
            ResolvedPlatformBinary(
                downloadUrl = url,
                buttonLabel = "Download APK (Vivo Universal)",
                architectureName = "Vivo Universal",
            )
        }
        isArm64 -> {
            val url = artifacts["android-arm64-v8a"]?.url
                ?: artifacts["android-universal"]?.url
                ?: artifacts["android"]?.url
                ?: releasePageUrl
            ResolvedPlatformBinary(
                downloadUrl = url,
                buttonLabel = "Download APK (ARM64)",
                architectureName = "ARM64 (64-bit)",
            )
        }
        isArmV7 -> {
            val url = artifacts["android-armeabi-v7a"]?.url
                ?: artifacts["android-universal"]?.url
                ?: artifacts["android"]?.url
                ?: releasePageUrl
            ResolvedPlatformBinary(
                downloadUrl = url,
                buttonLabel = "Download APK (ARMv7)",
                architectureName = "ARMv7 (32-bit)",
            )
        }
        isX86_64 -> {
            val url = artifacts["android-x86_64"]?.url
                ?: artifacts["android-universal"]?.url
                ?: artifacts["android"]?.url
                ?: releasePageUrl
            ResolvedPlatformBinary(
                downloadUrl = url,
                buttonLabel = "Download APK (x86_64)",
                architectureName = "x86_64",
            )
        }
        else -> {
            val url = artifacts["android-universal"]?.url
                ?: artifacts["android"]?.url
                ?: releasePageUrl
            ResolvedPlatformBinary(
                downloadUrl = url,
                buttonLabel = "Download APK (Universal)",
                architectureName = "Universal",
            )
        }
    }
}
