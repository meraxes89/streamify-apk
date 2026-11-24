package com.maxrave.simpmusic.data.update

data class ResolvedPlatformBinary(
    val downloadUrl: String,
    val buttonLabel: String,
    val architectureName: String,
)

expect fun resolveDeviceBinary(
    tag: String,
    artifacts: Map<String, UpdateArtifact> = emptyMap(),
): ResolvedPlatformBinary
