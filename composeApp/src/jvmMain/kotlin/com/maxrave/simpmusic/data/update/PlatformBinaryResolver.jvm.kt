package com.maxrave.simpmusic.data.update

import java.io.File

actual fun resolveDeviceBinary(
    tag: String,
    artifacts: Map<String, UpdateArtifact>,
): ResolvedPlatformBinary {
    val osName = System.getProperty("os.name", "").lowercase()
    val cleanTag = tag.removePrefix("v")
    val baseDownloadUrl = "https://github.com/meraxes89/streamify-apk/releases/download/$tag"

    val isMac = osName.contains("mac") || osName.contains("darwin")
    val isWindows = osName.contains("win")
    val isLinux = osName.contains("linux")

    return when {
        isWindows -> {
            val url = artifacts["windows-x64"]?.url
                ?: artifacts["windows"]?.url
                ?: "$baseDownloadUrl/Streamify-$cleanTag.msi"
            ResolvedPlatformBinary(
                downloadUrl = url,
                buttonLabel = "Download Windows Installer (.msi)",
                architectureName = "Windows 64-bit",
            )
        }
        isMac -> {
            val url = artifacts["macos-universal"]?.url
                ?: artifacts["macos"]?.url
                ?: "$baseDownloadUrl/Streamify-$cleanTag.dmg"
            ResolvedPlatformBinary(
                downloadUrl = url,
                buttonLabel = "Download macOS DMG",
                architectureName = "macOS Universal",
            )
        }
        isLinux -> {
            val isAppImage = System.getenv("APPIMAGE") != null
            val isRpm = try {
                val osRelease = File("/etc/os-release")
                if (osRelease.exists()) {
                    val content = osRelease.readText().lowercase()
                    content.contains("fedora") || content.contains("rhel") ||
                        content.contains("centos") || content.contains("suse")
                } else {
                    false
                }
            } catch (_: Exception) {
                false
            }

            when {
                isAppImage -> {
                    val url = artifacts["linux-appimage"]?.url
                        ?: artifacts["linux"]?.url
                        ?: "$baseDownloadUrl/Streamify-$cleanTag-x86_64.AppImage"
                    ResolvedPlatformBinary(
                        downloadUrl = url,
                        buttonLabel = "Download AppImage (.AppImage)",
                        architectureName = "Linux AppImage",
                    )
                }
                isRpm -> {
                    val url = artifacts["linux-rpm"]?.url
                        ?: artifacts["linux-appimage"]?.url
                        ?: artifacts["linux"]?.url
                        ?: "$baseDownloadUrl/streamify-$cleanTag.x86_64.rpm"
                    ResolvedPlatformBinary(
                        downloadUrl = url,
                        buttonLabel = "Download RPM (.rpm)",
                        architectureName = "Linux RPM",
                    )
                }
                else -> {
                    val url = artifacts["linux-deb"]?.url
                        ?: artifacts["linux-appimage"]?.url
                        ?: artifacts["linux"]?.url
                        ?: "$baseDownloadUrl/streamify_${cleanTag}_amd64.deb"
                    ResolvedPlatformBinary(
                        downloadUrl = url,
                        buttonLabel = "Download DEB (.deb)",
                        architectureName = "Linux DEB",
                    )
                }
            }
        }
        else -> {
            ResolvedPlatformBinary(
                downloadUrl = "https://github.com/meraxes89/streamify-apk/releases/tag/$tag",
                buttonLabel = "Download Update",
                architectureName = "Desktop",
            )
        }
    }
}
