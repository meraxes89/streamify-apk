package com.streamify.sync.v5.planeb

import com.streamify.supabase.v5.PlaneBEventClient
import com.streamify.supabase.v5.RpcResult
import com.streamify.supabase.v5.WrappedSnapshotDto
import kotlinx.serialization.json.JsonObject

/**
 * Coordinates Wrapped cloud snapshot backup, checksum deduplication, and server fallback computation.
 */
class WrappedSyncCoordinator(
    private val client: PlaneBEventClient,
) {
    private val lastUploadedChecksumByPeriod = mutableMapOf<String, String>()

    suspend fun uploadIfChanged(
        periodKey: String,
        schemaVersion: Int,
        sourceEventCount: Int,
        totalListeningSec: Long,
        checksum: String,
        isFinalized: Boolean,
        summaryJson: JsonObject,
        force: Boolean = false,
    ): Boolean {
        if (!force && lastUploadedChecksumByPeriod[periodKey] == checksum) {
            return true
        }
        val res = client.upsertWrappedSnapshot(
            periodKey = periodKey,
            schemaVersion = schemaVersion,
            sourceEventCount = sourceEventCount,
            totalListeningSec = totalListeningSec,
            checksum = checksum,
            isFinalized = isFinalized,
            summaryJson = summaryJson,
            force = force,
        )
        return if (res is RpcResult.Ok && res.data.status == "OK") {
            lastUploadedChecksumByPeriod[periodKey] = checksum
            res.data.applied
        } else {
            false
        }
    }

    suspend fun fetchOrCompute(
        periodKey: String,
        forceRecompute: Boolean = false,
    ): WrappedSnapshotDto? {
        if (!forceRecompute) {
            val fetched = client.fetchWrappedSnapshots(listOf(periodKey))
            if (fetched is RpcResult.Ok) {
                val existing = fetched.data.snapshots.firstOrNull()
                if (existing != null) return existing
            }
        }
        return when (val computed = client.computeWrappedSnapshot(periodKey, forceRecompute)) {
            is RpcResult.Ok -> computed.data.snapshot
            else -> null
        }
    }
}
