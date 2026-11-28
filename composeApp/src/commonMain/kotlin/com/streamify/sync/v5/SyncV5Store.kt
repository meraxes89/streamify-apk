package com.streamify.sync.v5

import com.streamify.supabase.v5.LongAsStringSerializer
import com.streamify.sync.HybridLogicalClock
import com.streamify.sync.synchronizedLock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

enum class OutboxStatus {
    PENDING,
    IN_FLIGHT,
    SENT,
    DEAD_LETTER,
}

data class SyncOutboxRow(
    val mutationId: String,
    val entityType: String,
    val entityKey: String,
    val op: String,
    val hlc: Long,
    val deviceId: String,
    val payloadJson: String,
    val status: OutboxStatus = OutboxStatus.PENDING,
    val attemptCount: Int = 0,
    val lastErrorCode: String? = null,
    val lastErrorDetail: String? = null,
)

data class DeadLetterSummary(
    val count: Int = 0,
    val latestErrorCode: String? = null,
)

data class SyncCheckpointRow(
    val userId: String,
    val deviceId: String,
    val pullCursorRev: Long = 0L,
    val lastHlc: Long = 0L,
    val bootstrapComplete: Boolean = false,
    val lastSyncedAtMs: Long = 0L,
)

/**
 * Unified transactional persistence contract for Sync v5 across all 4 KMP targets.
 * Dead-letter entries live as an indexed [OutboxStatus.DEAD_LETTER] state so
 * [claimPendingBatch] never over-fetches or loads DataStore JSON sidecars.
 */
interface SyncV5Store {
    suspend fun enqueueOrCoalesce(row: SyncOutboxRow)
    suspend fun claimPendingBatch(limit: Int): List<SyncOutboxRow>
    suspend fun markSent(mutationIds: List<String>, sentAtHlc: Long)
    suspend fun revertInFlightToPending(mutationIds: List<String>)
    suspend fun moveToDeadLetter(
        mutationId: String,
        errorCode: String,
        errorDetail: String,
        failedAtHlc: Long,
    )
    suspend fun resetInFlightToPending(): Int
    suspend fun pruneSentOlderThan(cutoffHlc: Long): Int
    suspend fun hasPendingOrInFlight(entityType: String, entityKey: String): Boolean
    suspend fun pendingCount(): Int

    fun observeDeadLetterSummary(): Flow<DeadLetterSummary>
    suspend fun listDeadLetters(limit: Int = 50): List<SyncOutboxRow>
    suspend fun retryDeadLetter(mutationId: String, newHlc: Long)
    suspend fun purgeDeadLetters(): Int

    suspend fun getCheckpoint(userId: String): SyncCheckpointRow?
    suspend fun saveCheckpoint(checkpoint: SyncCheckpointRow)
    suspend fun wipeAllForAccountSwitch()
}

expect fun createPlatformSyncV5Store(): SyncV5Store

/**
 * Thread-safe in-memory [SyncV5Store] for unit tests and restricted private-browsing fallback.
 */
class InMemorySyncV5Store : SyncV5Store {
    private val lock = Any()
    private val rows = LinkedHashMap<String, SyncOutboxRow>()
    private val checkpoints = HashMap<String, SyncCheckpointRow>()
    private val dlqFlow = MutableStateFlow(DeadLetterSummary())

    override suspend fun enqueueOrCoalesce(row: SyncOutboxRow) = synchronizedLock(lock) {
        val existingPending = rows.values.firstOrNull {
            it.status == OutboxStatus.PENDING &&
                it.entityType == row.entityType &&
                it.entityKey == row.entityKey
        }
        if (existingPending != null) {
            rows.remove(existingPending.mutationId)
        }
        rows[row.mutationId] = row.copy(status = OutboxStatus.PENDING)
        refreshDlqSummaryLocked()
    }

    override suspend fun claimPendingBatch(limit: Int): List<SyncOutboxRow> = synchronizedLock(lock) {
        val batch = rows.values
            .filter { it.status == OutboxStatus.PENDING }
            .sortedBy { it.hlc }
            .take(limit)
        batch.forEach { r ->
            rows[r.mutationId] = r.copy(status = OutboxStatus.IN_FLIGHT, attemptCount = r.attemptCount + 1)
        }
        batch.map { rows[it.mutationId]!! }
    }

