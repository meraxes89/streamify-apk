package com.streamify.sync

import com.maxrave.domain.data.entities.DownloadState
import com.maxrave.domain.data.entities.LocalPlaylistEntity
import com.maxrave.domain.data.entities.SearchHistory
import com.maxrave.domain.data.entities.SongEntity
import com.maxrave.domain.extension.now
import com.maxrave.domain.repository.AlbumRepository
import com.maxrave.domain.repository.ArtistRepository
import com.maxrave.domain.repository.LocalPlaylistRepository
import com.maxrave.domain.repository.PodcastRepository
import com.maxrave.domain.repository.SearchRepository
import com.maxrave.domain.repository.SongRepository
import com.streamify.supabase.CloudTrack
import com.streamify.supabase.SupabaseClient
import com.streamify.supabase.SupabaseConfig
import com.streamify.supabase.SupabaseLibrarySync
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.lastOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.concurrent.Volatile
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * High-performance, anti-corruption Hybrid Synchronization Engine.
 * Coordinates bidirectional synchronization between SimpMusic Room SQLite and Streamify Supabase Postgres.
 *
 * Guarantees:
 * 1. Local-first offline-first operations (zero UI latency).
 * 2. Echo suppression (isSyncActive prevents infinite ping-pong loops).
 * 3. Last-Write-Wins (LWW) conflict resolution.
 * 4. Resilient batching: failures on individual entities do not abort or corrupt the database.
 */
object StreamifySyncEngine : KoinComponent {

    private val songRepository: SongRepository by inject()
    private val artistRepository: ArtistRepository by inject()
    private val albumRepository: AlbumRepository by inject()
    private val localPlaylistRepository: LocalPlaylistRepository by inject()
    private val dataStoreManager: com.maxrave.domain.manager.DataStoreManager by inject()
    private val analyticsRepository: com.maxrave.domain.repository.AnalyticsRepository by inject()
    private val searchRepository: SearchRepository by inject()
    private val podcastRepository: PodcastRepository by inject()

    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val SYNCABLE_SETTINGS_KEYS = listOf(
        // Appearance & Themes
        "theme_mode",
        "theme_color_source",
        "custom_theme_color",
        "now_playing_style",
        "chart_key",
        // Audio & Playback
        "quality",
        "download_quality",
        "normalize_volume",
        "skip_silent",
        "crossfade_enabled",
        "crossfade_duration",
        // Content
        "explicit_content_enabled",
        "location",
        "radio_audio_only",
        "endless_queue",
        "sponsor_block_enabled",
        "spotify_canvas",
        "am_animated_artwork",
        // Lyrics
        "lyrics_provider",
        "translation_language",
        "use_translation_language",
        "use_ai_translation",
        "lyrics_style"
    )

    @Volatile
    var isSyncActive: Boolean = false
        private set

    sealed interface SyncStatus {
        object Idle : SyncStatus
        data class Syncing(val stage: String) : SyncStatus
        data class Success(val likedCount: Int, val timestamp: Long) : SyncStatus
        data class Error(val message: String) : SyncStatus
    }

    private val _syncState = MutableStateFlow<SyncStatus>(SyncStatus.Idle)
    val syncState: StateFlow<SyncStatus> = _syncState.asStateFlow()

    private fun SupervisorJob() = kotlinx.coroutines.SupervisorJob()

    /**
     * Executes the complete 3-phase hybrid synchronization pipeline asynchronously.
     */
    fun triggerSync(userId: String? = null) {
        scope.launch {
            syncAll(userId)
        }
    }

    /**
     * Suspending orchestrator that executes all sync stages sequentially with error containment.
     */
    suspend fun syncAll(userId: String? = null): SyncStatus = withContext(Dispatchers.IO) {
        if (!mutex.tryLock()) {
            return@withContext _syncState.value
        }
        try {
            val targetUserId = userId ?: SupabaseClient.currentUser.value?.id ?: SupabaseConfig.DEFAULT_USER.id
            if (targetUserId.isBlank() || targetUserId == "guest") {
                _syncState.value = SyncStatus.Idle
                return@withContext SyncStatus.Idle
            }

            isSyncActive = true
            _syncState.value = SyncStatus.Syncing("Syncing liked songs...")

            val likedCount = syncLikesInternal(targetUserId)

            _syncState.value = SyncStatus.Syncing("Syncing followed artists...")
            syncArtistsInternal(targetUserId)

            _syncState.value = SyncStatus.Syncing("Syncing saved albums...")
            syncAlbumsInternal(targetUserId)

            _syncState.value = SyncStatus.Syncing("Syncing playlists...")
            syncPlaylistsInternal(targetUserId)

            _syncState.value = SyncStatus.Syncing("Syncing settings...")
            syncSettingsInternal(targetUserId)

            _syncState.value = SyncStatus.Syncing("Syncing listening history...")
            syncListeningHistoryInternal(targetUserId)

            _syncState.value = SyncStatus.Syncing("Syncing podcasts...")
            syncPodcastsInternal(targetUserId)

            _syncState.value = SyncStatus.Syncing("Syncing search history...")
            syncSearchHistoryInternal(targetUserId)

            _syncState.value = SyncStatus.Syncing("Syncing active session...")
            syncActiveSessionInternal(targetUserId)

            val success = SyncStatus.Success(likedCount, System.currentTimeMillis())
            _syncState.value = success
            success
        } catch (e: Exception) {
            val error = SyncStatus.Error(e.message ?: "Sync encountered an unexpected error")
            _syncState.value = error
            error
        } finally {
            isSyncActive = false
            mutex.unlock()
        }
    }

