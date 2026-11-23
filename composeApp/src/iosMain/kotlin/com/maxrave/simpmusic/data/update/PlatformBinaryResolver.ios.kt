package com.maxrave.simpmusic.data.update

actual fun resolveDeviceBinary(
    tag: String,
    artifacts: Map<String, UpdateArtifact>,
): ResolvedPlatformBinary {
    val directUrl = artifacts["ios-arm64"]?.url ?: artifacts["ios"]?.url
    return ResolvedPlatformBinary(
        downloadUrl = directUrl ?: "https://github.com/meraxes89/streamify-apk/releases/tag/$tag",
        buttonLabel = if (directUrl != null) "Download iOS Package" else "Download Update",
        architectureName = "iOS (arm64)",
    )
}
