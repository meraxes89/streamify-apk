package com.streamify.announcements

import com.maxrave.domain.manager.DataStoreManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Persists announcement lifecycle records in DataStore, or memory when unprovided.
 */
open class AnnouncementStore(
    private val dataStoreManager: DataStoreManager? = null
) {
    private val mutex = Mutex()
    private val inMemoryMap = mutableMapOf<String, String>()
    private val json = Json { ignoreUnknownKeys = true }
    private val prefPrefix = "announcement_record_"
    private val globalLastShownKey = "announcement_global_last_shown"

    open suspend fun getRecord(id: String): AnnouncementRecord = mutex.withLock {
        try {
            val raw = dataStoreManager?.getString(prefPrefix + id)?.first() ?: inMemoryMap[prefPrefix + id]
            if (!raw.isNullOrBlank()) {
                json.decodeFromString<AnnouncementRecord>(raw)
            } else {
                AnnouncementRecord(id = id)
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            AnnouncementRecord(id = id)
        }
    }

    open suspend fun saveRecord(record: AnnouncementRecord) = mutex.withLock {
        try {
            val serialized = json.encodeToString(record)
            if (dataStoreManager != null) {
                dataStoreManager.putString(prefPrefix + record.id, serialized)
            } else {
                inMemoryMap[prefPrefix + record.id] = serialized
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
        }
    }

    open suspend fun getGlobalLastShownEpochMs(): Long = mutex.withLock {
        try {
            (dataStoreManager?.getString(globalLastShownKey)?.first() ?: inMemoryMap[globalLastShownKey])?.toLongOrNull() ?: 0L
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            0L
        }
    }

    open suspend fun updateGlobalLastShownEpochMs(epochMs: Long) = mutex.withLock {
        try {
            if (dataStoreManager != null) {
                dataStoreManager.putString(globalLastShownKey, epochMs.toString())
            } else {
                inMemoryMap[globalLastShownKey] = epochMs.toString()
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
        }
    }
}
