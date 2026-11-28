package com.streamify.sync.v5.planea

import com.streamify.supabase.v5.PlaneAStateSyncClient
import com.streamify.supabase.v5.PlaneAMutationDto
import com.streamify.supabase.v5.PlaneAPushResponse
import com.streamify.supabase.v5.RpcResult
import com.streamify.sync.HybridLogicalClock
import com.streamify.sync.v5.SyncOutboxRow
import com.streamify.sync.v5.SyncV5Store
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

sealed interface PushOutcome {
    data class Completed(val pushedCount: Int, val needsPull: Boolean) : PushOutcome
    data class ClockSkewBlocked(val serverTimeMs: Long?) : PushOutcome
    data class DeviceRevoked(val revokedAt: String?) : PushOutcome
    data class TransientFailure(val message: String) : PushOutcome
}

/**
 * Coalesces, batches (<= 200), pushes, and dead-letters Plane A mutations.
 * Never advances `pullCursorRev` on push.
 */
class SyncOutboxProcessor(
    private val store: SyncV5Store,
    private val client: PlaneAStateSyncClient,
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val isParentTombstonedLocally: suspend (entityKey: String) -> Boolean = { false },
    private val onMutationsAcked: suspend (List<String>) -> Unit = {},
) {
    suspend fun pushPending(
        userId: String,
        deviceId: String,
        knownPullCursorRev: Long,
    ): PushOutcome {
        var totalPushed = 0
        var needsPull = false

        while (true) {
            val batch = store.claimPendingBatch(MAX_BATCH_SIZE)
            if (batch.isEmpty()) {
                return PushOutcome.Completed(totalPushed, needsPull)
            }

            val batchPairs = batch.mapNotNull { row ->
                val payloadObj = runCatching { json.parseToJsonElement(row.payloadJson).jsonObject }.getOrNull()
                if (payloadObj == null) {
                    store.moveToDeadLetter(row.mutationId, "INVALID_PAYLOAD", "Malformed local JSON", HybridLogicalClock.now())
                    null
                } else {
                    row to PlaneAMutationDto(
                        mutationId = row.mutationId,
                        entityType = row.entityType,
                        entityKey = row.entityKey,
                        op = row.op,
                        hlc = row.hlc,
                        payload = payloadObj,
                    )
                }
            }

            if (batchPairs.isEmpty()) continue
            val dtos = batchPairs.map { it.second }
            val rowById = batchPairs.associate { it.first.mutationId to it.first }

            when (val res = client.push(deviceId, dtos)) {
                is RpcResult.Ok -> {
                    val applied = applyOkPush(res, rowById, knownPullCursorRev)
                    totalPushed += applied.pushed
                    if (applied.needsPull) needsPull = true
                    if (applied.clockSkewServerMs != null) {
                        return PushOutcome.ClockSkewBlocked(applied.clockSkewServerMs)
                    }
                }
                is RpcResult.DeviceRevoked -> {
                    store.revertInFlightToPending(batchPairs.map { it.first.mutationId })
                    return PushOutcome.DeviceRevoked(res.revokedAt)
                }
                is RpcResult.RateLimited -> {
                    store.revertInFlightToPending(batchPairs.map { it.first.mutationId })
                    return PushOutcome.TransientFailure("Rate limited (${res.retryAfterMs}ms)")
                }
                is RpcResult.Unauthorized -> {
                    store.revertInFlightToPending(batchPairs.map { it.first.mutationId })
                    return PushOutcome.TransientFailure(res.reason)
                }
                is RpcResult.TransientFailure -> {
                    store.revertInFlightToPending(batchPairs.map { it.first.mutationId })
                    return PushOutcome.TransientFailure(res.message)
                }
                is RpcResult.TerminalError -> {
                    if (batchPairs.size == 1) {
                        store.moveToDeadLetter(batchPairs[0].first.mutationId, res.errorCode, res.message, HybridLogicalClock.now())
                    } else {
                        for (idx in batchPairs.indices) {
                            val (row, dto) = batchPairs[idx]
                            when (val singleRes = client.push(deviceId, listOf(dto))) {
                                is RpcResult.Ok -> {
                                    val applied = applyOkPush(singleRes, mapOf(row.mutationId to row), knownPullCursorRev)
                                    totalPushed += applied.pushed
                                    if (applied.needsPull) needsPull = true
                                    if (applied.clockSkewServerMs != null) {
                                        val remainingIds = batchPairs.drop(idx + 1).map { it.first.mutationId }
                                        if (remainingIds.isNotEmpty()) store.revertInFlightToPending(remainingIds)
                                        return PushOutcome.ClockSkewBlocked(applied.clockSkewServerMs)
                                    }
                                }
                                is RpcResult.TerminalError -> {
                                    store.moveToDeadLetter(row.mutationId, singleRes.errorCode, singleRes.message, HybridLogicalClock.now())
                                }
                                is RpcResult.DeviceRevoked -> {
                                    store.revertInFlightToPending(batchPairs.drop(idx).map { it.first.mutationId })
                                    return PushOutcome.DeviceRevoked(singleRes.revokedAt)
                                }
                                is RpcResult.RateLimited -> {
                                    store.revertInFlightToPending(batchPairs.drop(idx).map { it.first.mutationId })
                                    return PushOutcome.TransientFailure("Rate limited (${singleRes.retryAfterMs}ms)")
                                }
                                is RpcResult.Unauthorized -> {
                                    store.revertInFlightToPending(batchPairs.drop(idx).map { it.first.mutationId })
                                    return PushOutcome.TransientFailure(singleRes.reason)
                                }
                                is RpcResult.TransientFailure -> {
                                    store.revertInFlightToPending(batchPairs.drop(idx).map { it.first.mutationId })
                                    return PushOutcome.TransientFailure(singleRes.message)
                                }
                                is RpcResult.CursorExpired, is RpcResult.ClockSkew -> {
                                    store.revertInFlightToPending(batchPairs.drop(idx).map { it.first.mutationId })
                                    return PushOutcome.TransientFailure("Unexpected push status")
                                }
                            }
                        }
                    }
                }
                is RpcResult.CursorExpired, is RpcResult.ClockSkew -> {
                    store.revertInFlightToPending(batchPairs.map { it.first.mutationId })
                    return PushOutcome.TransientFailure("Unexpected push status")
                }
            }
        }
    }

    private data class OkApplyResult(
        val pushed: Int,
        val needsPull: Boolean,
        val clockSkewServerMs: Long?,
    )

    private suspend fun applyOkPush(
        res: RpcResult.Ok<PlaneAPushResponse>,
        rowById: Map<String, SyncOutboxRow>,
        knownPullCursorRev: Long,
    ): OkApplyResult {
        val sentIds = mutableListOf<String>()
        val revertIds = mutableListOf<String>()
        var clockSkewServerMs: Long? = null
        var needsPull = false
        val ackHlc = HybridLogicalClock.now()

        for (r in res.data.results) {
            when (r.status) {
                "applied", "duplicate" -> sentIds.add(r.mutationId)
                "stale" -> {
                    sentIds.add(r.mutationId)
                    needsPull = true
                }
                "rejected" -> when (r.errorCode) {
                    "CLOCK_SKEW_EXCESSIVE" -> {
                        revertIds.add(r.mutationId)
                        clockSkewServerMs = r.serverTimeMs ?: res.serverTimeMs
                    }
                    "PARENT_NOT_FOUND" -> {
                        val row = rowById[r.mutationId]
                        if (row != null && isParentTombstonedLocally(row.entityKey)) {
                            sentIds.add(r.mutationId)
                        } else {
                            store.moveToDeadLetter(r.mutationId, "PARENT_NOT_FOUND", "Missing parent playlist", ackHlc)
                        }
                    }
                    else -> {
                        store.moveToDeadLetter(r.mutationId, r.errorCode ?: "REJECTED", "Server rejected", ackHlc)
                    }
                }
                else -> revertIds.add(r.mutationId)
            }
        }

        if (sentIds.isNotEmpty()) {
            store.markSent(sentIds, ackHlc)
            onMutationsAcked(sentIds)
        }
        if (revertIds.isNotEmpty()) {
            store.revertInFlightToPending(revertIds)
        }
        if (res.data.headRev > knownPullCursorRev) {
            needsPull = true
        }
        return OkApplyResult(
            pushed = sentIds.size,
            needsPull = needsPull,
            clockSkewServerMs = clockSkewServerMs,
        )
    }

    companion object {
        const val MAX_BATCH_SIZE = 200
    }
}
