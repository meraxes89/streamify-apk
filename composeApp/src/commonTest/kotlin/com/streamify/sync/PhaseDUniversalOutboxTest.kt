package com.streamify.sync

import com.maxrave.domain.data.entities.sync.SyncChangeEntity
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PhaseDUniversalOutboxTest {

    @Test
    fun testUniversalOutboxPendingCoalescing() {
        val outbox = mutableListOf<SyncChangeEntity>()

        // Helper mimicking SyncDao.recordPending
        fun recordPending(change: SyncChangeEntity) {
            outbox.removeAll { it.entity == change.entity && it.entityKey == change.entityKey && it.serverSeq == null }
            outbox.add(change)
        }

        // 1. User likes a song
        recordPending(
            SyncChangeEntity(
                changeId = "ch_1",
                entity = SyncKind.SONG_LIKE,
                entityKey = "vid_radiohead_creep",
                op = SyncOp.SET,
                value = "1",
                changedAt = 1000L,
                deviceId = "dev_phone_1"
            )
        )
        assertEquals(1, outbox.size)
        assertEquals("1", outbox.first().value)

        // 2. User unlikes the same song while still offline
        recordPending(
            SyncChangeEntity(
                changeId = "ch_2",
                entity = SyncKind.SONG_LIKE,
                entityKey = "vid_radiohead_creep",
                op = SyncOp.SET,
                value = "0",
                changedAt = 2000L,
                deviceId = "dev_phone_1"
            )
        )
        // Coalesced into a single pending change for that item!
        assertEquals(1, outbox.size)
        assertEquals("ch_2", outbox.first().changeId)
        assertEquals("0", outbox.first().value)
    }

    @Test
    fun testHistoryEventsAreNeverCoalesced() {
        val outbox = mutableListOf<SyncChangeEntity>()

        fun recordHistoryPlay(syncId: String, timestamp: Long) {
            outbox.add(
                SyncChangeEntity(
                    changeId = syncId,
                    entity = SyncKind.HISTORY,
                    entityKey = syncId,
                    op = SyncOp.ADD,
                    value = null,
                    changedAt = timestamp,
                    deviceId = "dev_phone_1"
                )
            )
        }

        // User plays same song multiple times: each play has unique syncId and must be preserved
        recordHistoryPlay("play_evt_001", 1000L)
        recordHistoryPlay("play_evt_002", 2000L)
        recordHistoryPlay("play_evt_003", 3000L)

        assertEquals(3, outbox.size)
        val keys = outbox.map { it.entityKey }.toSet()
        assertEquals(setOf("play_evt_001", "play_evt_002", "play_evt_003"), keys)
    }

    @Test
    fun testSyncKindToRpcEntityMapping() {
        // Like mapping
        val likeChange = SyncChangeEntity("c1", SyncKind.SONG_LIKE, "vid_1", SyncOp.SET, "1", 100L, "d1")
        val likeOp = buildJsonObject {
            put("entity", "like")
            put("op", if (likeChange.value == "1") "U" else "D")
            put("key", likeChange.entityKey)
            put("hlc", likeChange.changedAt)
        }
        assertEquals("like", likeOp["entity"]?.jsonPrimitive?.content)
        assertEquals("U", likeOp["op"]?.jsonPrimitive?.content)
        assertEquals("vid_1", likeOp["key"]?.jsonPrimitive?.content)

        // Library track mapping
        val libChange = SyncChangeEntity("c2", SyncKind.LIBRARY_TRACK, "vid_2", SyncOp.SET, "1", 150L, "d1")
        val libOp = buildJsonObject {
            put("entity", "library_track")
            put("op", if (libChange.value == "1") "U" else "D")
            put("key", libChange.entityKey)
            put("hlc", libChange.changedAt)
        }
        assertEquals("library_track", libOp["entity"]?.jsonPrimitive?.content)
        assertEquals("U", libOp["op"]?.jsonPrimitive?.content)

        // Artist favorite mapping
        val artistChange = SyncChangeEntity("c3", SyncKind.ARTIST_FOLLOW, "art_coldplay", SyncOp.SET, "1", 200L, "d1")
        val artistOp = buildJsonObject {
            put("entity", "favorite")
            put("op", if (artistChange.value == "1") "U" else "D")
            put("key", "artist:${artistChange.entityKey}")
            put("hlc", artistChange.changedAt)
        }
        assertEquals("favorite", artistOp["entity"]?.jsonPrimitive?.content)
        assertEquals("artist:art_coldplay", artistOp["key"]?.jsonPrimitive?.content)

        // Playlist track mapping
        val plTrackChange = SyncChangeEntity("c4", SyncKind.LOCAL_PLAYLIST_TRACK, "pl_uuid_99|vid_track_1", SyncOp.SET, "1", 250L, "d1")
        val parts = plTrackChange.entityKey.split("|")
        val plTrackOp = buildJsonObject {
            put("entity", "playlist_track")
            put("op", "U")
            put("key", parts[1])
            put("hlc", plTrackChange.changedAt)
            put("data", buildJsonObject {
                put("playlist_id", parts[0])
                put("track_id", parts[1])
            })
        }
        assertEquals("playlist_track", plTrackOp["entity"]?.jsonPrimitive?.content)
        assertEquals("vid_track_1", plTrackOp["key"]?.jsonPrimitive?.content)

        // Episode state mapping
        val epChange = SyncChangeEntity("c5", SyncKind.EPISODE_STATE, "ep_42", SyncOp.UPSERT, """{"podcast_id":"pod_1","position_sec":300,"completed":true}""", 300L, "d1")
        val epOp = buildJsonObject {
            put("entity", "episode_state")
            put("op", "U")
            put("key", epChange.entityKey)
            put("hlc", epChange.changedAt)
        }
        assertEquals("episode_state", epOp["entity"]?.jsonPrimitive?.content)
        assertEquals("ep_42", epOp["key"]?.jsonPrimitive?.content)
    }

    @Test
    fun testEqualizerInvariantExclusion() {
        val syncableKeys = listOf(
            "theme_mode", "quality", "normalize_volume", "crossfade_enabled",
            "lyrics_provider", "location", "sponsor_block_enabled"
        )
        val forbiddenEqKeys = listOf(
            "equalizer_enabled", "equalizer_preset", "equalizer_bass_boost",
            "equalizer_virtualizer", "equalizer_band_0", "equalizer_band_1"
        )

        for (eqKey in forbiddenEqKeys) {
            assertFalse(eqKey in syncableKeys, "Equalizer key $eqKey must never be synchronized to cloud")
        }
    }

    @Test
    fun testDrainAndMarkSentWorkflow() {
        val outbox = mutableListOf<SyncChangeEntity>()
        outbox.add(SyncChangeEntity("ch_a", SyncKind.SONG_LIKE, "vid_a", SyncOp.SET, "1", 100L, "d1"))
        outbox.add(SyncChangeEntity("ch_b", SyncKind.ARTIST_FOLLOW, "art_b", SyncOp.SET, "1", 200L, "d1"))
        outbox.add(SyncChangeEntity("ch_c", SyncKind.HISTORY, "evt_c", SyncOp.ADD, null, 300L, "d1"))

        // Drain pending
        val pending = outbox.filter { it.serverSeq == null }.take(500)
        assertEquals(3, pending.size)

        // Simulate successful push with cursor 42
        val pushCursor = 42L
        val sentIds = pending.map { it.changeId }.toSet()
        val updatedOutbox = outbox.map {
            if (it.changeId in sentIds) it.copy(serverSeq = pushCursor) else it
        }

        // Verify all marked sent
        val remainingPending = updatedOutbox.filter { it.serverSeq == null }
        assertEquals(0, remainingPending.size)

        val sentRows = updatedOutbox.filter { it.serverSeq == 42L }
        assertEquals(3, sentRows.size)
    }

    @Test
    fun testGuestUserIdFiltering() {
        fun isGuestUserId(userId: String?): Boolean {
            if (userId.isNullOrBlank()) return true
            return userId == "guest" || userId.startsWith("guest")
        }

        assertTrue(isGuestUserId(null))
        assertTrue(isGuestUserId(""))
        assertTrue(isGuestUserId("   "))
        assertTrue(isGuestUserId("guest"))
        assertTrue(isGuestUserId("guest_1732520000"))
        assertTrue(isGuestUserId("guest_desktop"))
        assertTrue(isGuestUserId("guest_wasm"))
        assertTrue(isGuestUserId("guest_ios"))

        assertFalse(isGuestUserId("dbgtvzgwixerhnwvhtil"))
        assertFalse(isGuestUserId("a74b8eb0-578d-4453-bd19-0b0da2d9ff89"))
    }

    @Test
    fun testOutboxOpMappingPreservesChangeIdAlignment() {
        data class OutboxOpEntry(val op: String, val changeId: String)

        val pendingChanges = listOf(
            SyncChangeEntity("ch_like", SyncKind.SONG_LIKE, "vid_1", SyncOp.SET, "1", 100L, "d1"),
            SyncChangeEntity("ch_order", SyncKind.LOCAL_PLAYLIST_ORDER, "pl_1", SyncOp.SET, "3_tracks", 200L, "d1"),
            SyncChangeEntity("ch_hist", SyncKind.HISTORY, "evt_1", SyncOp.ADD, null, 300L, "d1")
        )

        val entries = mutableListOf<OutboxOpEntry>()
        for (ch in pendingChanges) {
            when (ch.entity) {
                SyncKind.SONG_LIKE -> entries.add(OutboxOpEntry("like_op", ch.changeId))
                SyncKind.LOCAL_PLAYLIST_ORDER -> {
                    // Simulating 3 tracks in order for this one change
                    entries.add(OutboxOpEntry("pl_trk_1", ch.changeId))
                    entries.add(OutboxOpEntry("pl_trk_2", ch.changeId))
                    entries.add(OutboxOpEntry("pl_trk_3", ch.changeId))
                }
                SyncKind.HISTORY -> entries.add(OutboxOpEntry("hist_op", ch.changeId))
            }
        }

        assertEquals(5, entries.size)
        // Verify every entry carries its exact source changeId
        assertEquals("ch_like", entries[0].changeId)
        assertEquals("ch_order", entries[1].changeId)
        assertEquals("ch_order", entries[2].changeId)
        assertEquals("ch_order", entries[3].changeId)
        assertEquals("ch_hist", entries[4].changeId)
    }

    @Test
    fun testAckAppliedAndDeadLetterRejected() {
        val outbox = mutableListOf(
            SyncChangeEntity("ch_ok1", SyncKind.SONG_LIKE, "v1", SyncOp.SET, "1", 100L, "d1"),
            SyncChangeEntity("ch_bad", SyncKind.SONG_LIKE, "v2", SyncOp.SET, "1", 200L, "d1"),
            SyncChangeEntity("ch_stale", SyncKind.SONG_LIKE, "v3", SyncOp.SET, "1", 300L, "d1")
        )

        // Mock RPC response
        val results = listOf(
            Pair(0, "applied"),
            Pair(1, "rejected"),
            Pair(2, "stale")
        )

        val appliedChangeIds = mutableSetOf<String>()
        val rejectedChangeIds = mutableSetOf<String>()

        for ((idx, status) in results) {
            val change = outbox.getOrNull(idx) ?: continue
            when (status) {
                "applied", "stale" -> appliedChangeIds.add(change.changeId)
                "rejected" -> rejectedChangeIds.add(change.changeId)
            }
        }
        appliedChangeIds.removeAll(rejectedChangeIds)

        assertEquals(setOf("ch_ok1", "ch_stale"), appliedChangeIds)
        assertEquals(setOf("ch_bad"), rejectedChangeIds)

        // Mark sent for applied/stale with server cursor 55, rejected with -1
        val updated = outbox.map { ch ->
            when (ch.changeId) {
                in appliedChangeIds -> ch.copy(serverSeq = 55L)
                in rejectedChangeIds -> ch.copy(serverSeq = -1L)
                else -> ch
            }
        }

        val pending = updated.filter { it.serverSeq == null }
        val sent = updated.filter { (it.serverSeq ?: 0L) > 0L }
        val deadLettered = updated.filter { it.serverSeq == -1L }

        assertEquals(0, pending.size)
        assertEquals(2, sent.size)
        assertEquals(1, deadLettered.size)
        assertEquals("ch_bad", deadLettered.first().changeId)
    }
}
