package com.streamify.supabase

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SyncCompatHlcTest {

    @Test
    fun nextCompatHlcPreservesExplicitOrGeneratesPackedHlc() {
        val explicit = 1_760_000_000_000L shl 22
        assertEquals(explicit, SupabaseLibrarySync.nextCompatHlc(explicit))

        val generated = SupabaseLibrarySync.nextCompatHlc(0L)
        assertTrue(generated >= (1_700_000_000_000L shl 22))
    }

    @Test
    fun buildSyncOpShapesSearchAndHistoryPayloadsForSyncPush() {
        val hlc = 1_760_000_000_000L shl 22
        val searchOp = SupabaseLibrarySync.buildSyncOp(
            entity = "search",
            op = "U",
            key = "lofi beats",
            hlc = hlc,
        )
        assertEquals("search", searchOp["entity"]?.jsonPrimitive?.content)
        assertEquals("U", searchOp["op"]?.jsonPrimitive?.content)
        assertEquals("lofi beats", searchOp["key"]?.jsonPrimitive?.content)
        assertEquals(hlc, searchOp["hlc"]?.jsonPrimitive?.long)

        val historyOp = SupabaseLibrarySync.buildSyncOp(
            entity = "history",
            op = "U",
            key = "evt-123",
            hlc = hlc,
            data = buildJsonObject {
                put("client_event_id", "evt-123")
                put("track_id", "dQw4w9WgXcQ")
                put("listened_duration_sec", 42)
            },
        )
        assertEquals("history", historyOp["entity"]?.jsonPrimitive?.content)
        assertEquals("evt-123", historyOp["data"]?.jsonObject?.get("client_event_id")?.jsonPrimitive?.content)
    }
}
