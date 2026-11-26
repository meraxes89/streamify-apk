package com.streamify.sync

import com.maxrave.domain.data.entities.LocalPlaylistEntity
import com.maxrave.domain.data.entities.analytics.PlaybackEventEntity
import com.maxrave.domain.data.entities.sync.SyncChangeEntity
import kotlinx.browser.localStorage
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
private data class WasmOutboxRecord(
    val changeId: String,
    val entity: String,
    val entityKey: String,
    val op: String,
    val value: String? = null,
    val changedAt: Long,
    val deviceId: String = "",
    val serverSeq: Long? = null,
)

@Serializable
private data class WasmOutboxSnapshot(
    val changes: List<WasmOutboxRecord> = emptyList(),
    val rejectedIds: Set<String> = emptySet(),
)

class WasmFakeSyncOutboxRepository : SyncOutboxRepository {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val storageKey = "streamify_wasm_sync_outbox_v1"

    private val changes = mutableListOf<SyncChangeEntity>()
    private val rejectedIds = mutableSetOf<String>()

    init {
        loadFromStorage()
    }

    private fun loadFromStorage() {
        val raw = runCatching { localStorage.getItem(storageKey) }.getOrNull() ?: return
        val snapshot = runCatching { json.decodeFromString<WasmOutboxSnapshot>(raw) }.getOrNull() ?: return
        changes.clear()
        changes.addAll(
            snapshot.changes.map {
                SyncChangeEntity(
                    changeId = it.changeId,
                    entity = it.entity,
                    entityKey = it.entityKey,
                    op = it.op,
                    value = it.value,
                    changedAt = it.changedAt,
                    deviceId = it.deviceId,
                    serverSeq = it.serverSeq,
                )
            },
        )
        rejectedIds.clear()
        rejectedIds.addAll(snapshot.rejectedIds)
    }

    private fun persistToStorage() {
        runCatching {
            val snapshot = WasmOutboxSnapshot(
                changes = changes.map {
                    WasmOutboxRecord(
                        changeId = it.changeId,
                        entity = it.entity,
                        entityKey = it.entityKey,
                        op = it.op,
                        value = it.value,
                        changedAt = it.changedAt,
                        deviceId = it.deviceId,
                        serverSeq = it.serverSeq,
                    )
                },
                rejectedIds = rejectedIds.toSet(),
            )
            localStorage.setItem(storageKey, json.encodeToString(snapshot))
        }
    }

    override suspend fun recordPending(change: SyncChangeEntity) {
        rejectedIds.remove(change.changeId)
        changes.removeAll { it.entity == change.entity && it.entityKey == change.entityKey }
        changes.add(change)
        persistToStorage()
    }

    override suspend fun getPending(limit: Int): List<SyncChangeEntity> =
        changes.filter { it.changeId !in rejectedIds }.take(limit)

    override suspend fun markSent(changeIds: List<String>, seq: Long) {
        rejectedIds.removeAll(changeIds.toSet())
        changes.removeAll { it.changeId in changeIds }
        persistToStorage()
    }

    override suspend fun markRejected(changeIds: List<String>) {
        rejectedIds.addAll(changeIds)
        persistToStorage()
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
