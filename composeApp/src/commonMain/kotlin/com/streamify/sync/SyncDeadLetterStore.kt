package com.streamify.sync

import com.maxrave.domain.manager.DataStoreManager
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Durable dead-letter quarantine for rejected outbox `changeId`s (S11 / #371).
 *
 * Keeps rejected ops out of `getPending()` without calling `SyncDao.markSent(ids, -1L)`,
 * so rejected changes are neither falsely marked sent nor allowed to stall later ops.
 */
internal object SyncDeadLetterStore {
    private const val KEY_DEAD_LETTER_IDS = "streamify_sync_dead_letter_ids_v1"
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }

    @kotlin.concurrent.Volatile
    private var cachedIds: Set<String>? = null

    internal fun decodeIds(raw: String?): Set<String> =
        if (!raw.isNullOrBlank()) {
            runCatching { json.decodeFromString<Set<String>>(raw) }.getOrDefault(emptySet())
        } else {
            emptySet()
        }

    internal fun encodeIds(ids: Set<String>): String = json.encodeToString(ids)

    suspend fun quarantinedIds(dataStoreManager: DataStoreManager): Set<String> = mutex.withLock {
        cachedIds?.let { return@withLock it }
        val parsed = decodeIds(dataStoreManager.getString(KEY_DEAD_LETTER_IDS).firstOrNull())
        cachedIds = parsed
        parsed
    }

    suspend fun quarantine(dataStoreManager: DataStoreManager, changeIds: List<String>) {
        if (changeIds.isEmpty()) return
        mutex.withLock {
            val current = cachedIds ?: decodeIds(dataStoreManager.getString(KEY_DEAD_LETTER_IDS).firstOrNull())
            val updated = current + changeIds
            cachedIds = updated
            dataStoreManager.putString(KEY_DEAD_LETTER_IDS, encodeIds(updated))
        }
    }

    suspend fun clear(dataStoreManager: DataStoreManager) {
        mutex.withLock {
            cachedIds = emptySet()
            dataStoreManager.putString(KEY_DEAD_LETTER_IDS, "[]")
        }
    }

    internal fun resetCacheForTesting() {
        cachedIds = null
    }
}
