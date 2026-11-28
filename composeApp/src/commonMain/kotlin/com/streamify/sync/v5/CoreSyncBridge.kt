package com.streamify.sync.v5

import com.streamify.sync.HybridLogicalClock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

data class CorePendingMutation(
    val coreRowId: Long,
    val entityType: String,
    val entityKey: String,
    val op: String,
    val payloadJson: String,
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
    private val deviceIdProvider: () -> String,
) {
    @OptIn(ExperimentalUuidApi::class)
    suspend fun drainCoreMutationsIntoV5(limit: Int = 200): Int {
        val rows = coreSource.readPendingBatch(limit)
        if (rows.isEmpty()) return 0

        val deviceId = deviceIdProvider()
        val consumedIds = mutableListOf<Long>()
        for (row in rows) {
            val normalizedType = mapLegacyEntityType(row.entityType) ?: continue
            val normalizedOp = if (row.op in setOf("D", "delete")) "delete" else "upsert"
            store.enqueueOrCoalesce(
                SyncOutboxRow(
                    mutationId = Uuid.random().toString(),
                    entityType = normalizedType,
                    entityKey = row.entityKey,
                    op = normalizedOp,
                    hlc = HybridLogicalClock.now(),
                    deviceId = deviceId,
                    payloadJson = row.payloadJson,
                )
            )
            consumedIds += row.coreRowId
        }
        if (consumedIds.isNotEmpty()) {
            coreSource.deleteConsumed(consumedIds)
        }
        return consumedIds.size
    }

    suspend fun clearAll() {
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