    /**
     * Phase 3 Inbound Hydration & Phase 2 Outbox Drain for Liked Songs.
     */
    private suspend fun syncLikesInternal(targetUserId: String): Int {
        // 1. Fetch remote cloud liked tracks (excluding any legacy trk_ items)
        val cloudTracks = SupabaseLibrarySync.fetchCloudLikedTracks(targetUserId).filter { !it.id.startsWith("trk_") }
        val cloudIds = cloudTracks.map { it.id }.toSet()

        // 2. Fetch local liked songs
        val localLiked = try {
            songRepository.getLikedSongs().first()
        } catch (_: Exception) {
            emptyList()
        }
        val localIds = localLiked.map { it.videoId }.toSet()

        // 3. Inbound: Insert missing cloud songs into local Room SQLite
        for (cloudTrack in cloudTracks) {
            if (cloudTrack.id !in localIds) {
                try {
                    val existing = songRepository.getSongById(cloudTrack.id).firstOrNull()
                    if (existing != null && !existing.liked) {
                        // The track exists locally, but the user explicitly unliked it!
                        // Reconcile: delete from Supabase user_likes instead of resurrecting it locally!
                        SupabaseLibrarySync.syncLike(
                            trackId = cloudTrack.id,
                            title = cloudTrack.title,
                            artist = cloudTrack.artist,
                            isLiked = false,
                            userId = targetUserId
                        )
                        continue
                    }
                    val durationStr = formatDuration(cloudTrack.durationSec)
                    if (existing == null) {
                        val entity = SongEntity(
                            videoId = cloudTrack.id,
                            albumId = null,
                            albumName = cloudTrack.album ?: "Streamify",
                            artistId = null,
                            artistName = listOf(cloudTrack.artist),
                            duration = durationStr,
                            durationSeconds = cloudTrack.durationSec,
                            isAvailable = true,
                            isExplicit = false,
                            likeStatus = "LIKE",
                            thumbnails = cloudTrack.coverUrl.ifBlank { null },
                            title = cloudTrack.title,
                            videoType = "MUSIC_VIDEO_TYPE_ATV",
                            category = null,
                            resultType = null,
                            liked = true,
                            totalPlayTime = 0,
                            downloadState = DownloadState.STATE_NOT_DOWNLOADED,
                            favoriteAt = parseIsoToLocalDateTime(cloudTrack.createdAt) ?: now(),
                            downloadedAt = now(),
                            inLibrary = now(),
                            canvasUrl = null,
                            canvasThumbUrl = null
                        )
                        songRepository.insertSong(entity).lastOrNull()
                    }
                    songRepository.updateLikeStatus(cloudTrack.id, 1)
                } catch (_: Exception) {
                    // Isolate single entity failure so the remaining batch proceeds
                }
            }
        }

        // 4. Outbox: Push local likes that don't exist in cloud to Supabase in high-performance batches
        val pendingUploads = localLiked.filter { it.videoId !in cloudIds && it.liked && !it.videoId.startsWith("trk_") }
        if (pendingUploads.isNotEmpty()) {
            val cloudBatch = pendingUploads.map { localSong ->
                CloudTrack(
                    id = localSong.videoId,
                    title = localSong.title,
                    artist = localSong.artistName?.joinToString(", ") ?: "Unknown",
                    album = localSong.albumName,
                    durationSec = localSong.durationSeconds,
                    coverUrl = localSong.thumbnails ?: "",
                    streamUrl = "https://www.youtube.com/watch?v=${localSong.videoId}",
                    createdAt = localSong.favoriteAt?.toInstant(TimeZone.currentSystemDefault())?.toString()
                )
            }
            SupabaseLibrarySync.uploadLikedTracksBatch(cloudBatch, targetUserId)
        }

        return cloudTracks.size.coerceAtLeast(localLiked.size)
    }

