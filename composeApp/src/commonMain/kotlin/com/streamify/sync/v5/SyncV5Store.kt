package com.streamify.sync.v5

import com.streamify.sync.HybridLogicalClock
import com.streamify.sync.synchronizedLock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map

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
}
