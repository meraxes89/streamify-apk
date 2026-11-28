package com.streamify.sync.v5.planef

import com.streamify.sync.HybridLogicalClock
import com.streamify.sync.v5.FractionalIndex
import com.streamify.sync.v5.SyncOutboxRow
import com.streamify.sync.v5.SyncSessionLifecycle
import com.streamify.sync.v5.SyncV5Store
import com.streamify.sync.v5.SyncWriteGuard
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

enum class PeerTransferMode {
    SAME_ACCOUNT_HYDRATION,
    CROSS_ACCOUNT_CLONE,
    OFFLINE_GUEST_IMPORT,
}

data class PeerPlaylistItem(
    val id: String,
    val name: String,
    val description: String? = null,
    val trackIds: List<String> = emptyList(),
)

data class PeerLibraryPayload(
    val senderUserId: String?,
    val senderDeviceId: String,
    val likedTrackIds: List<String> = emptyList(),
    val libraryTrackIds: List<String> = emptyList(),
    val playlists: List<PeerPlaylistItem> = emptyList(),
)

data class PeerImportReport(
    val mode: PeerTransferMode,
    val entitiesImported: Int,
    val entitiesSkippedDuplicate: Int,
    val outboxMutationsEnqueued: Int,
    val playlistsRemapped: Int,
)

interface PeerLocalStorage {
    suspend fun existingLikedIds(): Set<String>
    suspend fun existingLibraryIds(): Set<String>
    suspend fun insertLike(trackId: String)
    suspend fun insertLibraryTrack(trackId: String)
    suspend fun insertPlaylist(id: String, name: String, description: String?)
    suspend fun insertPlaylistTrack(playlistId: String, trackId: String, sortKey: String)
    suspend fun applyAllowlistedSettings(settings: Map<String, String>)
}

interface PeerSyncBridge {
    suspend fun importLoginTransfer(
        authTransfer: suspend () -> String,
        allowlistedSettings: Map<String, String>,
    )

    suspend fun importLibraryTransfer(payload: PeerLibraryPayload): PeerImportReport
}

/**
 * Collision-proof LAN/QR transfer bridge routed strictly through Plane E's
 * [SyncSessionLifecycle], [SyncWriteGuard], [SyncV5Store], and [FractionalIndex].
 */
