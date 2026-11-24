package com.streamify.sync

import com.maxrave.domain.data.entities.LocalPlaylistEntity
import com.maxrave.domain.data.entities.analytics.PlaybackEventEntity
import com.maxrave.domain.data.entities.sync.SyncChangeEntity

class WasmFakeSyncOutboxRepository : SyncOutboxRepository {
    private val changes = mutableListOf<SyncChangeEntity>()

    override suspend fun recordPending(change: SyncChangeEntity) {
        changes.removeAll { it.entity == change.entity && it.entityKey == change.entityKey }
        changes.add(change)
    }

    override suspend fun getPending(limit: Int): List<SyncChangeEntity> = changes.take(limit)

    override suspend fun markSent(changeIds: List<String>, seq: Long) {
        changes.removeAll { it.changeId in changeIds }
    }

    override suspend fun pruneSent(beforeTimestamp: Long) {}

    override suspend fun getPlaybackEventsBySyncIds(syncIds: List<String>): List<PlaybackEventEntity> = emptyList()

    override suspend fun getLocalPlaylistBySyncId(syncId: String): LocalPlaylistEntity? = null

    override suspend fun getPlaylistTrackOrder(playlistId: Long): List<String> = emptyList()

    override suspend fun getPendingCount(): Int = changes.size
}

actual fun createPlatformSyncOutboxRepository(): SyncOutboxRepository = WasmFakeSyncOutboxRepository()
