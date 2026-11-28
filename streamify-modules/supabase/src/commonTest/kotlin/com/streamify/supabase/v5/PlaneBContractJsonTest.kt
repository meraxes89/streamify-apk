package com.streamify.supabase.v5

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaneBContractJsonTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun decodesIngestPlaybackEventsResponse() {
        val raw = """
            {
              "status": "OK",
              "server_time_ms": "1795738800412",
              "profile_totals": { "total_plays": 148, "listening_seconds": 38420 },
              "results": [
                {
                  "client_event_id": "019a8f12-77b1-7c30-a110-998877665544",
                  "status": "updated",
                  "accepted_duration_sec": 60,
                  "play_counted": true,
                  "delta_sec": 30,
                  "delta_plays": 0
                }
              ]
            }
        """.trimIndent()
        val parsed = json.decodeFromString(IngestPlaybackEventsResponse.serializer(), raw)
        assertEquals("OK", parsed.status)
        assertEquals(1795738800412L, parsed.serverTimeMs)
        assertEquals(148L, parsed.profileTotals?.totalPlays)
        assertEquals(1, parsed.results.size)
        assertEquals(60, parsed.results.first().acceptedDurationSec)
        assertTrue(parsed.results.first().playCounted == true)
    }

    @Test
    fun decodesFetchSearchHistoryWithClearEpoch() {
        val raw = """
            {
              "status": "OK",
              "server_time": "2026-11-28T00:21:00Z",
              "search_cleared_at": "2026-11-28T00:20:00Z",
              "clear_epoch": "9007199254740995",
              "full_reset_required": true,
              "active_items": [
                {
                  "query_norm": "radiohead",
                  "query": "Radiohead",
                  "result_type": "artist",
                  "searched_at": "2026-11-28T00:20:30Z",
                  "updated_at": "2026-11-28T00:20:30Z"
                }
              ],
              "tombstones": [
                { "query_norm": "daft punk", "deleted_at": "2026-11-28T00:19:00Z" }
              ]
            }
        """.trimIndent()
        val parsed = json.decodeFromString(FetchSearchHistoryResponse.serializer(), raw)
        assertEquals(9007199254740995L, parsed.clearEpoch)
        assertTrue(parsed.fullResetRequired)
        assertEquals("radiohead", parsed.activeItems.single().queryNorm)
        assertEquals("daft punk", parsed.tombstones.single().queryNorm)
    }

    @Test
    fun decodesComputeWrappedSnapshotResponse() {
        val raw = """
            {
              "status": "OK",
              "cached": false,
              "snapshot": {
                "period_key": "2026",
                "schema_version": 1,
                "source_event_count": 412,
                "total_listening_sec": "84210",
                "summary_json": { "unique_tracks": 193 },
                "checksum": "sha256:abc12345",
                "is_finalized": false
              }
            }
        """.trimIndent()
        val parsed = json.decodeFromString(ComputeWrappedSnapshotResponse.serializer(), raw)
        assertFalse(parsed.cached)
        assertEquals("2026", parsed.snapshot?.periodKey)
        assertEquals(84210L, parsed.snapshot?.totalListeningSec)
    }
}
