package com.streamify.sync.v5.planea

import com.streamify.supabase.v5.PlaneAStateSyncClient
import com.streamify.supabase.v5.RpcResult
import com.streamify.sync.HybridLogicalClock
import com.streamify.sync.v5.SyncCheckpointRow
import com.streamify.sync.v5.SyncV5Store
import com.streamify.sync.v5.SyncWriteGuard
import kotlinx.serialization.json.JsonObject
import kotlin.time.Clock

sealed interface PullOutcome {
    data class Completed(val appliedCount: Int, val cursorRev: Long) : PullOutcome
    data object CursorExpired : PullOutcome
    data class DeviceRevoked(val revokedAt: String?) : PullOutcome
    data class TransientFailure(val message: String) : PullOutcome
}

/**
 * Pulls ordered Plane A deltas from `sync_pull_v5` and applies them inside [SyncWriteGuard.withRemoteApply].
 */
class DeltaReplicator(
    private val store: SyncV5Store,
    private val client: PlaneAStateSyncClient,
    private val appliers: EntityApplierRegistry,
    private val writeGuard: SyncWriteGuard,
    private val onSidecarCatalog: suspend (JsonObject) -> Unit = {},
    private val nowMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    suspend fun pullToHead(
        userId: String,
        deviceId: String,
    ): PullOutcome {
        var checkpoint = store.getCheckpoint(userId)
            ?: SyncCheckpointRow(userId = userId, deviceId = deviceId)
        var totalApplied = 0
        var latestHlc = checkpoint.lastHlc

        while (true) {
            when (val res = client.pull(deviceId, sinceRev = checkpoint.pullCursorRev)) {
                is RpcResult.Ok -> {
                    val data = res.data
                    writeGuard.withRemoteApply {
                        data.catalog?.let { onSidecarCatalog(it) }
                        for (change in data.changes) {
                            latestHlc = maxOf(latestHlc, HybridLogicalClock.merge(change.hlc, nowMs()))
                            appliers.apply(
                                entityType = change.entityType,
                                op = change.op,
                                entityKey = change.entityKey,
                                hlc = change.hlc,
                                modifiedBy = change.originDeviceId,
                                payload = change.payload,
                            )
                            totalApplied++
                        }
                    }

                    val nextCursor = data.nextRev ?: checkpoint.pullCursorRev
                    checkpoint = checkpoint.copy(
                        pullCursorRev = nextCursor,
                        lastHlc = latestHlc,
                        lastSyncedAtMs = nowMs(),
                    )
                    store.saveCheckpoint(checkpoint)

                    if (!data.hasMore) {
                        return PullOutcome.Completed(totalApplied, nextCursor)
                    }
                }
                is RpcResult.CursorExpired -> {
                    store.saveCheckpoint(checkpoint.copy(bootstrapComplete = false))
                    return PullOutcome.CursorExpired
                }
                is RpcResult.DeviceRevoked -> return PullOutcome.DeviceRevoked(res.revokedAt)
                is RpcResult.RateLimited -> return PullOutcome.TransientFailure("Rate limited")
                is RpcResult.Unauthorized -> return PullOutcome.TransientFailure(res.reason)
                is RpcResult.TransientFailure -> return PullOutcome.TransientFailure(res.message)
                is RpcResult.TerminalError -> return PullOutcome.TransientFailure(res.message)
                is RpcResult.ClockSkew -> return PullOutcome.TransientFailure("Clock skew")
            }
        }
    }
}
