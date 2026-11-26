package com.streamify.sync

import com.maxrave.domain.data.entities.LocalPlaylistEntity
import com.maxrave.domain.data.entities.analytics.PlaybackEventEntity
import com.maxrave.domain.data.entities.sync.SyncChangeEntity

object SyncKind {
    /** key: videoId, value "1"/"0". */
    const val SONG_LIKE = "song_like"

    /** key: videoId, value "1"/"0". */
    const val LIBRARY_TRACK = "library_track"

    /** key: channelId, value "1"/"0". */
    const val ARTIST_FOLLOW = "artist_follow"

    /** key: album browseId, value "1"/"0". */
    const val ALBUM_LIKE = "album_like"

    /** key: YouTube playlist id, value "1"/"0". */
    const val PLAYLIST_LIKE = "playlist_like"

    /** key: podcastId, value "1"/"0". */
    const val PODCAST_FAVORITE = "podcast_fav"

    /** key: local playlist syncId; UPSERT carries [PlaylistMeta], DELETE nothing. */
    const val LOCAL_PLAYLIST = "lp"

    /** key: "<syncId>|<videoId>", value "1" in the playlist, "0" taken out. */
    const val LOCAL_PLAYLIST_TRACK = "lp_track"

    /** key: local playlist syncId; value the videoIds in order (filled in when sent). */
    const val LOCAL_PLAYLIST_ORDER = "lp_order"

    /** key: the play's syncId; the play itself travels as an EventWire. */
    const val HISTORY = "history"

    /** key: setting key, value: string setting value. */
    const val SETTINGS = "settings"

    /** key: episodeId, value: json with podcast_id, position_sec, completed. */
    const val EPISODE_STATE = "episode_state"
}

object SyncOp {
    const val SET = "SET"
    const val UPSERT = "UPSERT"
    const val DELETE = "DELETE"
    const val ADD = "ADD"
}

interface SyncOutboxRepository {
    suspend fun recordPending(change: SyncChangeEntity)
    suspend fun getPending(limit: Int = 500): List<SyncChangeEntity>
    suspend fun markSent(changeIds: List<String>, seq: Long)
    suspend fun markRejected(changeIds: List<String>)
    suspend fun pruneSent(beforeTimestamp: Long)
    suspend fun getPlaybackEventsBySyncIds(syncIds: List<String>): List<PlaybackEventEntity>
    suspend fun getLocalPlaylistBySyncId(syncId: String): LocalPlaylistEntity?
    suspend fun setLocalPlaylistSyncId(id: Long, syncId: String)
    suspend fun getPlaylistTrackOrder(playlistId: Long): List<String>
    suspend fun getPendingCount(): Int
}

expect fun withSyncApplying(): kotlin.coroutines.CoroutineContext

expect fun createPlatformSyncOutboxRepository(): SyncOutboxRepository
