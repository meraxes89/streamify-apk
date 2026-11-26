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
    val graceHours: Int = 48,
    val channel: String = "beta",
    val gitSha: String = "",
    val recalledShas: List<String> = emptyList(),
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

enum class UpdateUrgency(val skipCooldownSeconds: Int) {
    SOFT(0),
    ACTIVE_5S(5),
    URGENT_10S(10),
    ENFORCED(0),
}

fun computeUpdateUrgency(
    firstSeenAtMs: Long,
    nowMs: Long,
    graceHours: Int,
    isBreaking: Boolean,
): UpdateUrgency {
    if (graceHours <= 0 || isBreaking) return UpdateUrgency.ENFORCED
    val elapsedMs = (nowMs - firstSeenAtMs).coerceAtLeast(0L)
    val graceMs = graceHours.toLong() * 3_600_000L
    if (elapsedMs >= graceMs) return UpdateUrgency.ENFORCED
    val elapsedHours = elapsedMs / 3_600_000L
    return when {
        elapsedHours < 12L -> UpdateUrgency.SOFT
        elapsedHours < 36L -> UpdateUrgency.ACTIVE_5S
        else -> UpdateUrgency.URGENT_10S
    }
}

fun remainingGraceHours(
    firstSeenAtMs: Long,
    nowMs: Long,
    graceHours: Int,
): Int {
    if (graceHours <= 0) return 0
    val graceMs = graceHours.toLong() * 3_600_000L
    val remainingMs = (firstSeenAtMs + graceMs - nowMs).coerceAtLeast(0L)
    return ((remainingMs + 3_599_999L) / 3_600_000L).toInt()
}

object UpdateStateHolder {
    var latestManifest: UpdateManifest? = null
    var isBreaking: Boolean = false
    var urgency: UpdateUrgency = UpdateUrgency.SOFT
    var remainingHours: Int = 48
}

