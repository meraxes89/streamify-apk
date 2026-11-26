package com.streamify.sync

import com.maxrave.domain.data.entities.LocalPlaylistEntity
import com.maxrave.domain.data.entities.analytics.PlaybackEventEntity
import com.maxrave.domain.data.entities.sync.SyncChangeEntity

class WasmFakeSyncOutboxRepository : SyncOutboxRepository {
    private val changes = mutableListOf<SyncChangeEntity>()
    private val rejectedIds = mutableSetOf<String>()

    override suspend fun recordPending(change: SyncChangeEntity) {
        rejectedIds.remove(change.changeId)
        changes.removeAll { it.entity == change.entity && it.entityKey == change.entityKey }
        changes.add(change)
    }

    override suspend fun getPending(limit: Int): List<SyncChangeEntity> =
        changes.filter { it.changeId !in rejectedIds }.take(limit)

    override suspend fun markSent(changeIds: List<String>, seq: Long) {
        rejectedIds.removeAll(changeIds.toSet())
        changes.removeAll { it.changeId in changeIds }
    }

    override suspend fun markRejected(changeIds: List<String>) {
        rejectedIds.addAll(changeIds)
    }

    override suspend fun pruneSent(beforeTimestamp: Long) {}

    override suspend fun getPlaybackEventsBySyncIds(syncIds: List<String>): List<PlaybackEventEntity> = emptyList()

    override suspend fun getLocalPlaylistBySyncId(syncId: String): LocalPlaylistEntity? = null

    override suspend fun setLocalPlaylistSyncId(id: Long, syncId: String) {}

    override suspend fun getPlaylistTrackOrder(playlistId: Long): List<String> = emptyList()

    override suspend fun getPendingCount(): Int = changes.count { it.changeId !in rejectedIds }
}

actual fun createPlatformSyncOutboxRepository(): SyncOutboxRepository = WasmFakeSyncOutboxRepository()

actual fun withSyncApplying(): kotlin.coroutines.CoroutineContext = kotlin.coroutines.EmptyCoroutineContext
