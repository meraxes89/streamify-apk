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
    val locales: List<String> = emptyList()
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
