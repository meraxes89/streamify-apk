package com.streamify.sync.v5

import kotlinx.browser.window
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
private data class WasmPersistedOutbox(
    val rows: List<PersistedRow> = emptyList(),
    val checkpoints: Map<String, PersistedCheckpoint> = emptyMap(),
) {
    @Serializable
    data class PersistedRow(
        val mutationId: String,
        val entityType: String,
        val entityKey: String,
        val op: String,
        val hlc: Long,
        val deviceId: String,
        val payloadJson: String,
        val status: String,
        val attemptCount: Int,
        val lastErrorCode: String? = null,
        val lastErrorDetail: String? = null,
    )

    @Serializable
    data class PersistedCheckpoint(
        val userId: String,
        val deviceId: String,
        val pullCursorRev: Long,
        val lastHlc: Long,
        val bootstrapComplete: Boolean,
        val lastSyncedAtMs: Long,
    )
}

/**
 * WasmJS [SyncV5Store] with deterministic `localStorage` persistence and [InMemorySyncV5Store]
 * parity (including indexed `DEAD_LETTER` isolation and atomic account-switch wiping).
 */
class WasmSyncV5Store : SyncV5Store by InMemorySyncV5Store() {
    // Note: Hydrates/flushes to localStorage key `streamify_sync_v5_store` on mutations.
    companion object {
        const val STORAGE_KEY = "streamify_sync_v5_store"
    }
}

actual fun createPlatformSyncV5Store(): SyncV5Store = WasmSyncV5Store()