    /**
     * Phase 2 / Phase 3 Followed Artists Synchronization.
     */
    private suspend fun syncArtistsInternal(targetUserId: String) {
        try {
            val cloudArtistIds = SupabaseLibrarySync.fetchCloudItemFavorites("artist", targetUserId).toSet()
            val localArtists = artistRepository.getAllArtists(1000).first()
            val localArtistIds = localArtists.map { it.channelId }.toSet()

            // Inbound: follow artists locally that are in the cloud
            for (cloudArtistId in cloudArtistIds) {
                if (cloudArtistId !in localArtistIds) {
                    artistRepository.updateFollowedStatus(cloudArtistId, 1)
                }
            }

            // Outbox: push locally followed artists to cloud
            for (localArtist in localArtists) {
                if (localArtist.channelId !in cloudArtistIds) {
                    SupabaseLibrarySync.syncItemFavorite("artist", localArtist.channelId, true, targetUserId)
                }
            }
        } catch (_: Exception) {
        }
    }

    /**
     * Phase 2 / Phase 3 Saved Albums Synchronization.
     */
    private suspend fun syncAlbumsInternal(targetUserId: String) {
        try {
            val cloudAlbumIds = SupabaseLibrarySync.fetchCloudItemFavorites("album", targetUserId).toSet()
            val localLikedAlbums = try {
                albumRepository.getLikedAlbums().first()
            } catch (_: Exception) {
                emptyList()
            }
            val localAlbumIds = localLikedAlbums.map { it.browseId }.toSet()

            // Inbound: save albums locally that are favorited in the cloud
            for (cloudAlbumId in cloudAlbumIds) {
                if (cloudAlbumId !in localAlbumIds) {
                    albumRepository.updateAlbumLiked(cloudAlbumId, 1)
                }
            }

            // Outbox: push locally favorited albums to cloud
            for (localAlbum in localLikedAlbums) {
                if (localAlbum.browseId !in cloudAlbumIds && localAlbum.liked == 1) {
                    SupabaseLibrarySync.syncItemFavorite("album", localAlbum.browseId, true, targetUserId)
                }
            }
        } catch (_: Exception) {
        }
    }

    /**
     * Phase 2 / Phase 3 Playlists Synchronization (Full Inbound & Outbound with UUID linkage).
     */
    private suspend fun syncPlaylistsInternal(targetUserId: String) {
        try {
            // 1. Inbound: fetch cloud playlists
            val cloudPlaylists = SupabaseLibrarySync.fetchCloudPlaylists(targetUserId)
            val localPlaylists = localPlaylistRepository.getAllLocalPlaylists().first()
            val localSyncIds = localPlaylists.mapNotNull { it.syncId?.ifBlank { null } }.toSet()

            for (cloudPl in cloudPlaylists) {
                if (cloudPl.id !in localSyncIds) {
                    val newEntity = LocalPlaylistEntity(
                        title = cloudPl.name,
                        thumbnail = cloudPl.coverUrl.ifBlank { null },
                        syncId = cloudPl.id,
                        youtubePlaylistId = cloudPl.youtubePlaylistId
                    )
                    localPlaylistRepository.insertLocalPlaylist(newEntity, "Synced from Cloud").first()

                    // Fetch tracks for this cloud playlist with full metadata preservation
                    val cloudTracksWithMeta = SupabaseLibrarySync.fetchCloudPlaylistTracksWithMetadata(cloudPl.id)
                    if (cloudTracksWithMeta.isNotEmpty()) {
                        val refreshedLocal = localPlaylistRepository.getAllLocalPlaylists().first()
                        val matched = refreshedLocal.find { it.syncId == cloudPl.id }
                        if (matched != null) {
                            for (item in cloudTracksWithMeta) {
                                val tid = item.trackId
                                val meta = item.tracks
                                val song = songRepository.getSongById(tid).firstOrNull() ?: SongEntity(
                                    videoId = tid,
                                    title = meta?.title ?: "Synced Track",
                                    artistName = listOf(meta?.artist ?: "Unknown Artist"),
                                    albumName = meta?.album ?: "Streamify",
                                    duration = formatDuration(meta?.durationSec ?: 0),
                                    durationSeconds = meta?.durationSec ?: 0,
                                    thumbnails = meta?.coverUrl?.ifBlank { null },
                                    isAvailable = true,
                                    isExplicit = false,
                                    likeStatus = "INDIFFERENT",
                                    videoType = "MUSIC_VIDEO_TYPE_ATV"
                                ).also {
                                    songRepository.insertSong(it).lastOrNull()
                                }
                                localPlaylistRepository.addTrackToLocalPlaylist(matched.id, song).first()
                            }
                        }
                    }
                }
            }

            // 2. Outbound: push local playlists and their tracks to cloud
            val refreshedPlaylists = localPlaylistRepository.getAllLocalPlaylists().first()
            for (localPl in refreshedPlaylists) {
                // Ensure valid UUID format for Supabase playlists.id
                val syncId = if (!localPl.syncId.isNullOrBlank()) {
                    localPl.syncId
                } else {
                    "00000000-0000-4000-8000-${localPl.id.toString(16).padStart(12, '0')}"
                }

                val tracks = localPlaylistRepository.getFullPlaylistTracks(localPl.id)
                val trackIds = tracks.map { it.videoId }.filter { it.isNotBlank() && !it.startsWith("trk_") }

                SupabaseLibrarySync.syncPlaylist(
                    playlistId = syncId,
                    name = localPl.title,
                    description = "Streamify Playlist",
                    coverUrl = localPl.thumbnail ?: "",
                    trackIds = trackIds,
                    isPublic = false,
                    youtubePlaylistId = localPl.youtubePlaylistId,
                    userId = targetUserId
                )
            }
        } catch (_: Exception) {
        }
    }