    override suspend fun markSent(mutationIds: List<String>, sentAtHlc: Long) = synchronizedLock(lock) {
        mutationIds.forEach { id ->
            rows[id]?.let { rows[id] = it.copy(status = OutboxStatus.SENT, hlc = maxOf(it.hlc, sentAtHlc)) }
        }
    }

    override suspend fun revertInFlightToPending(mutationIds: List<String>) = synchronizedLock(lock) {
        mutationIds.forEach { id ->
            rows[id]?.let {
                if (it.status == OutboxStatus.IN_FLIGHT) {
                    rows[id] = it.copy(status = OutboxStatus.PENDING)
                }
            }
        }
    }

    override suspend fun moveToDeadLetter(
        mutationId: String,
        errorCode: String,
        errorDetail: String,
        failedAtHlc: Long,
    ) = synchronizedLock(lock) {
        rows[mutationId]?.let {
            rows[mutationId] = it.copy(
                status = OutboxStatus.DEAD_LETTER,
                lastErrorCode = errorCode,
                lastErrorDetail = errorDetail,
                hlc = maxOf(it.hlc, failedAtHlc),
            )
        }
        // Cap DLQ at 500 FIFO
        val dlq = rows.values.filter { it.status == OutboxStatus.DEAD_LETTER }.sortedBy { it.hlc }
        if (dlq.size > 500) {
            dlq.take(dlq.size - 500).forEach { rows.remove(it.mutationId) }
        }
        refreshDlqSummaryLocked()
    }

    override suspend fun resetInFlightToPending(): Int = synchronizedLock(lock) {
        var count = 0
        rows.entries.toList().forEach { (id, row) ->
            if (row.status == OutboxStatus.IN_FLIGHT) {
                rows[id] = row.copy(status = OutboxStatus.PENDING)
                count++
            }
        }
        count
    }

    override suspend fun pruneSentOlderThan(cutoffHlc: Long): Int = synchronizedLock(lock) {
        val toRemove = rows.values.filter { it.status == OutboxStatus.SENT && it.hlc < cutoffHlc }.map { it.mutationId }
        toRemove.forEach { rows.remove(it) }
        toRemove.size
    }

    override suspend fun hasPendingOrInFlight(entityType: String, entityKey: String): Boolean = synchronizedLock(lock) {
        rows.values.any {
            (it.status == OutboxStatus.PENDING || it.status == OutboxStatus.IN_FLIGHT) &&
                it.entityType == entityType &&
                it.entityKey == entityKey
        }
    }

    override suspend fun pendingCount(): Int = synchronizedLock(lock) {
        rows.values.count { it.status == OutboxStatus.PENDING || it.status == OutboxStatus.IN_FLIGHT }
    }

    override fun observeDeadLetterSummary(): Flow<DeadLetterSummary> = dlqFlow.asStateFlow()

    override suspend fun listDeadLetters(limit: Int): List<SyncOutboxRow> = synchronizedLock(lock) {
        rows.values
            .filter { it.status == OutboxStatus.DEAD_LETTER }
            .sortedByDescending { it.hlc }
            .take(limit)
    }

    override suspend fun retryDeadLetter(mutationId: String, newHlc: Long) = synchronizedLock(lock) {
        rows[mutationId]?.let {
            if (it.status == OutboxStatus.DEAD_LETTER) {
                rows[mutationId] = it.copy(
                    status = OutboxStatus.PENDING,
                    hlc = newHlc,
                    lastErrorCode = null,
                    lastErrorDetail = null,
                )
            }
        }
        refreshDlqSummaryLocked()
    }

    override suspend fun purgeDeadLetters(): Int = synchronizedLock(lock) {
        val ids = rows.values.filter { it.status == OutboxStatus.DEAD_LETTER }.map { it.mutationId }
        ids.forEach { rows.remove(it) }
        refreshDlqSummaryLocked()
        ids.size
    }

    override suspend fun getCheckpoint(userId: String): SyncCheckpointRow? = synchronizedLock(lock) {
        checkpoints[userId]
    }

    override suspend fun saveCheckpoint(checkpoint: SyncCheckpointRow) = synchronizedLock(lock) {
        checkpoints[checkpoint.userId] = checkpoint
    }

