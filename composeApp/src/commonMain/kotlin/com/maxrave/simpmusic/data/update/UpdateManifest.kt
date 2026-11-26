package com.maxrave.simpmusic.data.update

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlin.concurrent.Volatile

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
    val signatureHex: String = "",
)

sealed interface UpdateResult {
    data class Available(
        val manifest: UpdateManifest,
        val isPolicyVerified: Boolean = false,
    ) : UpdateResult
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

data class UpdatePolicyState(
    val latestManifest: UpdateManifest? = null,
    val isPolicyVerified: Boolean = false,
    val isBreaking: Boolean = false,
    val urgency: UpdateUrgency = UpdateUrgency.SOFT,
    val remainingHours: Int = 48,
) {
    /**
     * Capability gate for online streaming: only enforced when a cryptographically verified
     * policy reaches [UpdateUrgency.ENFORCED]. Offline downloaded tracks always remain playable.
     */
    val isOnlineStreamingBlocked: Boolean
        get() = isPolicyVerified && urgency == UpdateUrgency.ENFORCED
}

fun evaluateUpdatePolicy(
    manifest: UpdateManifest?,
    installedCode: Int,
    installedSha: String = "",
    firstSeenAtMs: Long,
    nowMs: Long,
    isPolicyVerified: Boolean,
): UpdatePolicyState {
    if (manifest == null) return UpdatePolicyState()
    val isRecalled = installedSha.isNotBlank() && manifest.recalledShas.contains(installedSha)
    val isBreaking =
        manifest.isBreaking ||
            isRecalled ||
            (manifest.minSupportedVersionCode > 0 && installedCode < manifest.minSupportedVersionCode)
    val urgency = computeUpdateUrgency(firstSeenAtMs, nowMs, manifest.graceHours, isBreaking)
    val remaining = remainingGraceHours(firstSeenAtMs, nowMs, manifest.graceHours)
    return UpdatePolicyState(
        latestManifest = manifest,
        isPolicyVerified = isPolicyVerified,
        isBreaking = isBreaking,
        urgency = urgency,
        remainingHours = remaining,
    )
}

object UpdatePreferences {
    const val KEY_NIGHTLY_ENABLED = "STREAMIFY_NIGHTLY_UPDATES"
    const val KEY_AUTO_INSTALL_ENABLED = "STREAMIFY_AUTO_INSTALL_UPDATES"
}

/**
 * Returns true when an already-downloaded verified update should auto-install:
 * either playback is paused/stopped, or the player/user shifted to a different track.
 */
fun shouldTriggerAutoInstall(
    initialMediaId: String?,
    currentMediaId: String?,
    isPlaying: Boolean?,
): Boolean {
    if (isPlaying != true) return true
    if (!initialMediaId.isNullOrBlank() && !currentMediaId.isNullOrBlank() && currentMediaId != initialMediaId) {
        return true
    }
    return false
}

object UpdateStateHolder {
    private val _state = MutableStateFlow(UpdatePolicyState())
    val state: StateFlow<UpdatePolicyState> = _state.asStateFlow()

    @Volatile
    var nightlyEnabled: Boolean = false

    @Volatile
    var autoInstallEnabled: Boolean = true

    val latestManifest: UpdateManifest? get() = _state.value.latestManifest
    val isPolicyVerified: Boolean get() = _state.value.isPolicyVerified
    val isBreaking: Boolean get() = _state.value.isBreaking
    val urgency: UpdateUrgency get() = _state.value.urgency
    val remainingHours: Int get() = _state.value.remainingHours
    val isOnlineStreamingBlocked: Boolean get() = _state.value.isOnlineStreamingBlocked

    fun update(newState: UpdatePolicyState) {
        _state.value = newState
    }

    fun clear() {
        _state.value = UpdatePolicyState()
    }
}

