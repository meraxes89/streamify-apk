package com.streamify.sync.v5.planea

import com.maxrave.common.Config
import com.maxrave.domain.data.entities.LocalPlaylistEntity
import com.maxrave.domain.data.entities.SongEntity
import com.maxrave.domain.extension.now
import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.domain.repository.AlbumRepository
import com.maxrave.domain.repository.ArtistRepository
import com.maxrave.domain.repository.LocalPlaylistRepository
import com.maxrave.domain.repository.PlaylistRepository
import com.maxrave.domain.repository.PodcastRepository
import com.maxrave.domain.repository.SongRepository
import com.streamify.sync.SettingsSyncRegistry
import com.streamify.sync.v5.SettingsSyncObserver
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.lastOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Builds the 7 production [PlaneAEntityApplier] implementations backed by domain repositories
 * and [SettingsSyncRegistry]. Missing optional repositories are safely skipped.
 */
fun buildDefaultPlaneAAppliers(
    songRepository: SongRepository?,
    artistRepository: ArtistRepository?,
    albumRepository: AlbumRepository?,
    playlistRepository: PlaylistRepository?,
    podcastRepository: PodcastRepository?,
    localPlaylistRepository: LocalPlaylistRepository?,
    dataStoreManager: DataStoreManager?,
    settingsObserver: SettingsSyncObserver?,
): List<PlaneAEntityApplier> = listOf(
    UserLikeApplier(songRepository),
    UserLibraryTrackApplier(songRepository),
    UserItemFavoriteApplier(artistRepository, albumRepository, playlistRepository, podcastRepository),
    PlaylistApplier(localPlaylistRepository),
    PlaylistTrackApplier(songRepository, localPlaylistRepository),
    UserSettingApplier(dataStoreManager, settingsObserver),
    PodcastEpisodeStateApplier(podcastRepository),
)

private fun buildSyncedSongEntity(
    videoId: String,
    title: String = "Synced Track",
    artistName: List<String> = listOf("Unknown Artist"),
    thumbnails: String? = null,
    albumName: String? = "Streamify",
    durationSeconds: Int = 0,
    likeStatus: String = "INDIFFERENT",
    liked: Boolean = false,
    inLibrary: kotlinx.datetime.LocalDateTime = Config.REMOVED_SONG_DATE_TIME,
): SongEntity = SongEntity(
    videoId = videoId,
    title = title,
    artistName = artistName,
    albumName = albumName,
    duration = null,
    durationSeconds = durationSeconds,
    thumbnails = thumbnails,
    isAvailable = true,
    isExplicit = false,
    likeStatus = likeStatus,
    liked = liked,
    inLibrary = inLibrary,
    videoType = "MUSIC_VIDEO_TYPE_ATV",
    category = null,
    resultType = null,
)

