package com.maxrave.simpmusic.data.update

actual fun resolveDeviceBinary(
    tag: String,
    artifacts: Map<String, UpdateArtifact>,
): ResolvedPlatformBinary {
    return ResolvedPlatformBinary(
        downloadUrl = "https://github.com/meraxes89/streamify-apk/releases/tag/$tag",
        buttonLabel = "Download Update",
        architectureName = "iOS",
    )
}
