package com.streamify.supabase.v5

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class PlaneDContractJsonTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun decodesGetTracksBatchResponse() {
        val raw = """
            {
              "status": "OK",
              "tracks": {
                "yt:9bZkp7q19f0": {
                  "id": "yt:9bZkp7q19f0",
                  "source": "yt",
                  "external_id": "9bZkp7q19f0",
                  "title": "GANGNAM STYLE",
                  "artist_name": "PSY",
                  "duration_sec": 252,
                  "thumb_url": "https://i.ytimg.com/vi/9bZkp7q19f0/maxresdefault.jpg",
                  "metadata_authority": 100,
                  "metadata_quality": 100,
                  "enrichment_status": "enriched",
                  "catalog_version": 2
                }
              },
              "id_aliases": { "9bZkp7q19f0": "yt:9bZkp7q19f0" },
              "unchanged_ids": ["yt:dQw4w9WgXcQ"],
              "missing_ids": ["yt:unknown1234"]
            }
        """.trimIndent()
        val parsed = json.decodeFromString(GetTracksBatchResponse.serializer(), raw)
        assertEquals("OK", parsed.status)
        assertEquals(252, parsed.tracks["yt:9bZkp7q19f0"]?.durationSec)
        assertEquals("yt:9bZkp7q19f0", parsed.idAliases["9bZkp7q19f0"])
        assertEquals(listOf("yt:dQw4w9WgXcQ"), parsed.unchangedIds)
        assertEquals(listOf("yt:unknown1234"), parsed.missingIds)
    }

    @Test
    fun decodesIngestTrackHintsResponse() {
        val raw = """
            {
              "status": "OK",
              "accepted": 1,
              "ignored_higher_authority": 2,
              "enqueued_for_enrichment": 1,
              "rate_limited": false
            }
        """.trimIndent()
        val parsed = json.decodeFromString(IngestTrackHintsResponse.serializer(), raw)
        assertEquals(1, parsed.accepted)
        assertEquals(2, parsed.ignoredHigherAuthority)
        assertFalse(parsed.rateLimited)
    }
}
