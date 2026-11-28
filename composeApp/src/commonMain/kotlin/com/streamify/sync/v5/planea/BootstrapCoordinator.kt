package com.streamify.sync.v5.planea

import com.streamify.supabase.v5.PlaneAStateSyncClient
import com.streamify.supabase.v5.RpcResult
import com.streamify.sync.HybridLogicalClock
import com.streamify.sync.v5.SyncCheckpointRow
import com.streamify.sync.v5.SyncV5Store
import com.streamify.sync.v5.SyncWriteGuard
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlin.random.Random
import kotlin.time.Clock

sealed interface BootstrapOutcome {
    data class Completed(val snapshotRev: Long, val rowCount: Int) : BootstrapOutcome
    data class DeviceRevoked(val revokedAt: String?) : BootstrapOutcome
    data class TransientFailure(val message: String) : BootstrapOutcome
}

/**
 * Snapshot-anchored paginated hydration (`sync_bootstrap_v5`).
 * Preserves any unsynced local outbox mutations during recovery.
 */
class BootstrapCoordinator(
    private val store: SyncV5Store,
    private val client: PlaneAStateSyncClient,
    private val appliers: EntityApplierRegistry,
    private val writeGuard: SyncWriteGuard,
    private val onSidecarCatalog: suspend (JsonObject) -> Unit = {},
    private val pruneMissingAfterBootstrap: suspend (bootstrapGenId: String, hasPending: suspend (String, String) -> Boolean) -> Unit = { _, _ -> },
    private val nowMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    suspend fun runBootstrap(
        userId: String,
        deviceId: String,
    ): BootstrapOutcome {
        val bootstrapGenId = "boot_${nowMs()}_${Random.nextInt(100_000, 999_999)}"
        var pageToken: JsonElement? = null
        var snapshotRev = 0L
        var totalRows = 0
        var latestHlc = store.getCheckpoint(userId)?.lastHlc ?: 0L

        while (true) {
            when (val res = client.bootstrap(pageToken = pageToken)) {
                is RpcResult.Ok -> {
                    val page = res.data
                    if (pageToken == null) {
                        snapshotRev = page.snapshotRev
                    }
                    writeGuard.withRemoteApply {
                        page.catalog?.let { onSidecarCatalog(it) }
                        for (row in page.rows) {
                            latestHlc = maxOf(latestHlc, HybridLogicalClock.merge(row.hlc, nowMs()))
                            appliers.apply(
                                entityType = row.entityType,
                                op = "upsert",
                                entityKey = row.entityKey,
                                hlc = row.hlc,
                                modifiedBy = row.modifiedBy,
                                payload = row.payload,
                                bootstrapGenId = bootstrapGenId,
                            )
                            totalRows++
                        }
                    }

                    if (!page.hasMore || page.nextPageToken == null) {
                        writeGuard.withRemoteApply {
                            pruneMissingAfterBootstrap(bootstrapGenId) { type, key ->
                                store.hasPendingOrInFlight(type, key)
                            }
                        }
                        store.saveCheckpoint(
                            SyncCheckpointRow(
                                userId = userId,
                                deviceId = deviceId,
                                pullCursorRev = snapshotRev,
                                lastHlc = latestHlc,
                                bootstrapComplete = true,
                                lastSyncedAtMs = nowMs(),
                            ),
                        )
                        return BootstrapOutcome.Completed(snapshotRev, totalRows)
                    }
                    pageToken = page.nextPageToken
                }
                is RpcResult.DeviceRevoked -> return BootstrapOutcome.DeviceRevoked(res.revokedAt)
                is RpcResult.RateLimited -> return BootstrapOutcome.TransientFailure("Rate limited")
                is RpcResult.Unauthorized -> return BootstrapOutcome.TransientFailure(res.reason)
                is RpcResult.TransientFailure -> return BootstrapOutcome.TransientFailure(res.message)
                is RpcResult.TerminalError -> return BootstrapOutcome.TransientFailure(res.message)
                is RpcResult.CursorExpired, is RpcResult.ClockSkew ->
                    return BootstrapOutcome.TransientFailure("Unexpected bootstrap response")
            }
        }
    }
}
