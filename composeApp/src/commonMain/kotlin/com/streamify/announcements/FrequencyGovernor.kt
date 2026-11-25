package com.streamify.announcements

/**
 * Enforces global annoyance budget and per-message frequency caps (comm19 Decision 3 & 4).
 * Includes clock-rollback resilience, priority bypass, and snooze reactivation.
 */
class FrequencyGovernor(
    private val store: AnnouncementStore,
    private val globalCooldownMs: Long = 24L * 60 * 60 * 1000L // Default fallback: Max 1 announcement every 24 hours across the app
) {

    suspend fun isEligible(
        announcement: Announcement,
        nowEpochMs: Long,
        governance: GovernanceConfig = GovernanceConfig(globalCooldownMs = globalCooldownMs)
    ): Boolean {
        val effectiveEmergencyThreshold = governance.emergencyPriorityThreshold
        // Defensive clamping: coerce cooldown to safe range (0 to 365 days)
        val effectiveCooldownMs = governance.globalCooldownMs.coerceIn(0L, 365L * 24 * 60 * 60 * 1000L)

        // Global budget check: high-priority announcements bypass global cooldown
        if (announcement.priority < effectiveEmergencyThreshold) {
            val globalLastShown = store.getGlobalLastShownEpochMs()
            // Protect against clock-skew / rollback: only enforce cooldown if now is forward of last shown
            if (globalLastShown > 0L && nowEpochMs >= globalLastShown && (nowEpochMs - globalLastShown) < effectiveCooldownMs) {
                return false
            }
        }

        // Schedule check
        if (nowEpochMs < announcement.startAtEpochMs || nowEpochMs > announcement.endAtEpochMs) {
            return false
        }

        val record = store.getRecord(announcement.id)

        // Permanent dismissal, already acted upon, or expired
        if (record.state == MessageState.DISMISSED ||
            record.state == MessageState.ACTED ||
            record.state == MessageState.EXPIRED
        ) {
            return false
        }

        // Snooze check: when snoozed, check if cooldown elapsed. If it has elapsed, allow reshow.
        if (record.state == MessageState.SNOOZED) {
            if (nowEpochMs < record.snoozeUntil) {
                return false
            }
        } else if (record.impressions >= announcement.frequency.maxImpressions) {
            return false
        }

        return true
    }

    suspend fun recordShown(announcement: Announcement, nowEpochMs: Long) {
        store.updateGlobalLastShownEpochMs(nowEpochMs)
        val current = store.getRecord(announcement.id)
        store.saveRecord(
            current.copy(
                state = MessageState.SHOWN,
                impressions = current.impressions + 1,
                lastShownAt = nowEpochMs
            )
        )
    }

    suspend fun recordDismissed(
        announcement: Announcement,
        nowEpochMs: Long,
        snooze: Boolean,
        governance: GovernanceConfig = GovernanceConfig()
    ) {
        val current = store.getRecord(announcement.id)
        if (snooze && announcement.dismissPolicy == DismissPolicy.LATER_SNOOZE) {
            val snoozeCooldown = if (announcement.frequency.cooldownMs > 0L) {
                announcement.frequency.cooldownMs
            } else {
                governance.defaultSnoozeCooldownMs.coerceAtLeast(0L)
            }
            store.saveRecord(
                current.copy(
                    state = MessageState.SNOOZED,
                    snoozeUntil = nowEpochMs + snoozeCooldown
                )
            )
        } else {
            store.saveRecord(
                current.copy(
                    state = MessageState.DISMISSED
                )
            )
        }
    }

    suspend fun recordActed(announcement: Announcement, nowEpochMs: Long) {
        val current = store.getRecord(announcement.id)
        store.saveRecord(
            current.copy(
                state = MessageState.ACTED,
                actedAt = nowEpochMs
            )
        )
    }
}
