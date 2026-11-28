package com.streamify.sync.v5.planed

import com.streamify.supabase.v5.CatalogTrackDto
import com.streamify.supabase.v5.PlaneDCatalogClient
import com.streamify.supabase.v5.RpcResult
import com.streamify.supabase.v5.TrackHintInputDto
import kotlin.math.max

data class LocalSongCatalogRecord(
    val id: String,
    val title: String,
    val artistName: String,
    val albumName: String? = null,
    val durationSec: Int = 0,
    val thumbUrl: String? = null,
    val catalogAuthority: Int = 0,
    val catalogVersion: Int = 1,
    val enrichmentStatus: String = "pending",
    val hydratedAtMs: Long = 0L,
)

interface SongCatalogStore {
    suspend fun getById(id: String): LocalSongCatalogRecord?
    suspend fun upsertMonotonic(record: LocalSongCatalogRecord)
    suspend fun findIncompleteTracks(limit: Int): List<LocalSongCatalogRecord>
}

class InMemorySongCatalogStore : SongCatalogStore {
    private val map = linkedMapOf<String, LocalSongCatalogRecord>()

    override suspend fun getById(id: String): LocalSongCatalogRecord? = map[id]

    override suspend fun upsertMonotonic(record: LocalSongCatalogRecord) {
        map[record.id] = record
    }

    override suspend fun findIncompleteTracks(limit: Int): List<LocalSongCatalogRecord> =
        map.values
            .filter {
                (it.durationSec <= 0 || it.catalogAuthority < 50) &&
                    it.enrichmentStatus !in setOf("unavailable", "dead_letter")
            }
            .take(limit)
}

/**
 * Echo-free Plane D catalog hydration & hint coordinator.
 * Writes exclusively to [SongCatalogStore] and never touches `sync_outbox_v5`.
 */
class CatalogHydrationCoordinator(
    private val store: SongCatalogStore,
    private val client: PlaneDCatalogClient,
    private val nowMs: () -> Long,
) {
    private val hintBuffer = linkedMapOf<String, TrackHintInputDto>()
    private var lastRepairAtMs: Long = Long.MIN_VALUE

    suspend fun applyServerCatalog(tracks: Map<String, CatalogTrackDto>): Int {
        var applied = 0
        val now = nowMs()
        for ((_, incoming) in tracks) {
            val existing = store.getById(incoming.id)
            if (existing == null) {
                store.upsertMonotonic(
                    LocalSongCatalogRecord(
                        id = incoming.id,
                        title = incoming.title,
                        artistName = incoming.artistName,
                        albumName = incoming.albumName,
                        durationSec = incoming.durationSec ?: 0,
                        thumbUrl = incoming.thumbUrl,
                        catalogAuthority = incoming.metadataAuthority,
                        catalogVersion = incoming.catalogVersion,
                        enrichmentStatus = incoming.enrichmentStatus,
                        hydratedAtMs = now,
                    )
                )
                applied++
                continue
            }

            val inAuth = incoming.metadataAuthority
            val localAuth = existing.catalogAuthority
            val mergedTitle = if (inAuth > localAuth || isPlaceholder(existing.title)) {
                incoming.title
            } else {
                existing.title
            }
            val mergedArtist = if (inAuth > localAuth || isPlaceholder(existing.artistName)) {
                incoming.artistName
            } else {
                existing.artistName
            }
            val mergedAlbum = if (inAuth > localAuth || existing.albumName.isNullOrBlank()) {
                incoming.albumName ?: existing.albumName
            } else {
                existing.albumName
            }
            val inDur = incoming.durationSec ?: 0
            val mergedDuration = if (inDur > 0 && (inAuth >= localAuth || existing.durationSec <= 0)) {
                inDur
            } else {
                existing.durationSec
            }
            val mergedThumb = if (!incoming.thumbUrl.isNullOrBlank() && (inAuth >= localAuth || existing.thumbUrl.isNullOrBlank())) {
                incoming.thumbUrl
            } else {
                existing.thumbUrl
            }

            store.upsertMonotonic(
                existing.copy(
                    title = mergedTitle,
                    artistName = mergedArtist,
                    albumName = mergedAlbum,
                    durationSec = mergedDuration,
                    thumbUrl = mergedThumb,
                    catalogAuthority = max(localAuth, inAuth),
                    catalogVersion = if (inAuth >= localAuth) incoming.catalogVersion else existing.catalogVersion,
                    enrichmentStatus = if (inAuth >= localAuth) incoming.enrichmentStatus else existing.enrichmentStatus,
                    hydratedAtMs = now,
                )
            )
            applied++
        }
        return applied
    }

    suspend fun repairMissingTracks(force: Boolean = false): Int {
        val now = nowMs()
        if (!force && lastRepairAtMs != Long.MIN_VALUE && (now - lastRepairAtMs) < REPAIR_THROTTLE_MS) {
            return 0
        }
        val incomplete = store.findIncompleteTracks(MAX_BATCH_IDS)
        if (incomplete.isEmpty()) {
            lastRepairAtMs = now
            return 0
        }
        val ids = incomplete.map { it.id }
        val knownVersions = incomplete.associate { it.id to it.catalogVersion }

        return when (val res = client.getTracksBatch(ids, knownVersions, enqueueMissing = true)) {
            is RpcResult.Ok -> {
                lastRepairAtMs = now
                applyServerCatalog(res.data.tracks)
            }
            else -> 0
        }
    }

    suspend fun enqueueHintIfNeeded(hint: TrackHintInputDto) {
        val existing = store.getById(hint.trackId)
        if (existing != null && existing.catalogAuthority >= 50) {
            return
        }
        hintBuffer[hint.trackId] = hint
    }

    suspend fun flushHints(): Int {
        if (hintBuffer.isEmpty()) return 0
        val batch = hintBuffer.values.take(MAX_HINT_BATCH)
        return when (val res = client.ingestTrackHints(batch)) {
            is RpcResult.Ok -> {
                for (h in batch) hintBuffer.remove(h.trackId)
                res.data.accepted
            }
            else -> 0
        }
    }

    fun pendingHintCount(): Int = hintBuffer.size

    companion object {
        const val REPAIR_THROTTLE_MS: Long = 30L * 60L * 1000L
        const val MAX_BATCH_IDS: Int = 200
        const val MAX_HINT_BATCH: Int = 100

        private val PLACEHOLDERS = setOf(
            "", "synced track", "unknown track", "untitled", "unknown",
            "unknown artist", "various", "loading...", "null", "n/a",
        )

        fun isPlaceholder(value: String?): Boolean =
            value == null || value.trim().lowercase() in PLACEHOLDERS
    }
}