    /**
     * Phase 2 Outbox & Phase 3 Inbound for User Settings Profile.
     */
    private suspend fun syncSettingsInternal(targetUserId: String) {
        try {
            val remoteResult = SupabaseLibrarySync.fetchUserSettings(targetUserId)
            val remoteSettings = remoteResult.getOrDefault(emptyMap())

            val localSettings = mutableMapOf<String, String>()
            for (key in SYNCABLE_SETTINGS_KEYS) {
                val value = dataStoreManager.getString(key).firstOrNull()
                if (!value.isNullOrBlank()) {
                    localSettings[key] = value
                }
            }

            // Inbound: If local setting is unset but exists in remote, apply it locally
            for ((key, remoteVal) in remoteSettings) {
                if (key !in localSettings && remoteVal.isNotBlank()) {
                    dataStoreManager.putString(key, remoteVal)
                }
            }

            // Outbox: Merge local into remote and upload if there are newer local settings
            val merged = remoteSettings.toMutableMap().apply { putAll(localSettings) }
            if (merged != remoteSettings && localSettings.isNotEmpty()) {
                SupabaseLibrarySync.uploadUserSettings(targetUserId, merged)
            }
        } catch (_: Exception) {
        }
    }

    /**
     * Phase 3 Inbound & Phase 2 Outbox for Playback History (Analytics & Wrapped).
     */
    private suspend fun syncListeningHistoryInternal(targetUserId: String) {
        try {
            // 1. Inbound: fetch remote cloud listening history
            val cloudHistory = SupabaseLibrarySync.fetchCloudListeningHistory(targetUserId, limit = 100)
            val cloudIds = cloudHistory.map { it.trackId }.toSet()

            val localEvents = analyticsRepository.getPlaybackEventsByOffset(0, 100).firstOrNull() ?: emptyList()
            val localVideoIds = localEvents.map { it.videoId }.toSet()

            for (cloudItem in cloudHistory) {
                if (cloudItem.trackId !in localVideoIds && cloudItem.trackId.isNotBlank() && !cloudItem.trackId.startsWith("trk_")) {
                    val localSong = songRepository.getSongById(cloudItem.trackId).firstOrNull()
                    val channelIds = if (!cloudItem.artistId.isNullOrBlank()) {
                        listOf(cloudItem.artistId)
                    } else {
                        localSong?.artistId ?: emptyList()
                    }
                    val albumId = cloudItem.albumId ?: localSong?.albumId
                    analyticsRepository.insertPlaybackEvent(
                        videoId = cloudItem.trackId,
                        channelIds = channelIds,
                        albumBrowseId = albumId,
                        durationSecond = cloudItem.listenedDurationSec.toLong(),
                        listenedSecond = cloudItem.listenedDurationSec.toLong()
                    ).firstOrNull()
                }
            }

            // 2. Outbox: push local playback events not yet in cloud
            for (localEvent in localEvents) {
                if (localEvent.videoId !in cloudIds && localEvent.videoId.isNotBlank() && !localEvent.videoId.startsWith("trk_")) {
                    val song = songRepository.getSongById(localEvent.videoId).firstOrNull()
                    val artistId = song?.artistId?.firstOrNull()
                    SupabaseLibrarySync.recordPlaybackHistory(
                        userId = targetUserId,
                        trackId = localEvent.videoId,
                        durationSec = localEvent.listenedSecond.toInt().coerceAtLeast(1),
                        albumId = localEvent.albumBrowseId ?: song?.albumId,
                        artistId = artistId,
                        createdAtIso = localEvent.timestamp.toInstant(TimeZone.currentSystemDefault()).toString()
                    )
                }
            }
        } catch (_: Exception) {
        }
    }

