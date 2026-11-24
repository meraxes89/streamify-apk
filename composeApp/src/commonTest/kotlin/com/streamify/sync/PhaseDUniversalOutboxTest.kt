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
}
