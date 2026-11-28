package com.maxrave.simpmusic.data.update

import android.content.Context
import android.os.Build
import com.maxrave.common.AppIdentity
import org.koin.core.context.GlobalContext

private const val VIVO_PACKAGE_ID = "com.soundcloud.android"

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

    val installedPackageId =
        runCatching {
            GlobalContext.getOrNull()?.let { koin ->
                koin.getOrNull<AppIdentity>()?.applicationId ?: koin.getOrNull<Context>()?.packageName
            }
        }.getOrNull()

    val isVivoHardware = Build.MANUFACTURER.contains("vivo", ignoreCase = true) ||
        Build.BRAND.contains("vivo", ignoreCase = true) ||
        Build.FINGERPRINT.contains("vivo", ignoreCase = true)

    // Match the currently installed package flavor first (Vivo Origin Island uses com.spotify.music).
    // Only fall back to hardware heuristic when Koin/Context is unavailable (e.g., unit tests).
    val isVivoFlavor =
        when (installedPackageId) {
            VIVO_PACKAGE_ID -> true
            null -> isVivoHardware
            else -> false
        }

    val hasVivoArtifact = artifacts.containsKey("android-vivo-arm64") ||
        artifacts.containsKey("android-vivo-arm64-v8a") ||
        artifacts.containsKey("android-vivo-armeabi-v7a") ||
        artifacts.containsKey("android-vivo-armv7") ||
        artifacts.containsKey("android-vivo-universal") ||
        artifacts.containsKey("android-vivo")

    val releasePageUrl = "https://github.com/meraxes89/streamify-apk/releases/tag/$tag"

    return when {
        isVivoFlavor && hasVivoArtifact -> {
            val url: String = (
                if (isArm64) {
                    artifacts["android-vivo-arm64"]?.url
                        ?: artifacts["android-vivo-arm64-v8a"]?.url
                        ?: artifacts["android-vivo-universal"]?.url
                        ?: artifacts["android-vivo"]?.url
                } else if (isArmV7) {
                    artifacts["android-vivo-armeabi-v7a"]?.url
                        ?: artifacts["android-vivo-armv7"]?.url
                        ?: artifacts["android-vivo-universal"]?.url
                        ?: artifacts["android-vivo"]?.url
                } else {
                    artifacts["android-vivo-universal"]?.url
                        ?: artifacts["android-vivo"]?.url
                }
            ) ?: releasePageUrl

            val archName = when {
                isArm64 -> "Vivo ARM64"
                isArmV7 -> "Vivo ARMv7"
                else -> "Vivo Edition"
            }
            ResolvedPlatformBinary(
                downloadUrl = url,
                buttonLabel = "Download APK ($archName)",
                architectureName = archName,
            )
        }
        isVivoFlavor && !hasVivoArtifact -> {
            // Never fall back to com.streamify.app APK when com.spotify.music (Vivo) is installed.
            ResolvedPlatformBinary(
                downloadUrl = releasePageUrl,
                buttonLabel = "Download Vivo APK",
                architectureName = "Vivo Edition",
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
