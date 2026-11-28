package com.streamify.sync.v5.planea

import com.streamify.supabase.v5.PlaneAStateSyncClient
import com.streamify.supabase.v5.PlaneAMutationDto
import com.streamify.supabase.v5.RpcResult
import com.streamify.sync.HybridLogicalClock
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

            val dtos = batch.mapNotNull { row ->
                val payloadObj = runCatching { json.parseToJsonElement(row.payloadJson).jsonObject }.getOrNull()
                if (payloadObj == null) {
                    store.moveToDeadLetter(row.mutationId, "INVALID_PAYLOAD", "Malformed local JSON", HybridLogicalClock.now())
                    null
                } else {
                    PlaneAMutationDto(
                        mutationId = row.mutationId,
                        entityType = row.entityType,
                        entityKey = row.entityKey,
                        op = row.op,
                        hlc = row.hlc,
                        payload = payloadObj,
                    )
                }
            }

            if (dtos.isEmpty()) continue

            when (val res = client.push(deviceId, dtos)) {
                is RpcResult.Ok -> {
                    val sentIds = mutableListOf<String>()
                    val revertIds = mutableListOf<String>()
                    var clockSkewServerMs: Long? = null
                    val ackHlc = HybridLogicalClock.now()

                    val rowById = batch.associateBy { it.mutationId }
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
                        totalPushed += sentIds.size
                    }
                    if (revertIds.isNotEmpty()) {
                        store.revertInFlightToPending(revertIds)
                    }
                    if (clockSkewServerMs != null) {
                        return PushOutcome.ClockSkewBlocked(clockSkewServerMs)
                    }
                    if (res.data.headRev > knownPullCursorRev) {
                        needsPull = true
                    }
                }
                is RpcResult.DeviceRevoked -> {
                    store.revertInFlightToPending(batch.map { it.mutationId })
                    return PushOutcome.DeviceRevoked(res.revokedAt)
                }
                is RpcResult.RateLimited -> {
                    store.revertInFlightToPending(batch.map { it.mutationId })
                    return PushOutcome.TransientFailure("Rate limited (${res.retryAfterMs}ms)")
                }
                is RpcResult.Unauthorized -> {
                    store.revertInFlightToPending(batch.map { it.mutationId })
                    return PushOutcome.TransientFailure(res.reason)
                }
                is RpcResult.TransientFailure -> {
                    store.revertInFlightToPending(batch.map { it.mutationId })
                    return PushOutcome.TransientFailure(res.message)
                }
                is RpcResult.TerminalError -> {
                    batch.forEach { store.moveToDeadLetter(it.mutationId, res.errorCode, res.message, HybridLogicalClock.now()) }
                }
                is RpcResult.CursorExpired, is RpcResult.ClockSkew -> {
                    store.revertInFlightToPending(batch.map { it.mutationId })
                    return PushOutcome.TransientFailure("Unexpected push status")
                }
            }
        }
    }

    companion object {
        const val MAX_BATCH_SIZE = 200
    }
}
