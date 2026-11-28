package com.streamify.sync.v5

import com.streamify.sync.HybridLogicalClock
import com.streamify.sync.SyncKind
import com.streamify.sync.SyncOp
import com.streamify.sync.SyncOutboxRepository
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * [CoreSyncSource] adapter backed by [SyncOutboxRepository] so `core/` outbox rows
 * are drained into [SyncV5Store] and deleted only after server acknowledgement.
 */
class SyncOutboxCoreSource(
    private val outboxRepository: SyncOutboxRepository,
) : CoreSyncSource {
    private val mutex = Mutex()
    private val changeIdByRowId = mutableMapOf<Long, String>()

    override suspend fun readPendingBatch(limit: Int): List<CorePendingMutation> {
        val pending = outboxRepository.getPending(limit)
        if (pending.isEmpty()) return emptyList()

        return mutex.withLock {
            pending.mapIndexedNotNull { idx, change ->
                val v5Type = mapSyncKind(change.entity) ?: return@mapIndexedNotNull null
                val rowId = change.changeId.hashCode().toLong() * 31L + idx
                changeIdByRowId[rowId] = change.changeId
                val op = if (change.op == SyncOp.DELETE || change.value == "0") "delete" else "upsert"
                val payload = buildJsonObject {
                    when (v5Type) {
                        "user_like", "user_library_track" -> put("track_id", change.entityKey)
                        "user_item_favorite" -> {
                            val type = when (change.entity) {
                                SyncKind.ARTIST_FOLLOW -> "artist"
                                SyncKind.ALBUM_LIKE -> "album"
                                SyncKind.PLAYLIST_LIKE -> "playlist"
                                SyncKind.PODCAST_FAVORITE -> "podcast"
                                else -> change.entityKey.substringBefore(":", "artist")
                            }
                            put("item_type", type)
                            put("item_id", change.entityKey.substringAfter(":"))
                        }
                        "playlist" -> put("id", change.entityKey)
                        "playlist_track" -> {
                            put("playlist_id", change.entityKey.substringBefore("|"))
                            put("track_id", change.entityKey.substringAfter("|"))
                        }
                        "user_setting" -> {
                            put("key", change.entityKey)
                            put("value", change.value ?: "")
                        }
                        "user_podcast_episode_state" -> put("episode_id", change.entityKey)
                    }
                }.toString()
                CorePendingMutation(
                    coreRowId = rowId,
                    entityType = v5Type,
                    entityKey = change.entityKey,
                    op = op,
                    payloadJson = payload,
                    coreChangeId = change.changeId,
                )
            }
        }
    }

    override suspend fun deleteConsumed(rowIds: List<Long>) {
        if (rowIds.isEmpty()) return
        val changeIds = mutex.withLock {
            rowIds.mapNotNull { changeIdByRowId.remove(it) }
        }
        if (changeIds.isNotEmpty()) {
            outboxRepository.markSent(changeIds, HybridLogicalClock.now())
        }
    }

    override suspend fun clearAll() {
        mutex.withLock { changeIdByRowId.clear() }
        outboxRepository.pruneSent(Long.MAX_VALUE)
    }

    private fun mapSyncKind(kind: String): String? = when (kind) {
        SyncKind.SONG_LIKE -> "user_like"
        SyncKind.LIBRARY_TRACK -> "user_library_track"
        SyncKind.ARTIST_FOLLOW, SyncKind.ALBUM_LIKE, SyncKind.PLAYLIST_LIKE, SyncKind.PODCAST_FAVORITE -> "user_item_favorite"
        SyncKind.LOCAL_PLAYLIST, SyncKind.LOCAL_PLAYLIST_ORDER -> "playlist"
        SyncKind.LOCAL_PLAYLIST_TRACK -> "playlist_track"
        SyncKind.SETTINGS -> "user_setting"
        SyncKind.EPISODE_STATE -> "user_podcast_episode_state"
        else -> null
    }
}