class DefaultPeerSyncBridge(
    private val lifecycle: SyncSessionLifecycle,
    private val writeGuard: SyncWriteGuard,
    private val store: SyncV5Store,
    private val localStorage: PeerLocalStorage,
    private val currentUserIdProvider: () -> String?,
    private val localDeviceIdProvider: () -> String,
) : PeerSyncBridge {

    override suspend fun importLoginTransfer(
        authTransfer: suspend () -> String,
        allowlistedSettings: Map<String, String>,
    ) {
        val safeSettings = allowlistedSettings.filterKeys { it !in FORBIDDEN_IDENTITY_KEYS }
        val newUserId = lifecycle.withBackupRestoreFence {
            val uid = authTransfer()
            writeGuard.withRemoteApply {
                localStorage.applyAllowlistedSettings(safeSettings)
            }
            uid
        }
        lifecycle.onUserAuthenticated(newUserId)
    }

    @OptIn(ExperimentalUuidApi::class)
    override suspend fun importLibraryTransfer(payload: PeerLibraryPayload): PeerImportReport {
        val receiverUserId = currentUserIdProvider()
        val mode = when {
            receiverUserId == null -> PeerTransferMode.OFFLINE_GUEST_IMPORT
            payload.senderUserId != null && payload.senderUserId == receiverUserId ->
                PeerTransferMode.SAME_ACCOUNT_HYDRATION
            else -> PeerTransferMode.CROSS_ACCOUNT_CLONE
        }

        val existingLikes = localStorage.existingLikedIds()
        val existingLibrary = localStorage.existingLibraryIds()
        val newLikes = payload.likedTrackIds.distinct().filterNot { it in existingLikes }
        val newLibrary = payload.libraryTrackIds.distinct().filterNot { it in existingLibrary }
        val skipped = (payload.likedTrackIds.size - newLikes.size) +
            (payload.libraryTrackIds.size - newLibrary.size)

        var playlistsRemapped = 0
        val outboxRows = mutableListOf<SyncOutboxRow>()
        val deviceId = localDeviceIdProvider()

        writeGuard.withRemoteApply {
            for (tid in newLikes) {
                localStorage.insertLike(tid)
                if (mode == PeerTransferMode.CROSS_ACCOUNT_CLONE) {
                    outboxRows += SyncOutboxRow(
                        mutationId = Uuid.random().toString(),
                        entityType = "user_like",
                        entityKey = tid,
                        op = "upsert",
                        hlc = HybridLogicalClock.now(),
                        deviceId = deviceId,
                        payloadJson = buildJsonObject { put("track_id", tid) }.toString(),
                    )
                }
            }

            for (tid in newLibrary) {
                localStorage.insertLibraryTrack(tid)
                if (mode == PeerTransferMode.CROSS_ACCOUNT_CLONE) {
                    outboxRows += SyncOutboxRow(
                        mutationId = Uuid.random().toString(),
                        entityType = "user_library_track",
                        entityKey = tid,
                        op = "upsert",
                        hlc = HybridLogicalClock.now(),
                        deviceId = deviceId,
                        payloadJson = buildJsonObject { put("track_id", tid) }.toString(),
                    )
                }
            }

            for (pl in payload.playlists) {
                val targetPlaylistId = if (mode == PeerTransferMode.CROSS_ACCOUNT_CLONE) {
                    playlistsRemapped++
                    Uuid.random().toString()
                } else {
                    pl.id
                }
                localStorage.insertPlaylist(targetPlaylistId, pl.name, pl.description)
                if (mode == PeerTransferMode.CROSS_ACCOUNT_CLONE) {
                    outboxRows += SyncOutboxRow(
                        mutationId = Uuid.random().toString(),
                        entityType = "playlist",
                        entityKey = targetPlaylistId,
                        op = "upsert",
                        hlc = HybridLogicalClock.now(),
                        deviceId = deviceId,
                        payloadJson = buildJsonObject {
                            put("id", targetPlaylistId)
                            put("name", pl.name)
                            if (pl.description != null) put("description", pl.description)
                        }.toString(),
                    )
                }

                var prevKey: String? = null
                for (tid in pl.trackIds.distinct()) {
                    val sortKey = FractionalIndex.between(prevKey, null)
                    prevKey = sortKey
                    localStorage.insertPlaylistTrack(targetPlaylistId, tid, sortKey)
                    if (mode == PeerTransferMode.CROSS_ACCOUNT_CLONE) {
                        outboxRows += SyncOutboxRow(
                            mutationId = Uuid.random().toString(),
                            entityType = "playlist_track",
                            entityKey = "$targetPlaylistId:$tid",
                            op = "upsert",
                            hlc = HybridLogicalClock.now(),
                            deviceId = deviceId,
                            payloadJson = buildJsonObject {
                                put("playlist_id", targetPlaylistId)
                                put("track_id", tid)
                                put("sort_key", sortKey)
                            }.toString(),
                        )
                    }
                }
            }
        }

        for (row in outboxRows) {
            store.enqueueOrCoalesce(row)
        }

        return PeerImportReport(
            mode = mode,
            entitiesImported = newLikes.size + newLibrary.size + payload.playlists.size,
            entitiesSkippedDuplicate = skipped,
            outboxMutationsEnqueued = outboxRows.size,
            playlistsRemapped = playlistsRemapped,
        )
    }

    companion object {
        val FORBIDDEN_IDENTITY_KEYS = setOf(
            "device_id",
            "sync_checkpoint_v5",
            "sync_outbox_v5",
            "sync_dead_letter_v5",
            "playback_event_queue",
            "search_event_queue",
            "localClearEpoch",
        )
    }
}
