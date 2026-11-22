package com.streamify.announcements

/**
 * Enforces global annoyance budget and per-message frequency caps (comm19 Decision 3 & 4).
 */
class FrequencyGovernor(
    private val store: AnnouncementStore,
    private val globalCooldownMs: Long = 24L * 60 * 60 * 1000L // Max 1 announcement every 24 hours across the app
) {

    suspend fun isEligible(announcement: Announcement, nowEpochMs: Long): Boolean {
        // Global budget check: has any announcement shown in the last 24h?
        val globalLastShown = store.getGlobalLastShownEpochMs()
        if (nowEpochMs - globalLastShown < globalCooldownMs) {
            return false
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

        // Snooze check
        if (record.state == MessageState.SNOOZED && nowEpochMs < record.snoozeUntil) {
            return false
        }

        // Impression cap check
        if (record.impressions >= announcement.frequency.maxImpressions) {
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
