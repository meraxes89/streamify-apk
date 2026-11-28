package com.streamify.supabase.v5

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlaneFContractJsonTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun decodesFetchActiveSessionV5WithQueue() {
        val raw = """
            {
              "status": "OK",
              "server_time_ms": "1795740130000",
              "live_devices": [],
              "handoff": {
                "device_id": "android_1",
                "platform": "android",
                "track_id": "yt:abc",
                "position_ms": 42000,
                "handoff_epoch": "7"
              },
              "queue": {
                "handoff_epoch": "7",
                "track_ids": ["yt:abc", "yt:def"],
                "queue_index": 0,
                "shuffle_enabled": true,
                "repeat_mode": "all"
              }
            }
        """.trimIndent()
        val parsed = json.decodeFromString(FetchActiveSessionV5Response.serializer(), raw)
        assertEquals(7L, parsed.handoff?.handoffEpoch)
        assertEquals(listOf("yt:abc", "yt:def"), parsed.queue?.trackIds)
        assertTrue(parsed.queue?.shuffleEnabled == true)
    }

    @Test
    fun decodesGetHybridHomeShelves() {
        val raw = """
            {
              "status": "OK",
              "daypart": "evening",
              "ttl_sec": 900,
              "shelves": {
                "community_hits": [
                  {
                    "track_id": "yt:abc",
                    "title": "Instant Crush",
                    "artist_name": "Daft Punk",
                    "duration_sec": 337,
                    "signal": "community"
                  }
                ],
                "circadian_daypart": []
              }
            }
        """.trimIndent()
        val parsed = json.decodeFromString(GetHybridHomeShelvesResponse.serializer(), raw)
        assertEquals("evening", parsed.daypart)
        assertEquals("yt:abc", parsed.shelves?.communityHits?.single()?.trackId)
    }
}
