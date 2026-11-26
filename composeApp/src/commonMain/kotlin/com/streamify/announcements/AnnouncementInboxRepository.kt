package com.streamify.announcements

import com.maxrave.domain.manager.DataStoreManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Manages the persistent "What's New / Announcements Inbox"
 * ensuring dismissed announcements stay discoverable in Settings.
 */
class AnnouncementInboxRepository(
    private val dataStoreManager: DataStoreManager
) {
    private val mutex = Mutex()
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
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            LocalAnnouncementDefaults.ALL
        }
    }

    suspend fun saveInboxAnnouncements(announcements: List<Announcement>) = mutex.withLock {
        try {
            val serialized = json.encodeToString(announcements)
            dataStoreManager.putString(inboxListKey, serialized)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
        }
    }

    suspend fun getReadAnnouncementIds(): Set<String> {
        return try {
            val raw = dataStoreManager.getString(readIdsKey).first()
            if (!raw.isNullOrBlank()) {
                json.decodeFromString<Set<String>>(raw)
            } else {
                emptySet()
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            emptySet()
        }
    }

    suspend fun markAsRead(id: String) = mutex.withLock {
        try {
            val current = getReadAnnouncementIds().toMutableSet()
            current.add(id)
            dataStoreManager.putString(readIdsKey, json.encodeToString(current))
        } catch (e: Exception) {
            if (e is CancellationException) throw e
        }
    }
}
