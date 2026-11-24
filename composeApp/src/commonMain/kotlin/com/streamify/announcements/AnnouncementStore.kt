package com.streamify.announcements

import com.maxrave.domain.manager.DataStoreManager
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Persists announcement lifecycle records in DataStore, or memory when unprovided.
 */
open class AnnouncementStore(
    private val dataStoreManager: DataStoreManager? = null
) {
    private val inMemoryMap = mutableMapOf<String, String>()
    private val json = Json { ignoreUnknownKeys = true }
    private val prefPrefix = "announcement_record_"
    private val globalLastShownKey = "announcement_global_last_shown"

    open suspend fun getRecord(id: String): AnnouncementRecord {
        return try {
            val raw = dataStoreManager?.getString(prefPrefix + id)?.first() ?: inMemoryMap[prefPrefix + id]
            if (!raw.isNullOrBlank()) {
                json.decodeFromString<AnnouncementRecord>(raw)
            } else {
                AnnouncementRecord(id = id)
            }
        } catch (_: Exception) {
            AnnouncementRecord(id = id)
        }
    }

    open suspend fun saveRecord(record: AnnouncementRecord) {
        try {
            val serialized = json.encodeToString(record)
            if (dataStoreManager != null) {
                dataStoreManager.putString(prefPrefix + record.id, serialized)
            } else {
                inMemoryMap[prefPrefix + record.id] = serialized
            }
        } catch (_: Exception) {}
    }

    open suspend fun getGlobalLastShownEpochMs(): Long {
        return try {
            (dataStoreManager?.getString(globalLastShownKey)?.first() ?: inMemoryMap[globalLastShownKey])?.toLongOrNull() ?: 0L
        } catch (_: Exception) {
            0L
        }
    }

    open suspend fun updateGlobalLastShownEpochMs(epochMs: Long) {
        try {
            if (dataStoreManager != null) {
                dataStoreManager.putString(globalLastShownKey, epochMs.toString())
            } else {
                inMemoryMap[globalLastShownKey] = epochMs.toString()
            }
        } catch (_: Exception) {}
    }
}
