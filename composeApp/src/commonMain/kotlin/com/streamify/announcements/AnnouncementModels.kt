package com.streamify.announcements

import kotlinx.serialization.Serializable

enum class PresentationType {
    MODAL,
    SHEET,
    BANNER,
    SNACKBAR,
    INBOX,
    SILENT
}

enum class MessageState {
    ELIGIBLE,
    SHOWN,
    SNOOZED,
    DISMISSED,
    ACTED,
    EXPIRED
}

enum class DismissPolicy {
    LATER_SNOOZE,
    PERMANENT,
    ACT_CONSUMES
}

@Serializable
sealed class AnnouncementAction {
    @Serializable
    data class DeepLink(val url: String) : AnnouncementAction()

    @Serializable
    data class InAppRoute(val route: String) : AnnouncementAction()

    @Serializable
    data object None : AnnouncementAction()
}

@Serializable
data class LocalizedContent(
    val title: String,
    val body: String,
    val bannerUrl: String? = null,
    val actionLabel: String? = null,
    val contentDescription: String? = null
)

@Serializable
data class Targeting(
    val minVersion: String? = null,
    val maxVersion: String? = null,
    val platforms: List<String> = emptyList(), // "android", "desktop", "wasm"
    val locales: List<String> = emptyList(),
    val channels: List<String> = emptyList(), // e.g. "stable", "nightly", "vivo"
    val minPlays: Int? = null,
    val maxPlays: Int? = null,
    val minListeningHours: Double? = null,
    val favoriteGenres: List<String> = emptyList(),
    val minAccountAgeDays: Int? = null,
    val maxAccountAgeDays: Int? = null,
    val minDormantDays: Int? = null,
    val requireAdmin: Boolean? = null,
    val requireAuthenticated: Boolean? = null,
    val targetUserHashes: List<String> = emptyList(), // lowercase SHA-256("$announcementId:$userId")
    val rolloutPercent: Int? = null, // 0..100 deterministic per-user rollout
)

/**
 * Local user profile & engagement snapshot used for client-side targeted announcement evaluation.
 */
data class UserProfileContext(
    val userId: String? = null,
    val displayName: String? = null,
    val isAdmin: Boolean = false,
    val totalPlays: Int = 0,
    val listeningSeconds: Long = 0L,
    val favoriteGenre: String? = null,
    val topTrack: String? = null,
    val createdAtEpochMs: Long? = null,
    val lastActiveAtEpochMs: Long? = null,
    val channel: String? = null,
    val isAuthenticated: Boolean = false,
)

@Serializable
data class FrequencyCap(
    val maxImpressions: Int = 1,
    val cooldownMs: Long = 7L * 24 * 60 * 60 * 1000L // 7 days default snooze cooldown
)

@Serializable
data class Announcement(
    val id: String,
    val schemaVersion: Int = 1,
    val priority: Int = 0,
    val presentation: PresentationType = PresentationType.MODAL,
    val content: Map<String, LocalizedContent>,
    val action: AnnouncementAction = AnnouncementAction.None,
    val targeting: Targeting = Targeting(),
    val frequency: FrequencyCap = FrequencyCap(),
    val dismissPolicy: DismissPolicy = DismissPolicy.LATER_SNOOZE,
    val startAtEpochMs: Long = 0L,
    val endAtEpochMs: Long = Long.MAX_VALUE
)

@Serializable
data class AnnouncementRecord(
    val id: String,
    val state: MessageState = MessageState.ELIGIBLE,
    val impressions: Int = 0,
    val lastShownAt: Long = 0L,
    val snoozeUntil: Long = 0L,
    val actedAt: Long = 0L
)

data class UiContext(
    val isPlaying: Boolean = false,
    val isModalActive: Boolean = false,
    val isOnboarding: Boolean = false
)
