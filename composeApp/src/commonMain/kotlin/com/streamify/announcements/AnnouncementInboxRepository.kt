package com.streamify.announcements

import com.maxrave.domain.manager.DataStoreManager
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Manages the persistent "What's New / Announcements Inbox"
 * ensuring dismissed announcements stay discoverable in Settings.
 */
class AnnouncementInboxRepository(
    private val dataStoreManager: DataStoreManager
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val inboxListKey = "streamify_announcements_inbox_list"
    private val readIdsKey = "streamify_announcements_inbox_read_ids"

    suspend fun getAllInboxAnnouncements(): List<Announcement> {
        return try {
            val raw = dataStoreManager.getString(inboxListKey).first()
            if (!raw.isNullOrBlank()) {
                json.decodeFromString<List<Announcement>>(raw)
            } else {
                LocalAnnouncementDefaults.ALL
            }
        } catch (_: Exception) {
            LocalAnnouncementDefaults.ALL
        }
    }

    suspend fun saveInboxAnnouncements(announcements: List<Announcement>) {
        try {
            val serialized = json.encodeToString(announcements)
            dataStoreManager.putString(inboxListKey, serialized)
        } catch (_: Exception) {}
    }

    suspend fun getReadAnnouncementIds(): Set<String> {
        return try {
            val raw = dataStoreManager.getString(readIdsKey).first()
            if (!raw.isNullOrBlank()) {
                json.decodeFromString<Set<String>>(raw)
            } else {
                emptySet()
            }
        } catch (_: Exception) {
            emptySet()
        }
    }

    suspend fun markAsRead(id: String) {
        try {
            val current = getReadAnnouncementIds().toMutableSet()
            current.add(id)
            dataStoreManager.putString(readIdsKey, json.encodeToString(current))
        } catch (_: Exception) {}
    }
}
