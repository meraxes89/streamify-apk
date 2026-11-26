package com.streamify.sync

import com.maxrave.data.db.SyncDao
import com.maxrave.domain.data.entities.LocalPlaylistEntity
import com.maxrave.domain.data.entities.analytics.PlaybackEventEntity
import com.maxrave.domain.data.entities.sync.SyncChangeEntity
import com.maxrave.domain.manager.DataStoreManager
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class AndroidRoomSyncOutboxRepository : SyncOutboxRepository, KoinComponent {
    private val syncDao: SyncDao by inject()
    private val dataStoreManager: DataStoreManager by inject()

    override suspend fun recordPending(change: SyncChangeEntity) {
        syncDao.recordPending(change)
    }

    override suspend fun getPending(limit: Int): List<SyncChangeEntity> {
        val quarantined = SyncDeadLetterStore.quarantinedIds(dataStoreManager)
        if (quarantined.isEmpty()) return syncDao.pending(limit)
        return syncDao.pending(limit + quarantined.size)
            .filter { it.changeId !in quarantined }
            .take(limit)
    }

    override suspend fun markSent(changeIds: List<String>, seq: Long) {
        if (changeIds.isNotEmpty()) {
            syncDao.markSent(changeIds, seq)
        }
    }

    override suspend fun markRejected(changeIds: List<String>) {
        SyncDeadLetterStore.quarantine(dataStoreManager, changeIds)
    }

    override suspend fun pruneSent(beforeTimestamp: Long) {
        syncDao.pruneSent(beforeTimestamp)
    }

    override suspend fun getPlaybackEventsBySyncIds(syncIds: List<String>): List<PlaybackEventEntity> {
        return if (syncIds.isNotEmpty()) syncDao.eventsBySyncId(syncIds) else emptyList()
    }

    override suspend fun getLocalPlaylistBySyncId(syncId: String): LocalPlaylistEntity? {
        return syncDao.localPlaylistBySyncId(syncId)
    }

    override suspend fun setLocalPlaylistSyncId(id: Long, syncId: String) {
        syncDao.setLocalPlaylistSyncId(id, syncId)
    }

    override suspend fun getPlaylistTrackOrder(playlistId: Long): List<String> {
        return syncDao.playlistOrder(playlistId)
    }

    override suspend fun getPendingCount(): Int {
        val quarantined = SyncDeadLetterStore.quarantinedIds(dataStoreManager)
        return syncDao.pending(1000).count { it.changeId !in quarantined }
    }
}

actual fun createPlatformSyncOutboxRepository(): SyncOutboxRepository = AndroidRoomSyncOutboxRepository()

actual fun withSyncApplying(): kotlin.coroutines.CoroutineContext = try {
    val clazz = Class.forName("com.maxrave.data.librarysync.SyncApplying")
    clazz.getField("INSTANCE").get(null) as kotlin.coroutines.CoroutineContext
} catch (_: Throwable) {
    kotlin.coroutines.EmptyCoroutineContext
}
