package com.streamify.announcements

import com.maxrave.domain.manager.DataStoreManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlin.time.Clock

/**
 * Sovereign In-App Message Engine Facade (comm19 Phase 1 & 2).
 * Integrates Local Defaults, Signed Remote Source, Targeting, Frequency Governance,
 * Master Launch Popup Switch, and Persistent Inbox.
 */
class AnnouncementEngine(
    private val dataStoreManager: DataStoreManager,
    private val remoteSource: RemoteAnnouncementSource = RemoteAnnouncementSource(dataStoreManager),
    private val inboxRepository: AnnouncementInboxRepository = AnnouncementInboxRepository(dataStoreManager),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) {
    private val store = AnnouncementStore(dataStoreManager)
    private val frequencyGovernor = FrequencyGovernor(store)
    private val arbiter = PresentationArbiter()
    private var cachedGovernance: GovernanceConfig = GovernanceConfig()

    val activeAnnouncement: StateFlow<Announcement?> = arbiter.activeCommand

    /**
     * Evaluates local candidate announcements and requests presentation if context allows.
     * Enforces the master popupModalEnabled statement and dynamic governance before triggering any modal presentation.
     * Also refreshes the remote source and updates the persistent inbox asynchronously.
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

                // Fetch remote manifest and evaluate master boolean toggle + dynamic governance
                val manifestResult = remoteSource.fetchManifest()
                cachedGovernance = manifestResult.governance
                DeepLinkAllowlist.setDynamicAllowedDomains(manifestResult.governance.allowedExtraDomains)

                // If master switch is false, immediately silence any active modal and abort presentation
                if (!manifestResult.popupModalEnabled) {
                    arbiter.dismissActive()
                    if (manifestResult.announcements.isNotEmpty()) {
                        inboxRepository.saveInboxAnnouncements(manifestResult.announcements)
                    }
                    return@launch
                }

                val combined = (LocalAnnouncementDefaults.ALL + manifestResult.announcements).distinctBy { it.id }

                // Sync persistent inbox
                inboxRepository.saveInboxAnnouncements(combined)

                val eligible = combined.filter { announcement ->
                    TargetingEvaluator.isEligible(
                        announcement.targeting,
                        currentVersion,
                        currentPlatform,
                        currentLocale
                    ) && frequencyGovernor.isEligible(announcement, nowEpochMs, manifestResult.governance)
                }.sortedByDescending { it.priority }

                val topCandidate = eligible.firstOrNull() ?: return@launch
                val presented = arbiter.tryPresent(topCandidate, uiContext)
                if (presented) {
                    frequencyGovernor.recordShown(topCandidate, nowEpochMs)
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
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
            } catch (e: Exception) {
                if (e is CancellationException) throw e
            }
        }
    }

    fun onDismiss(announcement: Announcement, snooze: Boolean) {
        scope.launch {
            try {
                arbiter.dismissActive()
                val nowEpochMs = Clock.System.now().toEpochMilliseconds()
                frequencyGovernor.recordDismissed(announcement, nowEpochMs, snooze = snooze, governance = cachedGovernance)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
            }
        }
    }

    fun onAction(announcement: Announcement) {
        scope.launch {
            try {
                arbiter.dismissActive()
                val nowEpochMs = Clock.System.now().toEpochMilliseconds()
                frequencyGovernor.recordActed(announcement, nowEpochMs)
                inboxRepository.markAsRead(announcement.id)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
            }
        }
    }
}