    override suspend fun wipeAllForAccountSwitch() = synchronizedLock(lock) {
        rows.clear()
        checkpoints.clear()
        refreshDlqSummaryLocked()
    }

    private fun refreshDlqSummaryLocked() {
        val dlq = rows.values.filter { it.status == OutboxStatus.DEAD_LETTER }
        dlqFlow.value = DeadLetterSummary(
            count = dlq.size,
            latestErrorCode = dlq.maxByOrNull { it.hlc }?.lastErrorCode,
        )
    }

    internal fun exportSnapshot(): PersistedV5StoreSnapshot = synchronizedLock(lock) {
        PersistedV5StoreSnapshot(
            rows = rows.values.map {
                PersistedV5OutboxRow(
                    mutationId = it.mutationId,
                    entityType = it.entityType,
                    entityKey = it.entityKey,
                    op = it.op,
                    hlc = it.hlc,
                    deviceId = it.deviceId,
                    payloadJson = it.payloadJson,
                    status = it.status.name,
                    attemptCount = it.attemptCount,
                    lastErrorCode = it.lastErrorCode,
                    lastErrorDetail = it.lastErrorDetail,
                )
            },
            checkpoints = checkpoints.mapValues { (_, cp) ->
                PersistedV5Checkpoint(
                    userId = cp.userId,
                    deviceId = cp.deviceId,
                    pullCursorRev = cp.pullCursorRev,
                    lastHlc = cp.lastHlc,
                    bootstrapComplete = cp.bootstrapComplete,
                    lastSyncedAtMs = cp.lastSyncedAtMs,
                )
            },
        )
    }

    internal fun importSnapshot(snapshot: PersistedV5StoreSnapshot) = synchronizedLock(lock) {
        rows.clear()
        for (r in snapshot.rows) {
            val status = runCatching { OutboxStatus.valueOf(r.status) }.getOrDefault(OutboxStatus.PENDING)
            rows[r.mutationId] = SyncOutboxRow(
                mutationId = r.mutationId,
                entityType = r.entityType,
                entityKey = r.entityKey,
                op = r.op,
                hlc = r.hlc,
                deviceId = r.deviceId,
                payloadJson = r.payloadJson,
                status = status,
                attemptCount = r.attemptCount,
                lastErrorCode = r.lastErrorCode,
                lastErrorDetail = r.lastErrorDetail,
            )
        }
        checkpoints.clear()
        for ((uid, cp) in snapshot.checkpoints) {
            checkpoints[uid] = SyncCheckpointRow(
                userId = cp.userId,
                deviceId = cp.deviceId,
                pullCursorRev = cp.pullCursorRev,
                lastHlc = cp.lastHlc,
                bootstrapComplete = cp.bootstrapComplete,
                lastSyncedAtMs = cp.lastSyncedAtMs,
            )
        }
        refreshDlqSummaryLocked()
    }
}

@Serializable
internal data class PersistedV5OutboxRow(
    val mutationId: String,
    val entityType: String,
    val entityKey: String,
    val op: String,
    @Serializable(with = LongAsStringSerializer::class)
    val hlc: Long,
    val deviceId: String,
    val payloadJson: String,
    val status: String,
    val attemptCount: Int = 0,
    val lastErrorCode: String? = null,
    val lastErrorDetail: String? = null,
)

@Serializable
internal data class PersistedV5Checkpoint(
    val userId: String,
    val deviceId: String,
    @Serializable(with = LongAsStringSerializer::class)
    val pullCursorRev: Long = 0L,
    @Serializable(with = LongAsStringSerializer::class)
    val lastHlc: Long = 0L,
    val bootstrapComplete: Boolean = false,
    @Serializable(with = LongAsStringSerializer::class)
    val lastSyncedAtMs: Long = 0L,
)

@Serializable
internal data class PersistedV5StoreSnapshot(
    val rows: List<PersistedV5OutboxRow> = emptyList(),
    val checkpoints: Map<String, PersistedV5Checkpoint> = emptyMap(),
)

/**
 * Durable JSON-backed [SyncV5Store] for DataStoreManager (Android/JVM/iOS/Wasm) and
 * `window.localStorage` (standalone Wasm). Uses [LongAsStringSerializer] for 64-bit safety.
 */
