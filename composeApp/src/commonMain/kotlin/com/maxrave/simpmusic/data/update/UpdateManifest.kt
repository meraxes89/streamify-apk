package com.maxrave.simpmusic.data.update

import kotlinx.serialization.Serializable

@Serializable
data class UpdateArtifact(
    val url: String = "",
    val sha256: String = "",
)

@Serializable
data class UpdateManifest(
    val schemaVersion: Int = 1,
    val version: String = "",
    val versionCode: Int = 0,
    val minSupportedVersionCode: Int = 0,
    val isBreaking: Boolean = false,
    val channel: String = "beta",
    val publishedAt: String? = null,
    val changelog: String = "",
    val artifacts: Map<String, UpdateArtifact> = emptyMap(),
)

sealed interface UpdateResult {
    data class Available(val manifest: UpdateManifest) : UpdateResult
    data class TagOnly(val tag: String) : UpdateResult
    data object UpToDate : UpdateResult
    data class Error(val message: String) : UpdateResult
}

object UpdateStateHolder {
    var latestManifest: UpdateManifest? = null
    var isBreaking: Boolean = false
}

