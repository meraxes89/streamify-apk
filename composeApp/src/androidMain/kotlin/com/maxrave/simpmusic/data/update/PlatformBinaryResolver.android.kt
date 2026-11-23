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

    val baseDownloadUrl = "https://github.com/meraxes89/streamify-apk/releases/download/$tag"

    return when {
        isArm64 -> {
            val url = artifacts["android-arm64-v8a"]?.url
                ?: "$baseDownloadUrl/Streamify-$tag-arm64-v8a-release.apk"
            ResolvedPlatformBinary(
                downloadUrl = url,
                buttonLabel = "Download APK (ARM64)",
                architectureName = "ARM64 (64-bit)",
            )
        }
        isArmV7 -> {
            val url = artifacts["android-armeabi-v7a"]?.url
                ?: "$baseDownloadUrl/Streamify-$tag-armeabi-v7a-release.apk"
            ResolvedPlatformBinary(
                downloadUrl = url,
                buttonLabel = "Download APK (ARMv7)",
                architectureName = "ARMv7 (32-bit)",
            )
        }
        isX86_64 -> {
            val url = artifacts["android-x86_64"]?.url
                ?: "$baseDownloadUrl/Streamify-$tag-x86_64-release.apk"
            ResolvedPlatformBinary(
                downloadUrl = url,
                buttonLabel = "Download APK (x86_64)",
                architectureName = "x86_64",
            )
        }
        else -> {
            val url = artifacts["android-universal"]?.url
                ?: artifacts["android"]?.url
                ?: "$baseDownloadUrl/Streamify-$tag.apk"
            ResolvedPlatformBinary(
                downloadUrl = url,
                buttonLabel = "Download APK (Universal)",
                architectureName = "Universal",
            )
        }
    }
}
