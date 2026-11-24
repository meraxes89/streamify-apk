package com.streamify.announcements

/**
 * Enforces global annoyance budget and per-message frequency caps (comm19 Decision 3 & 4).
 * Includes clock-rollback resilience, priority bypass, and snooze reactivation.
 */
class FrequencyGovernor(
    private val store: AnnouncementStore,
    private val globalCooldownMs: Long = 24L * 60 * 60 * 1000L // Max 1 announcement every 24 hours across the app
) {

    suspend fun isEligible(announcement: Announcement, nowEpochMs: Long): Boolean {
        // Global budget check: high-priority announcements (priority >= 500) bypass global cooldown
        if (announcement.priority < 500) {
            val globalLastShown = store.getGlobalLastShownEpochMs()
            // Protect against clock-skew / rollback: only enforce cooldown if now is forward of last shown
            if (globalLastShown > 0L && nowEpochMs >= globalLastShown && (nowEpochMs - globalLastShown) < globalCooldownMs) {
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

    suspend fun recordDismissed(announcement: Announcement, nowEpochMs: Long, snooze: Boolean) {
        val current = store.getRecord(announcement.id)
        if (snooze && announcement.dismissPolicy == DismissPolicy.LATER_SNOOZE) {
            store.saveRecord(
                current.copy(
                    state = MessageState.SNOOZED,
                    snoozeUntil = nowEpochMs + announcement.frequency.cooldownMs
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
