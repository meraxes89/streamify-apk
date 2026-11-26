package com.streamify.sync

import com.maxrave.domain.data.entities.SongEntity
import com.maxrave.domain.data.entities.analytics.PlaybackEventEntity
import com.maxrave.domain.data.entities.sync.SyncChangeEntity
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDateTime
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
            timestamp = LocalDateTime(2026, 11, 26, 1, 30, 0),
            listenedSecond = 185,
            syncId = "play_sync_456",
        )
        val song = SongEntity(
            videoId = "vid_abc",
            title = "Test Song",
            artistName = listOf("Test Artist"),
            albumName = "Test Album",
            duration = "3:05",
            durationSeconds = 185,
            isAvailable = true,
            isExplicit = false,
            likeStatus = "INDIFFERENT",
            videoType = "MUSIC_VIDEO_TYPE_ATV",
            category = null,
            resultType = null,
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

    @Test
    fun normalizeOutboxToChangelogKeyMapsAllSyncKinds() {
        assertEquals("like" to "vid1", StreamifySyncEngine.normalizeOutboxToChangelogKey(SyncKind.SONG_LIKE, "vid1"))
        assertEquals("library_track" to "vid2", StreamifySyncEngine.normalizeOutboxToChangelogKey(SyncKind.LIBRARY_TRACK, "vid2"))
        assertEquals("favorite" to "artist:UC1", StreamifySyncEngine.normalizeOutboxToChangelogKey(SyncKind.ARTIST_FOLLOW, "UC1"))
        assertEquals("favorite" to "album:MPRE1", StreamifySyncEngine.normalizeOutboxToChangelogKey(SyncKind.ALBUM_LIKE, "MPRE1"))
        assertEquals("favorite" to "playlist:VL1", StreamifySyncEngine.normalizeOutboxToChangelogKey(SyncKind.PLAYLIST_LIKE, "VL1"))
        assertEquals("favorite" to "podcast:POD1", StreamifySyncEngine.normalizeOutboxToChangelogKey(SyncKind.PODCAST_FAVORITE, "POD1"))
        assertEquals("playlist" to "pl_sync_1", StreamifySyncEngine.normalizeOutboxToChangelogKey(SyncKind.LOCAL_PLAYLIST, "pl_sync_1"))
        assertEquals("playlist" to "pl_sync_1", StreamifySyncEngine.normalizeOutboxToChangelogKey(SyncKind.LOCAL_PLAYLIST_ORDER, "pl_sync_1"))
        assertEquals("playlist_track" to "pl_sync_1|vid1", StreamifySyncEngine.normalizeOutboxToChangelogKey(SyncKind.LOCAL_PLAYLIST_TRACK, "pl_sync_1|vid1"))
        assertEquals("settings" to "settings", StreamifySyncEngine.normalizeOutboxToChangelogKey(SyncKind.SETTINGS, "theme_mode"))
        assertEquals("episode_state" to "ep1", StreamifySyncEngine.normalizeOutboxToChangelogKey(SyncKind.EPISODE_STATE, "ep1"))
        assertEquals("history" to "ev1", StreamifySyncEngine.normalizeOutboxToChangelogKey(SyncKind.HISTORY, "ev1"))
    }
}
