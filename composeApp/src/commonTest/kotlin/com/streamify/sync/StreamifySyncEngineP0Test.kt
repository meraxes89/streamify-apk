package com.streamify.sync

import com.maxrave.domain.data.entities.SongEntity
import com.maxrave.domain.data.entities.analytics.PlaybackEventEntity
import com.maxrave.domain.data.entities.sync.SyncChangeEntity
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
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

    @Test
    fun isStaleByWatermarkRejectsOlderOrEqualOps() {
        val watermarks = mapOf(
            "like:vid_1" to 2000L,
            "playlist_track:pl_1|vid_2" to 3000L,
        )
        assertTrue(StreamifySyncEngine.isStaleByWatermark("like", "vid_1", null, 1500L, watermarks))
        assertTrue(StreamifySyncEngine.isStaleByWatermark("like", "vid_1", null, 2000L, watermarks))
        assertFalse(StreamifySyncEngine.isStaleByWatermark("like", "vid_1", null, 2001L, watermarks))
        assertTrue(StreamifySyncEngine.isStaleByWatermark("playlist_track", "row_uuid", "pl_1|vid_2", 2500L, watermarks))
        assertFalse(StreamifySyncEngine.isStaleByWatermark("playlist_track", "row_uuid", "pl_1|vid_2", 3500L, watermarks))
        assertFalse(StreamifySyncEngine.isStaleByWatermark("like", "vid_1", null, 0L, watermarks))
    }

    @Test
    fun syncPullResultDecodesHeadCursor() {
        val decoded = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
            .decodeFromString<com.streamify.supabase.SyncPullResult>(
                """{"cursor":10,"head_cursor":999,"has_more":true,"ops":[]}"""
            )
        assertEquals(10L, decoded.cursor)
        assertEquals(999L, decoded.headCursor)
        assertTrue(decoded.hasMore)
    }

    private fun runImmediateSuspend(block: suspend () -> Unit) {
        var failure: Throwable? = null
        block.startCoroutine(Continuation(EmptyCoroutineContext) { result ->
            failure = result.exceptionOrNull()
        })
        failure?.let { throw it }
    }

    @Test
    fun resolveOrPersistPlaylistSyncIdGeneratesAndPersistsForNullOrLegacyId() = runImmediateSuspend {
        val persisted = mutableListOf<Pair<Long, String>>()
        val missingSyncId = com.maxrave.domain.data.entities.LocalPlaylistEntity(
            id = 42L,
            title = "Road Trip",
            syncId = null,
        )
        val generated1 = StreamifySyncEngine.resolveOrPersistPlaylistSyncId(missingSyncId) { id, syncId ->
            persisted.add(id to syncId)
        }
        assertTrue(generated1.isNotBlank())
        assertEquals(listOf(42L to generated1), persisted)

        val legacySyncId = com.maxrave.domain.data.entities.LocalPlaylistEntity(
            id = 43L,
            title = "Legacy Mix",
            syncId = "00000000-0000-4000-8000-000000000043",
        )
        val generated2 = StreamifySyncEngine.resolveOrPersistPlaylistSyncId(legacySyncId) { id, syncId ->
            persisted.add(id to syncId)
        }
        assertFalse(generated2.startsWith("00000000-0000-4000-8000-"))
        assertEquals(43L to generated2, persisted.last())

        val validExisting = com.maxrave.domain.data.entities.LocalPlaylistEntity(
            id = 44L,
            title = "Already Migrated",
            syncId = "11111111-2222-4333-8444-555555555555",
        )
        val reused = StreamifySyncEngine.resolveOrPersistPlaylistSyncId(validExisting) { id, syncId ->
            persisted.add(id to syncId)
        }
        assertEquals("11111111-2222-4333-8444-555555555555", reused)
        assertEquals(2, persisted.size)
    }
}
