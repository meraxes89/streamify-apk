package com.streamify.sync.v5

import com.streamify.sync.HybridLogicalClock
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

data class CorePendingMutation(
    val coreRowId: Long,
    val entityType: String,
    val entityKey: String,
    val op: String,
    val payloadJson: String,
    val coreChangeId: String = coreRowId.toString(),
)

interface CoreSyncSource {
    suspend fun readPendingBatch(limit: Int): List<CorePendingMutation>
    suspend fun deleteConsumed(rowIds: List<Long>)
    suspend fun clearAll()
}

/**
 * Anti-corruption bridge from the untouched `core/` submodule (`SyncDao` / `SyncChangeRecorder`)
 * into Streamify's v5 [SyncV5Store] with unified 64-bit HLC timestamps.
 */
class CoreSyncBridge(
    private val coreSource: CoreSyncSource,
    private val store: SyncV5Store,
    private val deviceIdProvider: suspend () -> String,
) {
    private val mutex = Mutex()
    private val pendingCoreRowIds = linkedMapOf<String, Long>()

    @OptIn(ExperimentalUuidApi::class)
    suspend fun drainCoreMutationsIntoV5(limit: Int = 200): Int = mutex.withLock {
        val rows = coreSource.readPendingBatch(limit)
        if (rows.isEmpty()) return@withLock 0

        val deviceId = deviceIdProvider()
        val alreadyDrainedRowIds = pendingCoreRowIds.values.toHashSet()
        var enqueued = 0
        for (row in rows) {
            if (row.coreRowId in alreadyDrainedRowIds) continue
            val normalizedType = mapLegacyEntityType(row.entityType) ?: continue
            val normalizedOp = if (row.op in setOf("D", "delete")) "delete" else "upsert"
            val mutationId = Uuid.random().toString()
            store.enqueueOrCoalesce(
                SyncOutboxRow(
                    mutationId = mutationId,
                    entityType = normalizedType,
                    entityKey = row.entityKey,
                    op = normalizedOp,
                    hlc = HybridLogicalClock.now(),
                    deviceId = deviceId,
                    payloadJson = row.payloadJson,
                )
            )
            pendingCoreRowIds[mutationId] = row.coreRowId
            enqueued++
        }
        enqueued
    }

    /**
     * Deletes drained `core/` outbox rows only after [SyncOutboxProcessor] receives server acks.
     */
    suspend fun onMutationsAcked(mutationIds: List<String>) {
        if (mutationIds.isEmpty()) return
        val ackedRowIds = mutex.withLock {
            mutationIds.mapNotNull { pendingCoreRowIds.remove(it) }
        }
        if (ackedRowIds.isNotEmpty()) {
            coreSource.deleteConsumed(ackedRowIds)
        }
    }

    suspend fun clearAll() {
        mutex.withLock { pendingCoreRowIds.clear() }
        coreSource.clearAll()
    }

    companion object {
        fun mapLegacyEntityType(legacy: String): String? = when (legacy) {
            "like", "user_like" -> "user_like"
            "library_track", "user_library_track" -> "user_library_track"
            "favorite", "user_item_favorite" -> "user_item_favorite"
            "playlist" -> "playlist"
            "playlist_track" -> "playlist_track"
            "setting", "user_setting" -> "user_setting"
            "episode_state", "user_podcast_episode_state" -> "user_podcast_episode_state"
            else -> null
        }
    }
}
