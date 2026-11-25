package com.streamify.sync

import com.maxrave.domain.data.entities.SongEntity
import com.maxrave.domain.data.entities.analytics.PlaybackEventEntity
import com.maxrave.domain.data.entities.sync.SyncChangeEntity
import kotlinx.datetime.Instant
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StreamifySyncEngineP0Test {

    @Test
    fun shouldRunDeltaSyncHonorsDoneCheckpointWhenCursorIsZero() {
        assertFalse(StreamifySyncEngine.shouldRunDeltaSync(localCursor = 0L, bootstrapCheckpoint = ""))
        assertFalse(StreamifySyncEngine.shouldRunDeltaSync(localCursor = 0L, bootstrapCheckpoint = "LIKES"))
        assertTrue(
            StreamifySyncEngine.shouldRunDeltaSync(
                localCursor = 0L,
                bootstrapCheckpoint = StreamifySyncEngine.BOOTSTRAP_DONE,
            ),
        )
        assertTrue(StreamifySyncEngine.shouldRunDeltaSync(localCursor = 42L, bootstrapCheckpoint = ""))
    }

    @Test
    fun bootstrapLibrarySongScanIsBounded() {
        assertTrue(StreamifySyncEngine.MAX_BOOTSTRAP_LIBRARY_SONGS in 1..10_000)
    }

    @Test
    fun buildHistoryOutboxOpFallsBackWhenPlaybackEventIsNull() {
        val change = SyncChangeEntity(
            changeId = "chg_1",
            entity = SyncKind.HISTORY,
            entityKey = "play_sync_123",
            op = SyncOp.ADD,
            value = null,
            changedAt = 1_700_000_000_000L,
            deviceId = "dev_1",
        )

        val op = StreamifySyncEngine.buildHistoryOutboxOp(change = change, event = null, song = null)
        assertEquals("history", op["entity"]?.jsonPrimitive?.content)
        assertEquals("I", op["op"]?.jsonPrimitive?.content)
        assertEquals("play_sync_123", op["key"]?.jsonPrimitive?.content)
        assertEquals("1700000000000", op["hlc"]?.jsonPrimitive?.content)

        val data = op["data"]!!.jsonObject
        assertEquals("play_sync_123", data["client_event_id"]?.jsonPrimitive?.content)
        assertEquals("", data["track_id"]?.jsonPrimitive?.content)
        assertEquals("0", data["listened_duration_sec"]?.jsonPrimitive?.content)
        assertEquals(
            Instant.fromEpochMilliseconds(1_700_000_000_000L).toString(),
            data["listened_at"]?.jsonPrimitive?.content,
        )
    }

    @Test
    fun buildHistoryOutboxOpUsesEventAndSongWhenPresent() {
        val change = SyncChangeEntity(
            changeId = "chg_2",
            entity = SyncKind.HISTORY,
            entityKey = "play_sync_456",
            op = SyncOp.ADD,
            value = null,
            changedAt = 1_700_000_001_000L,
            deviceId = "dev_1",
        )
        val event = PlaybackEventEntity(
            videoId = "vid_abc",
            albumBrowseId = "alb_xyz",
            timestamp = Instant.fromEpochMilliseconds(1_700_000_000_500L),
            listenedSecond = 185,
            syncId = "play_sync_456",
        )
        val song = SongEntity(
            videoId = "vid_abc",
            title = "Test Song",
            artistName = listOf("Test Artist"),
            albumName = "Test Album",
        )

        val op = StreamifySyncEngine.buildHistoryOutboxOp(change = change, event = event, song = song)
        val data = op["data"]!!.jsonObject
        assertEquals("play_sync_456", data["client_event_id"]?.jsonPrimitive?.content)
        assertEquals("vid_abc", data["track_id"]?.jsonPrimitive?.content)
        assertEquals("185", data["listened_duration_sec"]?.jsonPrimitive?.content)
        assertEquals("alb_xyz", data["album_id"]?.jsonPrimitive?.content)
        assertEquals("Test Song", data["title"]?.jsonPrimitive?.content)
        assertEquals("Test Artist", data["artist_name"]?.jsonPrimitive?.content)
        assertEquals("Test Album", data["album_name"]?.jsonPrimitive?.content)
    }
}
