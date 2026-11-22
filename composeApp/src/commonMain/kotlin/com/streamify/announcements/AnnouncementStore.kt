package com.streamify.announcements

import com.maxrave.domain.manager.DataStoreManager
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Persists announcement lifecycle records in DataStore.
 */
class AnnouncementStore(
    private val dataStoreManager: DataStoreManager
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val prefPrefix = "announcement_record_"
    private val globalLastShownKey = "announcement_global_last_shown"

    suspend fun getRecord(id: String): AnnouncementRecord {
        return try {
            val raw = dataStoreManager.getString(prefPrefix + id).first()
            if (!raw.isNullOrBlank()) {
                json.decodeFromString<AnnouncementRecord>(raw)
            } else {
                AnnouncementRecord(id = id)
            }
        } catch (_: Exception) {
            AnnouncementRecord(id = id)
        }
    }

    suspend fun saveRecord(record: AnnouncementRecord) {
        try {
            val serialized = json.encodeToString(record)
            dataStoreManager.putString(prefPrefix + record.id, serialized)
        } catch (_: Exception) {}
    }

    suspend fun getGlobalLastShownEpochMs(): Long {
        return try {
            dataStoreManager.getString(globalLastShownKey).first()?.toLongOrNull() ?: 0L
        } catch (_: Exception) {
            0L
        }
    }

    suspend fun updateGlobalLastShownEpochMs(epochMs: Long) {
        try {
            dataStoreManager.putString(globalLastShownKey, epochMs.toString())
        } catch (_: Exception) {}
    }
}
