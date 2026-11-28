package com.streamify.supabase.v5

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaneAContractJsonTest {
    private val json = SupabaseRpcExecutor.DefaultJson

    @Test
    fun longAsStringSerializerPreservesFull64BitHlcPrecisionAbove53Bits() {
        // 7531942184960001 > Number.MAX_SAFE_INTEGER (9007199254740991)
        val hlc = 7531942184960001L
        val dto = PlaneAMutationDto(
            mutationId = "019a8f10-3b2c-7d1e-9a44-112233445566",
            entityType = "playlist_track",
            entityKey = "pl1:trk1",
            op = "upsert",
            hlc = hlc,
            payload = buildJsonObject {
                put("playlist_id", "550e8400-e29b-41d4-a716-446655440000")
                put("track_id", "trk1")
                put("sort_key", "Vm4K")
            },
        )
        val encoded = json.encodeToString(dto)
        assertTrue(encoded.contains("\"hlc\":\"7531942184960001\""), "Expected base-10 string HLC: $encoded")

        val decoded = json.decodeFromString<PlaneAMutationDto>(encoded)
        assertEquals(hlc, decoded.hlc)
    }

    @Test
    fun pullResponseDecodesStringRevsAndHlcWithoutTruncation() {
        val raw = """
            {
              "protocol_version": 5,
              "status": "OK",
              "server_time_ms": "1795738504200",
              "head_rev": "9007199254740999",
              "min_available_rev": "0",
              "next_rev": "9007199254740999",
              "has_more": false,
              "changes": [
                {
                  "rev": "9007199254740999",
                  "mutation_id": "019a8f10-3b2c-7d1e-9a44-112233445566",
                  "entity_type": "user_like",
                  "entity_key": "trk_1",
                  "op": "upsert",
                  "hlc": "7531942184960003",
                  "origin_device_id": "android_1",
                  "payload": {"track_id": "trk_1"}
                }
              ]
            }
        """.trimIndent()
        val parsed = json.decodeFromString<PlaneAPullResponse>(raw)
        assertEquals(9007199254740999L, parsed.headRev)
        assertEquals(7531942184960003L, parsed.changes.single().hlc)
        assertFalse(parsed.hasMore)
    }
}