class DataStoreSyncV5Store(
    private val readSnapshot: suspend () -> String?,
    private val writeSnapshot: suspend (String) -> Unit,
    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true },
) : SyncV5Store {
    private val mutex = Mutex()
    private val delegate = InMemorySyncV5Store()
    private var loaded = false

    private suspend fun ensureLoadedLocked() {
        if (loaded) return
        loaded = true
        val raw = runCatching { readSnapshot() }.getOrNull()?.takeIf { it.isNotBlank() } ?: return
        runCatching { json.decodeFromString<PersistedV5StoreSnapshot>(raw) }
            .onSuccess { delegate.importSnapshot(it) }
    }

    private suspend fun persistLocked() {
        val encoded = json.encodeToString(delegate.exportSnapshot())
        runCatching { writeSnapshot(encoded) }
    }

    override suspend fun enqueueOrCoalesce(row: SyncOutboxRow) = mutex.withLock {
        ensureLoadedLocked()
        delegate.enqueueOrCoalesce(row)
        persistLocked()
    }

    override suspend fun claimPendingBatch(limit: Int): List<SyncOutboxRow> = mutex.withLock {
        ensureLoadedLocked()
        val claimed = delegate.claimPendingBatch(limit)
        if (claimed.isNotEmpty()) persistLocked()
        claimed
    }

    override suspend fun markSent(mutationIds: List<String>, sentAtHlc: Long) = mutex.withLock {
        ensureLoadedLocked()
        delegate.markSent(mutationIds, sentAtHlc)
        if (mutationIds.isNotEmpty()) persistLocked()
    }

    override suspend fun revertInFlightToPending(mutationIds: List<String>) = mutex.withLock {
        ensureLoadedLocked()
        delegate.revertInFlightToPending(mutationIds)
        if (mutationIds.isNotEmpty()) persistLocked()
    }

    override suspend fun moveToDeadLetter(
        mutationId: String,
        errorCode: String,
        errorDetail: String,
        failedAtHlc: Long,
    ) = mutex.withLock {
        ensureLoadedLocked()
        delegate.moveToDeadLetter(mutationId, errorCode, errorDetail, failedAtHlc)
        persistLocked()
    }

    override suspend fun resetInFlightToPending(): Int = mutex.withLock {
        ensureLoadedLocked()
        val count = delegate.resetInFlightToPending()
        if (count > 0) persistLocked()
        count
    }

    override suspend fun pruneSentOlderThan(cutoffHlc: Long): Int = mutex.withLock {
        ensureLoadedLocked()
        val count = delegate.pruneSentOlderThan(cutoffHlc)
        if (count > 0) persistLocked()
        count
    }

    override suspend fun hasPendingOrInFlight(entityType: String, entityKey: String): Boolean = mutex.withLock {
        ensureLoadedLocked()
        delegate.hasPendingOrInFlight(entityType, entityKey)
    }

    override suspend fun pendingCount(): Int = mutex.withLock {
        ensureLoadedLocked()
        delegate.pendingCount()
    }

    override fun observeDeadLetterSummary(): Flow<DeadLetterSummary> = delegate.observeDeadLetterSummary()

    override suspend fun listDeadLetters(limit: Int): List<SyncOutboxRow> = mutex.withLock {
        ensureLoadedLocked()
        delegate.listDeadLetters(limit)
    }

    override suspend fun retryDeadLetter(mutationId: String, newHlc: Long) = mutex.withLock {
        ensureLoadedLocked()
        delegate.retryDeadLetter(mutationId, newHlc)
        persistLocked()
    }

    override suspend fun purgeDeadLetters(): Int = mutex.withLock {
        ensureLoadedLocked()
        val count = delegate.purgeDeadLetters()
        if (count > 0) persistLocked()
        count
    }

    override suspend fun getCheckpoint(userId: String): SyncCheckpointRow? = mutex.withLock {
        ensureLoadedLocked()
        delegate.getCheckpoint(userId)
    }

    override suspend fun saveCheckpoint(checkpoint: SyncCheckpointRow) = mutex.withLock {
        ensureLoadedLocked()
        delegate.saveCheckpoint(checkpoint)
        persistLocked()
    }

    override suspend fun wipeAllForAccountSwitch() {
        mutex.withLock {
            ensureLoadedLocked()
            delegate.wipeAllForAccountSwitch()
            runCatching { writeSnapshot("") }
        }
    }
}