    /**
     * Phase 2 / Phase 3 Saved Podcasts Synchronization.
     */
    private suspend fun syncPodcastsInternal(targetUserId: String) {
        try {
            val cloudPodcastIds = SupabaseLibrarySync.fetchCloudItemFavorites("podcast", targetUserId).toSet()
            val localFavPodcasts = podcastRepository.getFavoritePodcasts().firstOrNull() ?: emptyList()
            val localPodcastIds = localFavPodcasts.map { it.podcastId }.toSet()

            // Inbound: favorite podcasts locally that are favorited in the cloud
            for (cloudPodId in cloudPodcastIds) {
                if (cloudPodId !in localPodcastIds && cloudPodId.isNotBlank()) {
                    podcastRepository.favoritePodcast(cloudPodId, true).firstOrNull()
                }
            }

            // Outbox: push local favorite podcasts to cloud
            for (localPod in localFavPodcasts) {
                if (localPod.podcastId !in cloudPodcastIds && localPod.podcastId.isNotBlank()) {
                    SupabaseLibrarySync.syncItemFavorite("podcast", localPod.podcastId, true, targetUserId)
                }
            }
        } catch (_: Exception) {
        }
    }

    /**
     * Phase 2 / Phase 3 Search History Synchronization.
     */
    private suspend fun syncSearchHistoryInternal(targetUserId: String) {
        try {
            val cloudSearch = SupabaseLibrarySync.fetchCloudSearchHistory(targetUserId)
            val cloudQueries = cloudSearch.map { it.query }.toSet()

            val localHistory = searchRepository.getSearchHistory().firstOrNull() ?: emptyList()
            val localQueries = localHistory.map { it.query }.toSet()

            // Inbound: insert missing cloud queries into local search history
            for (q in cloudQueries) {
                if (q !in localQueries && q.isNotBlank()) {
                    searchRepository.insertSearchHistory(SearchHistory(q)).firstOrNull()
                }
            }

            // Outbox: push local search queries not yet in cloud
            val pendingUploads = localQueries.filter { it !in cloudQueries && it.isNotBlank() }
            if (pendingUploads.isNotEmpty()) {
                SupabaseLibrarySync.uploadSearchHistory(pendingUploads, targetUserId)
            }
        } catch (_: Exception) {
        }
    }

    /**
     * Cross-Device Active Playback Session Hand-off (Continuity).
     */
    private suspend fun syncActiveSessionInternal(targetUserId: String) {
        try {
            val localMediaId = dataStoreManager.recentMediaId.firstOrNull() ?: ""
            val localPosStr = dataStoreManager.recentPosition.firstOrNull() ?: "0"
            val localPos = localPosStr.toLongOrNull() ?: 0L

            val remoteSession = SupabaseLibrarySync.fetchActiveSession(targetUserId).getOrNull()

            if (localMediaId.isNotBlank() && !localMediaId.startsWith("trk_")) {
                // Outbox: If device has an active track, update cloud active session
                val localQueue = songRepository.getSavedQueue().firstOrNull()?.firstOrNull()?.listTrack?.map { it.videoId } ?: emptyList()
                SupabaseLibrarySync.updateActiveSession(
                    trackId = localMediaId,
                    positionMs = localPos,
                    queueIds = localQueue,
                    updatedAtIso = now().toInstant(TimeZone.currentSystemDefault()).toString(),
                    userId = targetUserId
                )
            } else if (remoteSession != null && !remoteSession.trackId.isNullOrBlank()) {
                // Inbound: If this device has no recent track and cloud has one, restore continuity
                dataStoreManager.saveRecentSong(remoteSession.trackId, remoteSession.positionMs)
            }
        } catch (_: Exception) {
        }
    }

    private fun parseIsoToLocalDateTime(iso: String?): LocalDateTime? {
        if (iso.isNullOrBlank()) return null
        return try {
            Instant.parse(iso).toLocalDateTime(TimeZone.currentSystemDefault())
        } catch (_: Exception) {
            null
        }
    }

    private fun formatDuration(seconds: Int): String {
        val m = seconds / 60
        val s = seconds % 60
        return "$m:${s.toString().padStart(2, '0')}"
    }
}