private class UserLikeApplier(
    private val songRepository: SongRepository?,
) : PlaneAEntityApplier {
    override val entityType: String = "user_like"

    override suspend fun applyRemote(
        op: String,
        entityKey: String,
        hlc: Long,
        modifiedBy: String,
        payload: JsonObject,
        bootstrapGenId: String?,
    ) {
        val repo = songRepository ?: return
        val trackId = payload["track_id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: entityKey
        if (op == "delete") {
            repo.updateLikeStatus(trackId, 0)
            return
        }
        if (repo.getSongById(trackId).firstOrNull() == null) {
            val meta = payload["meta"] as? JsonObject ?: payload
            repo.insertSong(
                buildSyncedSongEntity(
                    videoId = trackId,
                    title = meta["title"]?.jsonPrimitive?.contentOrNull ?: "Synced Track",
                    artistName = listOfNotNull(meta["artist"]?.jsonPrimitive?.contentOrNull).ifEmpty { listOf("Unknown Artist") },
                    thumbnails = meta["cover_url"]?.jsonPrimitive?.contentOrNull?.ifBlank { null },
                    albumName = meta["album"]?.jsonPrimitive?.contentOrNull ?: "Streamify",
                    durationSeconds = meta["duration_sec"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0,
                    likeStatus = "LIKE",
                    liked = true,
                    inLibrary = Config.REMOVED_SONG_DATE_TIME,
                )
            ).lastOrNull()
        }
        repo.updateLikeStatus(trackId, 1)
    }
}

private class UserLibraryTrackApplier(
    private val songRepository: SongRepository?,
) : PlaneAEntityApplier {
    override val entityType: String = "user_library_track"

    override suspend fun applyRemote(
        op: String,
        entityKey: String,
        hlc: Long,
        modifiedBy: String,
        payload: JsonObject,
        bootstrapGenId: String?,
    ) {
        val repo = songRepository ?: return
        val trackId = payload["track_id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: entityKey
        if (op == "delete") {
            repo.updateSongInLibrary(Config.REMOVED_SONG_DATE_TIME, trackId).firstOrNull()
            return
        }
        if (repo.getSongById(trackId).firstOrNull() == null) {
            repo.insertSong(
                buildSyncedSongEntity(
                    videoId = trackId,
                    title = payload["title"]?.jsonPrimitive?.contentOrNull ?: "Synced Track",
                    likeStatus = "INDIFFERENT",
                    liked = false,
                    inLibrary = now(),
                )
            ).lastOrNull()
        } else {
            repo.updateSongInLibrary(now(), trackId).firstOrNull()
        }
    }
}

private class UserItemFavoriteApplier(
    private val artistRepository: ArtistRepository?,
    private val albumRepository: AlbumRepository?,
    private val playlistRepository: PlaylistRepository?,
    private val podcastRepository: PodcastRepository?,
) : PlaneAEntityApplier {
    override val entityType: String = "user_item_favorite"

    override suspend fun applyRemote(
        op: String,
        entityKey: String,
        hlc: Long,
        modifiedBy: String,
        payload: JsonObject,
        bootstrapGenId: String?,
    ) {
        val itemType = payload["item_type"]?.jsonPrimitive?.contentOrNull ?: entityKey.substringBefore(":")
        val itemId = payload["item_id"]?.jsonPrimitive?.contentOrNull ?: entityKey.substringAfter(":")
        val status = if (op == "delete") 0 else 1
        when (itemType) {
            "artist" -> artistRepository?.updateFollowedStatus(itemId, status)
            "album" -> albumRepository?.updateAlbumLiked(itemId, status)
            "playlist" -> playlistRepository?.updatePlaylistLiked(itemId, status)
            "podcast" -> podcastRepository?.favoritePodcast(itemId, op != "delete")?.firstOrNull()
        }
    }
}

private class PlaylistApplier(
    private val localPlaylistRepository: LocalPlaylistRepository?,
) : PlaneAEntityApplier {
    override val entityType: String = "playlist"

    override suspend fun applyRemote(
        op: String,
        entityKey: String,
        hlc: Long,
        modifiedBy: String,
        payload: JsonObject,
        bootstrapGenId: String?,
    ) {
        val repo = localPlaylistRepository ?: return
        val playlistSyncId = payload["id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: entityKey
        val matched = repo.getAllLocalPlaylists().firstOrNull()?.find { it.syncId == playlistSyncId }
        if (op == "delete") {
            if (matched != null) {
                repo.deleteLocalPlaylist(matched.id, "Synced deletion").firstOrNull()
            }
            return
        }
        val name = payload["name"]?.jsonPrimitive?.contentOrNull ?: "Synced Playlist"
        val cover = payload["cover_url"]?.jsonPrimitive?.contentOrNull?.ifBlank { null }
        val ytId = payload["youtube_playlist_id"]?.jsonPrimitive?.contentOrNull
        if (matched == null) {
            repo.insertLocalPlaylist(
                LocalPlaylistEntity(
                    title = name,
                    thumbnail = cover,
                    syncId = playlistSyncId,
                    youtubePlaylistId = ytId,
                ),
                "Synced from Cloud",
            ).firstOrNull()
        }
    }
}

private class PlaylistTrackApplier(
    private val songRepository: SongRepository?,
    private val localPlaylistRepository: LocalPlaylistRepository?,
) : PlaneAEntityApplier {
    override val entityType: String = "playlist_track"

    override suspend fun applyRemote(
        op: String,
        entityKey: String,
        hlc: Long,
        modifiedBy: String,
        payload: JsonObject,
        bootstrapGenId: String?,
    ) {
        val playlistRepo = localPlaylistRepository ?: return
        val playlistSyncId = payload["playlist_id"]?.jsonPrimitive?.contentOrNull
            ?: entityKey.substringBefore(":", "")
        val trackId = payload["track_id"]?.jsonPrimitive?.contentOrNull
            ?: entityKey.substringAfter(":", "")
        if (playlistSyncId.isBlank() || trackId.isBlank()) return

        val matched = playlistRepo.getAllLocalPlaylists().firstOrNull()?.find { it.syncId == playlistSyncId } ?: return
        val song = songRepository?.getSongById(trackId)?.firstOrNull()
            ?: buildSyncedSongEntity(videoId = trackId)
        if (op == "delete") {
            playlistRepo.removeTrackFromLocalPlaylist(
                id = matched.id,
                song = song,
                successMessage = "",
                updatedYtMessage = "",
                errorMessage = "",
            ).firstOrNull()
            return
        }
        playlistRepo.addTrackToLocalPlaylist(
            id = matched.id,
            song = song,
            successMessage = "",
            updatedYtMessage = "",
            errorMessage = "",
        ).firstOrNull()
    }
}

private class UserSettingApplier(
    private val dataStoreManager: DataStoreManager?,
    private val settingsObserver: SettingsSyncObserver?,
) : PlaneAEntityApplier {
    override val entityType: String = "user_setting"

    override suspend fun applyRemote(
        op: String,
        entityKey: String,
        hlc: Long,
        modifiedBy: String,
        payload: JsonObject,
        bootstrapGenId: String?,
    ) {
        val ds = dataStoreManager ?: return
        if (op == "delete") return
        val key = payload["key"]?.jsonPrimitive?.contentOrNull ?: entityKey
        val valueStr = (payload["value"] as? JsonPrimitive)?.contentOrNull ?: return
        val spec = SettingsSyncRegistry.byKey[key] ?: return
        settingsObserver?.markRemoteWrite(key, valueStr)
        spec.write(ds, valueStr)
    }
}

private class PodcastEpisodeStateApplier(
    private val podcastRepository: PodcastRepository?,
) : PlaneAEntityApplier {
    override val entityType: String = "user_podcast_episode_state"

    override suspend fun applyRemote(
        op: String,
        entityKey: String,
        hlc: Long,
        modifiedBy: String,
        payload: JsonObject,
        bootstrapGenId: String?,
    ) {
        val repo = podcastRepository ?: return
        if (op == "delete") return
        val episodeId = payload["episode_id"]?.jsonPrimitive?.contentOrNull ?: entityKey
        repo.getEpisode(episodeId).firstOrNull()
    }
}
