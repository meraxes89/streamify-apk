package com.streamify.announcements

import com.maxrave.domain.manager.DataStoreManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds

/**
 * Sovereign In-App Message Engine Facade (comm19).
 * Coordinates targeting, frequency governance, and safe presentation arbitration.
 */
class AnnouncementEngine(
    private val dataStoreManager: DataStoreManager,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) {
    private val store = AnnouncementStore(dataStoreManager)
    private val frequencyGovernor = FrequencyGovernor(store)
    private val arbiter = PresentationArbiter()

    val activeAnnouncement: StateFlow<Announcement?> = arbiter.activeCommand

    /**
     * Evaluates local candidate announcements and requests presentation if context allows.
     */
    fun evaluateCandidates(
        currentVersion: String,
        currentPlatform: String,
        currentLocale: String,
        uiContext: UiContext
    ) {
        scope.launch {
            try {
                val nowEpochMs = Clock.System.now().toEpochMilliseconds()

                val eligible = LocalAnnouncementDefaults.ALL.filter { announcement ->
                    TargetingEvaluator.isEligible(
                        announcement.targeting,
                        currentVersion,
                        currentPlatform,
                        currentLocale
                    ) && frequencyGovernor.isEligible(announcement, nowEpochMs)
                }.sortedByDescending { it.priority }

                val topCandidate = eligible.firstOrNull() ?: return@launch
                val presented = arbiter.tryPresent(topCandidate, uiContext)
                if (presented) {
                    frequencyGovernor.recordShown(topCandidate, nowEpochMs)
                }
            } catch (_: Exception) {
                // Fail-safe to total silence (comm19 Decision 7)
            }
        }
    }

    fun onContextChanged(uiContext: UiContext) {
        scope.launch {
            try {
                val presented = arbiter.onContextChanged(uiContext)
                if (presented != null) {
                    val nowEpochMs = Clock.System.now().toEpochMilliseconds()
                    frequencyGovernor.recordShown(presented, nowEpochMs)
                }
            } catch (_: Exception) {}
        }
    }

    fun onDismiss(announcement: Announcement, snooze: Boolean) {
        scope.launch {
            try {
                arbiter.dismissActive()
                val nowEpochMs = Clock.System.now().toEpochMilliseconds()
                frequencyGovernor.recordDismissed(announcement, nowEpochMs, snooze = snooze)
            } catch (_: Exception) {}
        }
    }

    fun onAction(announcement: Announcement) {
        scope.launch {
            try {
                arbiter.dismissActive()
                val nowEpochMs = Clock.System.now().toEpochMilliseconds()
                frequencyGovernor.recordActed(announcement, nowEpochMs)
            } catch (_: Exception) {}
        }
    }
}
