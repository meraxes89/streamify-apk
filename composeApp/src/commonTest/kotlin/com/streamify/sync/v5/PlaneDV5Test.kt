package com.streamify.sync.v5

import com.streamify.supabase.v5.CatalogTrackDto
import com.streamify.supabase.v5.PlaneDCatalogClient
import com.streamify.supabase.v5.TrackHintInputDto
import com.streamify.sync.v5.planed.CatalogHydrationCoordinator
import com.streamify.sync.v5.planed.InMemorySongCatalogStore
import com.streamify.sync.v5.planed.LocalSongCatalogRecord
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class PlaneDV5Test {

    private fun fakeClient(
        handler: suspend (rpcName: String, bodyJson: String) -> FakeRpcResponse,
    ): PlaneDCatalogClient = PlaneDCatalogClient(FakeRpcExecutor(handler))

    @Test
    fun monotonicMergeUpgradesPlaceholderAndNeverDowngradesHigherAuthority() : Unit = runBlocking {
        val store = InMemorySongCatalogStore()
        store.upsertMonotonic(
            LocalSongCatalogRecord(
                id = "yt:abc",
                title = "Synced Track",
                artistName = "Daft Punk",
                durationSec = 0,
                catalogAuthority = 10,
                catalogVersion = 1,
            )
        )
        store.upsertMonotonic(
            LocalSongCatalogRecord(
                id = "yt:verified",
                title = "Canonical Title",
                artistName = "Verified Artist",
                durationSec = 240,
                catalogAuthority = 100,
                catalogVersion = 5,
                enrichmentStatus = "enriched",
            )
        )

        val coordinator = CatalogHydrationCoordinator(store, fakeClient { _, _ -> error("unused") }) { 1000L }
        coordinator.applyServerCatalog(
            mapOf(
                "yt:abc" to CatalogTrackDto(
                    id = "yt:abc",
                    source = "yt",
                    externalId = "abc",
                    title = "Instant Crush",
                    artistName = "Daft Punk",
                    durationSec = 337,
                    metadataAuthority = 100,
                    catalogVersion = 2,
                    enrichmentStatus = "enriched",
                ),
                "yt:verified" to CatalogTrackDto(
                    id = "yt:verified",
                    source = "yt",
                    externalId = "verified",
                    title = "Lower Hint Title",
                    artistName = "Lower Hint Artist",
                    durationSec = 10,
                    metadataAuthority = 10,
                    catalogVersion = 1,
                    enrichmentStatus = "pending",
                ),
            )
        )

        val upgraded = store.getById("yt:abc")!!
        assertEquals("Instant Crush", upgraded.title)
        assertEquals(337, upgraded.durationSec)
        assertEquals(100, upgraded.catalogAuthority)

        val protectedRow = store.getById("yt:verified")!!
        assertEquals("Canonical Title", protectedRow.title)
        assertEquals(240, protectedRow.durationSec)
        assertEquals(100, protectedRow.catalogAuthority)
    }

    @Test
    fun hintsSkipVerifiedTracksAndRepairUsesGetTracksBatch() : Unit = runBlocking {
        val store = InMemorySongCatalogStore()
        store.upsertMonotonic(
            LocalSongCatalogRecord(
                id = "yt:verified",
                title = "Verified",
                artistName = "Artist",
                durationSec = 200,
                catalogAuthority = 100,
                enrichmentStatus = "enriched",
            )
        )
        store.upsertMonotonic(
            LocalSongCatalogRecord(
                id = "yt:needs_dur",
                title = "Track",
                artistName = "Artist",
                durationSec = 0,
                catalogAuthority = 10,
            )
        )

        val client = fakeClient { rpcName, _ ->
            when (rpcName) {
                "ingest_track_hints" -> FakeRpcResponse(200, """{"status":"OK","accepted":1}""")
                "get_tracks_batch" -> FakeRpcResponse(
                    200,
                    """
                    {
                      "status": "OK",
                      "tracks": {
                        "yt:needs_dur": {
                          "id": "yt:needs_dur",
                          "source": "yt",
                          "external_id": "needs_dur",
                          "title": "Track",
                          "artist_name": "Artist",
                          "duration_sec": 185,
                          "metadata_authority": 100,
                          "catalog_version": 2,
                          "enrichment_status": "enriched"
                        }
                      }
                    }
                    """.trimIndent(),
                )
                else -> error("Unexpected $rpcName")
            }
        }
        val coordinator = CatalogHydrationCoordinator(store, client) { 2000L }

        coordinator.enqueueHintIfNeeded(TrackHintInputDto("yt:verified", title = "Override"))
        coordinator.enqueueHintIfNeeded(TrackHintInputDto("yt:needs_dur", title = "Track"))
        assertEquals(1, coordinator.pendingHintCount())
        assertEquals(1, coordinator.flushHints())
        assertEquals(0, coordinator.pendingHintCount())

        assertEquals(1, coordinator.repairMissingTracks())
        assertEquals(185, store.getById("yt:needs_dur")?.durationSec)
    }
}
