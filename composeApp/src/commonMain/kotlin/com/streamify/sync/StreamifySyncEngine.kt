package com.streamify.sync

import com.maxrave.common.Config
import com.maxrave.simpmusic.BuildKonfig
import com.maxrave.domain.data.entities.DownloadState
import com.maxrave.domain.data.entities.LocalPlaylistEntity
import com.maxrave.domain.data.entities.PlaylistEntity
import com.maxrave.domain.data.entities.SearchHistory
import com.maxrave.domain.data.entities.SongEntity
import com.maxrave.domain.data.entities.sync.SyncChangeEntity
import com.maxrave.domain.extension.now
import com.maxrave.domain.repository.AlbumRepository
import com.maxrave.domain.repository.ArtistRepository
import com.maxrave.domain.repository.LocalPlaylistRepository
import com.maxrave.domain.repository.PlaylistRepository
import com.maxrave.domain.repository.PodcastRepository
import com.maxrave.domain.repository.SearchRepository
import com.maxrave.domain.repository.SongRepository
import com.streamify.supabase.CloudEpisodeState
import com.streamify.supabase.CloudLibraryTrack
import com.streamify.supabase.CloudPresence
import com.streamify.supabase.CloudTrack
import com.streamify.supabase.SupabaseClient
import com.streamify.supabase.SupabaseConfig
import com.streamify.supabase.SupabaseLibrarySync
import com.streamify.supabase.SyncPullOp
import com.streamify.supabase.SyncPullResult
import com.streamify.supabase.SyncPushResult
import com.streamify.supabase.resolvePlatformDeviceInfo
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
import kotlin.time.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
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
    private val playlistRepository: PlaylistRepository by inject()
    private val dataStoreManager: com.maxrave.domain.manager.DataStoreManager by inject()
    private val analyticsRepository: com.maxrave.domain.repository.AnalyticsRepository by inject()
    private val searchRepository: SearchRepository by inject()
    private val podcastRepository: PodcastRepository by inject()
    private val syncOutboxRepository: SyncOutboxRepository by inject()

    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private const val TELEMETRY_INTERVAL_MS = 3_600_000L // 1 hour keep-alive floor

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

    data class LocalEpisodeState(
        val episodeId: String,
        val podcastId: String = "",
        val positionSec: Int = 0,
        val completed: Boolean = false,
        val modifiedHlc: Long = 0L
    )

    private val json = Json { ignoreUnknownKeys = true }

    @Volatile
    var isCurrentPlaybackActive: Boolean = false
        private set

    fun setPlaybackActive(active: Boolean) {
        isCurrentPlaybackActive = active
    }

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

    @Volatile
    private var backoffUntilMs: Long = 0L

    @Volatile
    private var consecutiveFailures: Int = 0

    private val backoffPolicy = com.maxrave.domain.utils.RetryPolicy(
        maxAttempts = 5,
        initialDelayMs = 1_000L,
        maxDelayMs = 60_000L,
        backoffMultiplier = 2.0,
        fullJitter = true,
    )

    private fun SupervisorJob() = kotlinx.coroutines.SupervisorJob()

    /**
     * Executes the complete 3-phase hybrid synchronization pipeline asynchronously.
     */
    fun triggerSync(userId: String? = null) {
        scope.launch {
            syncAll(userId)
        }
    }

    private fun isGuestUserId(userId: String?): Boolean {
        if (userId.isNullOrBlank()) return true
        return userId == "guest" || userId.startsWith("guest")
    }

    /**
     * Triggers asynchronous device and version telemetry sync.
     */
    fun triggerDeviceTelemetry(userId: String? = null) {
        scope.launch {
            val targetUserId = userId ?: SupabaseClient.currentUser.value?.id ?: SupabaseConfig.DEFAULT_USER.id
            if (!isGuestUserId(targetUserId)) {
                val deviceId = getOrCreateDeviceId()
                syncDeviceTelemetryInternal(targetUserId, deviceId)
            }
        }
    }

    private suspend fun getOrCreateDeviceId(): String {
        val existing = dataStoreManager.getString("streamify_device_id_v3").firstOrNull()
        if (!existing.isNullOrBlank()) {
            return existing
        }
        val newId = "dev_" + HybridLogicalClock.randomUuid()
        dataStoreManager.putString("streamify_device_id_v3", newId)
        return newId
    }

    /**
     * Suspending orchestrator that executes all sync stages with V3 delta sync & HLC.
     */
    suspend fun syncAll(userId: String? = null): SyncStatus = withContext(Dispatchers.IO) {
        val now = Clock.System.now().toEpochMilliseconds()
        if (now < backoffUntilMs) {
            return@withContext _syncState.value
        }
        if (!mutex.tryLock()) {
            return@withContext _syncState.value
        }
        try {
            val targetUserId = userId ?: SupabaseClient.currentUser.value?.id ?: SupabaseConfig.DEFAULT_USER.id
            if (isGuestUserId(targetUserId)) {
                _syncState.value = SyncStatus.Idle
                return@withContext SyncStatus.Idle
            }

            isSyncActive = true
            val deviceId = getOrCreateDeviceId()
            val hlc = HybridLogicalClock.now()
            val localCursor = dataStoreManager.getString("streamify_sync_cursor_v3").firstOrNull()?.toLongOrNull() ?: 0L

            var likedCount = 0

            if (localCursor > 0L) {
                // V3 Delta Sync path (cursor-based delta pull)
                _syncState.value = SyncStatus.Syncing("Pulling delta changes...")
                syncDeltaInternal(targetUserId, deviceId)

                // Drain local outbox via sync_push
                _syncState.value = SyncStatus.Syncing("Pushing changes...")
                syncOutboxPushInternal(targetUserId, deviceId, hlc)

                likedCount = songRepository.getLikedSongs().firstOrNull()?.size ?: 0
            } else {
                // Cold Bootstrap Sync with Resumable Stage Checkpointing (Step 8)
                val resumeStage = dataStoreManager.getString("streamify_bootstrap_checkpoint_v3").firstOrNull() ?: ""
                val stages = listOf(
                    "LIKES",
                    "ARTISTS",
                    "ALBUMS",
                    "LIBRARY_TRACKS",
                    "SAVED_PLAYLISTS",
                    "PLAYLISTS",
                    "SETTINGS",
                    "HISTORY",
                    "PODCASTS",
                    "EPISODES",
                    "SEARCH"
                )
                val resumeIdx = stages.indexOf(resumeStage).let { if (it >= 0) it + 1 else 0 }

                for (idx in resumeIdx until stages.size) {
                    val stage = stages[idx]
                    when (stage) {
                        "LIKES" -> {
                            _syncState.value = SyncStatus.Syncing("Bootstrapping liked songs...")
                            likedCount = syncLikesInternal(targetUserId)
                        }
                        "ARTISTS" -> {
                            _syncState.value = SyncStatus.Syncing("Bootstrapping followed artists...")
                            syncArtistsInternal(targetUserId)
                        }
                        "ALBUMS" -> {
                            _syncState.value = SyncStatus.Syncing("Bootstrapping saved albums...")
                            syncAlbumsInternal(targetUserId)
                        }
                        "LIBRARY_TRACKS" -> {
                            _syncState.value = SyncStatus.Syncing("Bootstrapping library tracks...")
                            syncLibraryTracksInternal(targetUserId)
                        }
                        "SAVED_PLAYLISTS" -> {
                            _syncState.value = SyncStatus.Syncing("Bootstrapping saved playlists...")
                            syncSavedPlaylistsInternal(targetUserId)
                        }
                        "PLAYLISTS" -> {
                            _syncState.value = SyncStatus.Syncing("Bootstrapping playlists...")
                            syncPlaylistsInternal(targetUserId)
                        }
                        "SETTINGS" -> {
                            _syncState.value = SyncStatus.Syncing("Bootstrapping settings...")
                            syncSettingsInternal(targetUserId)
                        }
                        "HISTORY" -> {
                            _syncState.value = SyncStatus.Syncing("Bootstrapping listening history...")
                            syncListeningHistoryInternal(targetUserId)
                        }
                        "PODCASTS" -> {
                            _syncState.value = SyncStatus.Syncing("Bootstrapping podcasts...")
                            syncPodcastsInternal(targetUserId)
                        }
                        "EPISODES" -> {
                            _syncState.value = SyncStatus.Syncing("Bootstrapping podcast episode progress...")
                            syncPodcastEpisodeStateInternal(targetUserId)
                        }
                        "SEARCH" -> {
                            _syncState.value = SyncStatus.Syncing("Bootstrapping search history...")
                            syncSearchHistoryInternal(targetUserId)
                        }
                    }
                    dataStoreManager.putString("streamify_bootstrap_checkpoint_v3", stage)
                }

                if (likedCount == 0) {
                    likedCount = songRepository.getLikedSongs().firstOrNull()?.size ?: 0
                }

                // Record latest changelog cursor upon bootstrap completion
                val pullRes = SupabaseLibrarySync.syncPull(since = 0L, limit = 1, deviceId = deviceId).getOrNull()
                val latestCursor = pullRes?.cursor ?: 0L
                dataStoreManager.putString("streamify_sync_cursor_v3", latestCursor.toString())
                dataStoreManager.putString("streamify_bootstrap_checkpoint_v3", "DONE")
            }

            // Sync presence via heartbeat_presence with HLC guard
            _syncState.value = SyncStatus.Syncing("Syncing playback presence...")
            syncPresenceInternal(targetUserId, deviceId, hlc)

            // V4.1 Fleet Telemetry: Sync device and version telemetry
            syncDeviceTelemetryInternal(targetUserId, deviceId)

            consecutiveFailures = 0
            backoffUntilMs = 0L
            val success = SyncStatus.Success(likedCount, System.currentTimeMillis())
            _syncState.value = success
            success
        } catch (e: Exception) {
            consecutiveFailures++
            val delayMs = backoffPolicy.computeDelayMs(consecutiveFailures.coerceAtMost(5))
            backoffUntilMs = Clock.System.now().toEpochMilliseconds() + delayMs
            val error = SyncStatus.Error("${e.message ?: "Sync encountered an unexpected error"} (backing off ${delayMs / 1000}s)")
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
            val localArtists = artistRepository.getFollowedArtists().firstOrNull() ?: emptyList()
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
                if (localAlbum.browseId !in cloudAlbumIds && localAlbum.liked) {
                    SupabaseLibrarySync.syncItemFavorite("album", localAlbum.browseId, true, targetUserId)
                }
            }
        } catch (_: Exception) {
        }
    }

    /**
     * Phase 2 / Phase 3 Library Tracks Synchronization (in-library state independent of likes).
     */
    private suspend fun syncLibraryTracksInternal(targetUserId: String) {
        try {
            val cloudLibrary = SupabaseLibrarySync.fetchCloudLibraryTracks(targetUserId)
            val cloudTrackIds = cloudLibrary.map { it.trackId }.toSet()

            val localSongs = songRepository.getAllSongs(Int.MAX_VALUE).firstOrNull() ?: emptyList()
            val localInLibrary = localSongs.filter { it.inLibrary > Config.REMOVED_SONG_DATE_TIME }
            val localLibraryIds = localInLibrary.map { it.videoId }.toSet()

            // Inbound: hydrate cloud library tracks locally
            for (cloudItem in cloudLibrary) {
                if (cloudItem.trackId !in localLibraryIds && cloudItem.trackId.isNotBlank() && !cloudItem.trackId.startsWith("trk_")) {
                    val existing = songRepository.getSongById(cloudItem.trackId).firstOrNull()
                    val addedAtTime = parseIsoToLocalDateTime(cloudItem.addedAt) ?: now()
                    if (existing == null) {
                        val entity = SongEntity(
                            videoId = cloudItem.trackId,
                            title = "Synced Track",
                            albumName = "Streamify",
                            duration = "0:00",
                            durationSeconds = 0,
                            isAvailable = true,
                            isExplicit = false,
                            likeStatus = "INDIFFERENT",
                            videoType = "MUSIC_VIDEO_TYPE_ATV",
                            category = null,
                            resultType = null,
                            inLibrary = addedAtTime
                        )
                        songRepository.insertSong(entity).lastOrNull()
                    } else {
                        songRepository.updateSongInLibrary(addedAtTime, cloudItem.trackId).firstOrNull()
                    }
                }
            }

            // Outbox: push local library tracks not yet in cloud
            val pendingUploads = localInLibrary.filter { it.videoId !in cloudTrackIds && !it.videoId.startsWith("trk_") }
            if (pendingUploads.isNotEmpty()) {
                SupabaseLibrarySync.uploadLibraryTracksBatch(pendingUploads.map { it.videoId }, targetUserId)
            }
        } catch (_: Exception) {
        }
    }

    /**
     * Phase 2 / Phase 3 Saved YouTube Playlists Synchronization.
     */
    private suspend fun syncSavedPlaylistsInternal(targetUserId: String) {
        try {
            val cloudFavorites = SupabaseLibrarySync.fetchCloudItemFavoritesWithMetadata("playlist", targetUserId)
            val cloudPlaylistIds = cloudFavorites.map { it.itemId }.toSet()

            val localPlaylists = playlistRepository.getLikedPlaylists().firstOrNull() ?: emptyList()
            val localPlaylistIds = localPlaylists.map { it.id }.toSet()

            // Inbound: save playlists locally that are favorited in the cloud
            for (cloudFav in cloudFavorites) {
                if (cloudFav.itemId !in localPlaylistIds && cloudFav.itemId.isNotBlank()) {
                    val existing = playlistRepository.getPlaylist(cloudFav.itemId).firstOrNull()
                    if (existing == null) {
                        val title = cloudFav.metadata?.get("title") ?: "Saved Playlist"
                        val thumb = cloudFav.metadata?.get("thumbnail") ?: ""
                        val count = cloudFav.metadata?.get("track_count")?.toIntOrNull() ?: 0
                        val entity = PlaylistEntity(
                            id = cloudFav.itemId,
                            title = title,
                            thumbnails = thumb,
                            trackCount = count,
                            liked = true,
                            favoriteAt = parseIsoToLocalDateTime(cloudFav.createdAt) ?: now()
                        )
                        playlistRepository.insertPlaylist(entity)
                    } else {
                        playlistRepository.updatePlaylistLiked(cloudFav.itemId, 1)
                    }
                }
            }

            // Outbox: push locally favorited playlists to cloud
            for (localPl in localPlaylists) {
                if (localPl.id !in cloudPlaylistIds && localPl.liked && localPl.id.isNotBlank()) {
                    SupabaseLibrarySync.syncItemFavorite(
                        itemType = "playlist",
                        itemId = localPl.id,
                        isFavorited = true,
                        userId = targetUserId,
                        metadata = mapOf(
                            "title" to localPl.title,
                            "thumbnail" to localPl.thumbnails,
                            "track_count" to localPl.trackCount.toString()
                        )
                    )
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
                                    videoType = "MUSIC_VIDEO_TYPE_ATV",
                                    category = null,
                                    resultType = null
                                ).also {
                                    songRepository.insertSong(it).lastOrNull()
                                }
                                localPlaylistRepository.addTrackToLocalPlaylist(
                                    id = matched.id,
                                    song = song,
                                    successMessage = "Added track",
                                    updatedYtMessage = "",
                                    errorMessage = "Failed to add track"
                                ).first()
                            }
                        }
                    }
                }
            }

            // 2. Outbound: push local playlists and their tracks to cloud
            val refreshedPlaylists = localPlaylistRepository.getAllLocalPlaylists().first()
            for (localPl in refreshedPlaylists) {
                // Ensure valid UUID format for Supabase playlists.id - migrate away from deterministic hex collision IDs
                val currentSyncId = localPl.syncId
                val syncId: String = if (currentSyncId.isNullOrBlank() || currentSyncId.startsWith("00000000-0000-4000-8000-")) {
                    HybridLogicalClock.randomUuid()
                } else {
                    currentSyncId
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
     * High fidelity: uses client_event_id deduplication, batch uploads, and unconstrained paginated drains.
     */
    private suspend fun syncListeningHistoryInternal(targetUserId: String) {
        try {
            // 1. Inbound: fetch remote cloud listening history with client_event_id fidelity
            val cloudHistory = SupabaseLibrarySync.fetchCloudListeningHistory(targetUserId, limit = 1000)
            val cloudClientEventIds = cloudHistory.mapNotNull { it.clientEventId?.ifBlank { null } ?: it.id.ifBlank { null } }.toSet()

            // Fetch local playback events with unconstrained pagination drain
            val localEvents = mutableListOf<com.maxrave.domain.data.entities.analytics.PlaybackEventEntity>()
            var offset = 0
            val pageSize = 200
            while (true) {
                val page = analyticsRepository.getPlaybackEventsByOffset(offset, pageSize).firstOrNull() ?: emptyList()
                if (page.isEmpty()) break
                localEvents.addAll(page)
                if (page.size < pageSize) break
                offset += pageSize
            }

            val localSyncIds = localEvents.mapNotNull { it.syncId?.ifBlank { null } }.toSet()

            // Inbound: insert missing cloud history events into local Room SQLite
            for (cloudItem in cloudHistory) {
                val cloudEventId = cloudItem.clientEventId?.ifBlank { null } ?: cloudItem.id
                if (cloudEventId !in localSyncIds && cloudItem.trackId.isNotBlank() && !cloudItem.trackId.startsWith("trk_")) {
                    val localSong = songRepository.getSongById(cloudItem.trackId).firstOrNull()
                    val remoteArtistId = cloudItem.artistId
                    val channelIds: List<String> = if (!remoteArtistId.isNullOrBlank()) {
                        listOf(remoteArtistId)
                    } else {
                        localSong?.artistId?.filterNotNull() ?: emptyList()
                    }
                    val albumId = cloudItem.albumId ?: localSong?.albumId
                    analyticsRepository.insertPlaybackEvent(
                        videoId = cloudItem.trackId,
                        channelIds = channelIds,
                        albumBrowseId = albumId,
                        durationSecond = cloudItem.listenedDurationSec.toLong(),
                        listenedSecond = cloudItem.listenedDurationSec.toLong(),
                        syncId = cloudEventId,
                        recordSync = false,
                    ).firstOrNull()
                }
            }

            // 2. Outbound: push missing plays to cloud in batches with client_event_id
            val pendingBatch = mutableListOf<com.streamify.supabase.PlaybackHistoryBatchItem>()
            for (localEvent in localEvents) {
                if (localEvent.videoId.isBlank() || localEvent.videoId.startsWith("trk_")) continue

                val effectiveSyncId = localEvent.syncId?.ifBlank { null }
                    ?: HybridLogicalClock.randomUuid()

                if (effectiveSyncId !in cloudClientEventIds) {
                    val song = songRepository.getSongById(localEvent.videoId).firstOrNull()
                    val artistId = song?.artistId?.filterNotNull()?.firstOrNull()
                    val createdAtIso = try {
                        localEvent.timestamp.toInstant(TimeZone.currentSystemDefault()).toString()
                    } catch (_: Exception) {
                        null
                    }
                    pendingBatch.add(
                        com.streamify.supabase.PlaybackHistoryBatchItem(
                            clientEventId = effectiveSyncId,
                            trackId = localEvent.videoId,
                            durationSec = localEvent.listenedSecond.toInt().coerceAtLeast(1),
                            albumId = localEvent.albumBrowseId ?: song?.albumId,
                            artistId = artistId,
                            createdAtIso = createdAtIso
                        )
                    )
                }
            }

            if (pendingBatch.isNotEmpty()) {
                SupabaseLibrarySync.recordPlaybackHistoryBatch(targetUserId, pendingBatch)
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
     * Phase 2 / Phase 3 Podcast Episode State Synchronization.
     */
    private suspend fun syncPodcastEpisodeStateInternal(targetUserId: String) {
        try {
            val cloudStates = SupabaseLibrarySync.fetchCloudEpisodeStates(targetUserId)
            val localStates = getLocalEpisodeStates().toMutableMap()
            var stateChanged = false

            // Inbound: reconcile cloud states using HLC-LWW
            for (cloud in cloudStates) {
                val local = localStates[cloud.episodeId]
                val cloudHlc = cloud.modifiedHlc ?: 0L
                if (local == null || cloudHlc > local.modifiedHlc) {
                    localStates[cloud.episodeId] = LocalEpisodeState(
                        episodeId = cloud.episodeId,
                        podcastId = cloud.podcastId,
                        positionSec = cloud.positionSec,
                        completed = cloud.completed,
                        modifiedHlc = cloudHlc
                    )
                    stateChanged = true
                }
            }

            if (stateChanged) {
                saveLocalEpisodeStates(localStates)
            }

            // Outbox: push local episode states that are newer than cloud
            val cloudMap = cloudStates.associateBy { it.episodeId }
            val pendingUploads = mutableListOf<CloudEpisodeState>()
            for ((epId, local) in localStates) {
                val cloud = cloudMap[epId]
                val cloudHlc = cloud?.modifiedHlc ?: 0L
                if (cloud == null || local.modifiedHlc > cloudHlc) {
                    pendingUploads.add(
                        CloudEpisodeState(
                            episodeId = epId,
                            podcastId = local.podcastId,
                            positionSec = local.positionSec,
                            completed = local.completed,
                            modifiedHlc = local.modifiedHlc
                        )
                    )
                }
            }

            if (pendingUploads.isNotEmpty()) {
                SupabaseLibrarySync.uploadEpisodeStatesBatch(pendingUploads, targetUserId)
            }
        } catch (_: Exception) {
        }
    }

    /**
     * Phase 2 / Phase 3 Search History Synchronization.
     * Defect D8 fix: Insert cloud queries in reverse order (oldest first) so that
     * SQLite rowid sequence reflects chronological searched_at ordering.
     */
    private suspend fun syncSearchHistoryInternal(targetUserId: String) {
        try {
            val cloudSearch = SupabaseLibrarySync.fetchCloudSearchHistory(targetUserId)
            val cloudQueries = cloudSearch.map { it.query }.toSet()

            val localHistory = searchRepository.getSearchHistory().firstOrNull() ?: emptyList()
            val localQueries = localHistory.map { it.query }.toSet()

            // Inbound: insert missing cloud queries in reversed order (oldest first)
            // so that SQLite rowid reflects chronological searched_at order (Defect D8 resolution)
            val toInsert = cloudSearch.filter { it.query !in localQueries && it.query.isNotBlank() }.reversed()
            for (item in toInsert) {
                searchRepository.insertSearchHistory(SearchHistory(item.query)).firstOrNull()
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
     * V3 Delta Sync: pulls changelog delta ops since local cursor, skipping calling device's own ops.
     */
    private suspend fun syncDeltaInternal(targetUserId: String, deviceId: String): Boolean {
        var cursor = dataStoreManager.getString("streamify_sync_cursor_v3").firstOrNull()?.toLongOrNull() ?: 0L
        var hasMore = true
        var pagesFetched = 0

        while (hasMore && pagesFetched < 20) {
            val pullResult = SupabaseLibrarySync.syncPull(
                since = cursor,
                limit = 500,
                deviceId = deviceId
            ).getOrNull() ?: return false

            withContext(withSyncApplying()) {
                for (op in pullResult.ops) {
                    applyDeltaOp(op)
                }
            }

            cursor = pullResult.cursor
            dataStoreManager.putString("streamify_sync_cursor_v3", cursor.toString())
            hasMore = pullResult.hasMore
            pagesFetched++
        }
        return true
    }

    /**
     * Drains pending local universal outbox changes from SyncDao to Supabase via `sync_push` RPC.
     * Maps all entity types (likes, library tracks, follows, album/playlist favorites, local playlist edits/tracks,
     * playback events, settings, episode progress) into idempotent batch operations.
     */
    private data class OutboxOpMapping(
        val op: JsonObject,
        val changeId: String,
    )

    private suspend fun syncOutboxPushInternal(targetUserId: String, deviceId: String, hlc: Long) {
        try {
            // 1. Drain pending outbox changes (up to 500)
            val pendingChanges = syncOutboxRepository.getPending(500)
            if (pendingChanges.isNotEmpty()) {
                val opMappings = mutableListOf<OutboxOpMapping>()
                val unhandledChangeIds = mutableListOf<String>()

                val historyKeys = pendingChanges.filter { it.entity == SyncKind.HISTORY }.map { it.entityKey }
                val historyEvents = if (historyKeys.isNotEmpty()) {
                    syncOutboxRepository.getPlaybackEventsBySyncIds(historyKeys).associateBy { it.syncId }
                } else {
                    emptyMap()
                }

                for (change in pendingChanges) {
                    val changeHlc = change.changedAt
                    when (change.entity) {
                        SyncKind.SONG_LIKE -> {
                            val isLike = change.op == SyncOp.UPSERT || (change.op == SyncOp.SET && change.value == "1")
                            if (!isLike || change.op == SyncOp.DELETE) {
                                opMappings.add(OutboxOpMapping(buildJsonObject {
                                    put("entity", "like")
                                    put("op", "D")
                                    put("key", change.entityKey)
                                    put("hlc", changeHlc)
                                }, change.changeId))
                            } else {
                                val song = songRepository.getSongById(change.entityKey).firstOrNull()
                                val title = song?.title ?: "Track ${change.entityKey}"
                                val artist = song?.artistName?.firstOrNull() ?: "Unknown"
                                val album = song?.albumName ?: "Streamify"
                                val durationSec = song?.durationSeconds ?: 0
                                val thumbUrl = song?.thumbnails ?: ""
                                val createdAt = song?.favoriteAt?.toString() ?: Instant.fromEpochMilliseconds(change.changedAt).toString()
                                opMappings.add(OutboxOpMapping(buildJsonObject {
                                    put("entity", "like")
                                    put("op", "U")
                                    put("key", change.entityKey)
                                    put("hlc", changeHlc)
                                    put("data", buildJsonObject {
                                        put("created_at", createdAt)
                                        put("meta", buildJsonObject {
                                            put("title", title)
                                            put("artist", artist)
                                            put("album", album)
                                            put("duration_sec", durationSec)
                                            put("thumb_url", thumbUrl)
                                        })
                                    })
                                }, change.changeId))
                            }
                        }

                        SyncKind.LIBRARY_TRACK -> {
                            val inLib = change.op != SyncOp.DELETE && change.value != "0"
                            if (!inLib) {
                                opMappings.add(OutboxOpMapping(buildJsonObject {
                                    put("entity", "library_track")
                                    put("op", "D")
                                    put("key", change.entityKey)
                                    put("hlc", changeHlc)
                                }, change.changeId))
                            } else {
                                opMappings.add(OutboxOpMapping(buildJsonObject {
                                    put("entity", "library_track")
                                    put("op", "U")
                                    put("key", change.entityKey)
                                    put("hlc", changeHlc)
                                    put("data", buildJsonObject {
                                        put("added_at", Instant.fromEpochMilliseconds(change.changedAt).toString())
                                    })
                                }, change.changeId))
                            }
                        }

                        SyncKind.ARTIST_FOLLOW -> {
                            val isFollow = change.op != SyncOp.DELETE && change.value != "0"
                            if (!isFollow) {
                                opMappings.add(OutboxOpMapping(buildJsonObject {
                                    put("entity", "favorite")
                                    put("op", "D")
                                    put("key", "artist:${change.entityKey}")
                                    put("hlc", changeHlc)
                                    put("data", buildJsonObject {
                                        put("item_type", "artist")
                                        put("item_id", change.entityKey)
                                    })
                                }, change.changeId))
                            } else {
                                val artist = artistRepository.getArtistById(change.entityKey).firstOrNull()
                                val name = artist?.name ?: "Artist"
                                opMappings.add(OutboxOpMapping(buildJsonObject {
                                    put("entity", "favorite")
                                    put("op", "U")
                                    put("key", "artist:${change.entityKey}")
                                    put("hlc", changeHlc)
                                    put("data", buildJsonObject {
                                        put("item_type", "artist")
                                        put("item_id", change.entityKey)
                                        put("name", name)
                                    })
                                }, change.changeId))
                            }
                        }

                        SyncKind.ALBUM_LIKE -> {
                            val isLiked = change.op != SyncOp.DELETE && change.value != "0"
                            if (!isLiked) {
                                opMappings.add(OutboxOpMapping(buildJsonObject {
                                    put("entity", "favorite")
                                    put("op", "D")
                                    put("key", "album:${change.entityKey}")
                                    put("hlc", changeHlc)
                                    put("data", buildJsonObject {
                                        put("item_type", "album")
                                        put("item_id", change.entityKey)
                                    })
                                }, change.changeId))
                            } else {
                                val album = albumRepository.getAlbum(change.entityKey).firstOrNull()
                                val title = album?.title ?: "Album"
                                opMappings.add(OutboxOpMapping(buildJsonObject {
                                    put("entity", "favorite")
                                    put("op", "U")
                                    put("key", "album:${change.entityKey}")
                                    put("hlc", changeHlc)
                                    put("data", buildJsonObject {
                                        put("item_type", "album")
                                        put("item_id", change.entityKey)
                                        put("title", title)
                                    })
                                }, change.changeId))
                            }
                        }

                        SyncKind.PLAYLIST_LIKE -> {
                            val isLiked = change.op != SyncOp.DELETE && change.value != "0"
                            if (!isLiked) {
                                opMappings.add(OutboxOpMapping(buildJsonObject {
                                    put("entity", "favorite")
                                    put("op", "D")
                                    put("key", "playlist:${change.entityKey}")
                                    put("hlc", changeHlc)
                                    put("data", buildJsonObject {
                                        put("item_type", "playlist")
                                        put("item_id", change.entityKey)
                                    })
                                }, change.changeId))
                            } else {
                                val pl = playlistRepository.getPlaylist(change.entityKey).firstOrNull()
                                val title = pl?.title ?: "Playlist"
                                val thumb = pl?.thumbnails ?: ""
                                val count = (pl?.trackCount ?: 0).toString()
                                opMappings.add(OutboxOpMapping(buildJsonObject {
                                    put("entity", "favorite")
                                    put("op", "U")
                                    put("key", "playlist:${change.entityKey}")
                                    put("hlc", changeHlc)
                                    put("data", buildJsonObject {
                                        put("item_type", "playlist")
                                        put("item_id", change.entityKey)
                                        put("metadata", buildJsonObject {
                                            put("title", title)
                                            put("thumbnail", thumb)
                                            put("track_count", count)
                                        })
                                    })
                                }, change.changeId))
                            }
                        }

                        SyncKind.PODCAST_FAVORITE -> {
                            val isFav = change.op != SyncOp.DELETE && change.value != "0"
                            if (!isFav) {
                                opMappings.add(OutboxOpMapping(buildJsonObject {
                                    put("entity", "favorite")
                                    put("op", "D")
                                    put("key", "podcast:${change.entityKey}")
                                    put("hlc", changeHlc)
                                    put("data", buildJsonObject {
                                        put("item_type", "podcast")
                                        put("item_id", change.entityKey)
                                    })
                                }, change.changeId))
                            } else {
                                val pod = podcastRepository.getPodcast(change.entityKey).firstOrNull()
                                val title = pod?.title ?: "Podcast"
                                opMappings.add(OutboxOpMapping(buildJsonObject {
                                    put("entity", "favorite")
                                    put("op", "U")
                                    put("key", "podcast:${change.entityKey}")
                                    put("hlc", changeHlc)
                                    put("data", buildJsonObject {
                                        put("item_type", "podcast")
                                        put("item_id", change.entityKey)
                                        put("title", title)
                                    })
                                }, change.changeId))
                            }
                        }

                        SyncKind.LOCAL_PLAYLIST -> {
                            if (change.op == SyncOp.DELETE) {
                                opMappings.add(OutboxOpMapping(buildJsonObject {
                                    put("entity", "playlist")
                                    put("op", "D")
                                    put("key", change.entityKey)
                                    put("hlc", changeHlc)
                                }, change.changeId))
                            } else {
                                val localPl = syncOutboxRepository.getLocalPlaylistBySyncId(change.entityKey)
                                if (localPl != null) {
                                    opMappings.add(OutboxOpMapping(buildJsonObject {
                                        put("entity", "playlist")
                                        put("op", "U")
                                        put("key", change.entityKey)
                                        put("hlc", changeHlc)
                                        put("data", buildJsonObject {
                                            put("name", localPl.title)
                                            put("description", "")
                                            put("cover_url", localPl.thumbnail ?: "")
                                            put("is_public", false)
                                            put("youtube_playlist_id", localPl.youtubePlaylistId)
                                        })
                                    }, change.changeId))
                                } else {
                                    unhandledChangeIds.add(change.changeId)
                                }
                            }
                        }

                        SyncKind.LOCAL_PLAYLIST_TRACK -> {
                            val parts = change.entityKey.split("|")
                            val plSyncId = parts.getOrNull(0) ?: ""
                            val videoId = parts.getOrNull(1) ?: ""
                            val trackKey = if (videoId.isNotEmpty()) "$plSyncId|$videoId" else change.entityKey
                            val inPlaylist = change.op != SyncOp.DELETE && change.value != "0"
                            if (!inPlaylist) {
                                opMappings.add(OutboxOpMapping(buildJsonObject {
                                    put("entity", "playlist_track")
                                    put("op", "D")
                                    put("key", trackKey)
                                    put("hlc", changeHlc)
                                    put("data", buildJsonObject {
                                        put("playlist_id", plSyncId)
                                        put("track_id", videoId)
                                    })
                                }, change.changeId))
                            } else {
                                opMappings.add(OutboxOpMapping(buildJsonObject {
                                    put("entity", "playlist_track")
                                    put("op", "U")
                                    put("key", trackKey)
                                    put("hlc", changeHlc)
                                    put("data", buildJsonObject {
                                        put("playlist_id", plSyncId)
                                        put("track_id", videoId)
                                        put("position", 0)
                                    })
                                }, change.changeId))
                            }
                        }

                        SyncKind.LOCAL_PLAYLIST_ORDER -> {
                            val localPl = syncOutboxRepository.getLocalPlaylistBySyncId(change.entityKey)
                            if (localPl != null) {
                                val orderedTrackIds = syncOutboxRepository.getPlaylistTrackOrder(localPl.id)
                                if (orderedTrackIds.isNotEmpty()) {
                                    for ((idx, trkId) in orderedTrackIds.withIndex()) {
                                        val trackKey = "${change.entityKey}|$trkId"
                                        opMappings.add(OutboxOpMapping(buildJsonObject {
                                            put("entity", "playlist_track")
                                            put("op", "U")
                                            put("key", trackKey)
                                            put("hlc", changeHlc)
                                            put("data", buildJsonObject {
                                                put("playlist_id", change.entityKey)
                                                put("track_id", trkId)
                                                put("position", idx)
                                            })
                                        }, change.changeId))
                                    }
                                } else {
                                    unhandledChangeIds.add(change.changeId)
                                }
                            } else {
                                unhandledChangeIds.add(change.changeId)
                            }
                        }

                        SyncKind.HISTORY -> {
                            val ev = historyEvents[change.entityKey]
                            if (ev != null) {
                                val song = songRepository.getSongById(ev.videoId).firstOrNull()
                                val clientEventId = ev.syncId ?: change.entityKey
                                opMappings.add(OutboxOpMapping(buildJsonObject {
                                    put("entity", "history")
                                    put("op", "I")
                                    put("key", clientEventId)
                                    put("hlc", changeHlc)
                                    put("data", buildJsonObject {
                                        put("client_event_id", clientEventId)
                                        put("track_id", ev.videoId)
                                        put("listened_duration_sec", ev.listenedSecond)
                                        put("artist_id", "")
                                        put("album_id", ev.albumBrowseId ?: "")
                                        put("title", song?.title ?: "")
                                        put("artist_name", song?.artistName?.firstOrNull() ?: "")
                                        put("album_name", song?.albumName ?: "")
                                        put("listened_at", ev.timestamp.toString())
                                    })
                                }, change.changeId))
                            } else {
                                unhandledChangeIds.add(change.changeId)
                            }
                        }

                        SyncKind.SETTINGS -> {
                            if (change.entityKey in SYNCABLE_SETTINGS_KEYS) {
                                opMappings.add(OutboxOpMapping(buildJsonObject {
                                    put("entity", "settings")
                                    put("op", "U")
                                    put("key", change.entityKey)
                                    put("hlc", changeHlc)
                                    put("data", buildJsonObject {
                                        put("settings", buildJsonObject {
                                            put(change.entityKey, change.value ?: "")
                                        })
                                    })
                                }, change.changeId))
                            } else {
                                unhandledChangeIds.add(change.changeId)
                            }
                        }

                        SyncKind.EPISODE_STATE -> {
                            val isDelete = change.op == SyncOp.DELETE
                            if (isDelete) {
                                opMappings.add(OutboxOpMapping(buildJsonObject {
                                    put("entity", "episode_state")
                                    put("op", "D")
                                    put("key", change.entityKey)
                                    put("hlc", changeHlc)
                                }, change.changeId))
                            } else {
                                val dataObj = try {
                                    change.value?.let { Json.parseToJsonElement(it).jsonObject }
                                } catch (_: Exception) { null }
                                opMappings.add(OutboxOpMapping(buildJsonObject {
                                    put("entity", "episode_state")
                                    put("op", "U")
                                    put("key", change.entityKey)
                                    put("hlc", changeHlc)
                                    put("data", buildJsonObject {
                                        put("podcast_id", dataObj?.get("podcast_id")?.jsonPrimitive?.contentOrNull ?: "")
                                        put("position_sec", dataObj?.get("position_sec")?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0)
                                        put("completed", dataObj?.get("completed")?.jsonPrimitive?.booleanOrNull ?: false)
                                    })
                                }, change.changeId))
                            }
                        }
                    }
                }

                if (unhandledChangeIds.isNotEmpty()) {
                    syncOutboxRepository.markRejected(unhandledChangeIds)
                }

                if (opMappings.isNotEmpty()) {
                    val chunkedMappings = opMappings.chunked(100)
                    for (chunk in chunkedMappings) {
                        val chunkOps = chunk.map { it.op }
                        val pushRes = SupabaseLibrarySync.syncPush(JsonArray(chunkOps), deviceId).getOrNull()
                        if (pushRes != null) {
                            val appliedChangeIds = mutableSetOf<String>()
                            val rejectedChangeIds = mutableSetOf<String>()

                            if (pushRes.results.isNotEmpty()) {
                                for ((idx, result) in pushRes.results.withIndex()) {
                                    val mapping = chunk.getOrNull(idx)
                                    if (mapping != null) {
                                        when (result.status) {
                                            "applied", "stale" -> appliedChangeIds.add(mapping.changeId)
                                            "rejected" -> rejectedChangeIds.add(mapping.changeId)
                                        }
                                    }
                                }
                            } else if (pushRes.rejected == 0 && pushRes.cursor > 0L) {
                                appliedChangeIds.addAll(chunk.map { it.changeId })
                            }

                            // If an individual change had any rejected ops, do not mark it applied
                            appliedChangeIds.removeAll(rejectedChangeIds)

                            if (appliedChangeIds.isNotEmpty()) {
                                syncOutboxRepository.markSent(appliedChangeIds.toList(), pushRes.cursor)
                            }
                            if (rejectedChangeIds.isNotEmpty()) {
                                syncOutboxRepository.markRejected(rejectedChangeIds.toList())
                                _syncState.value = SyncStatus.Error("Sync push rejected ${rejectedChangeIds.size} changes")
                            }
                            // Pull cursor is NEVER advanced by sync_push
                        }
                    }
                }

                val sevenDaysAgo = Clock.System.now().toEpochMilliseconds() - 7 * 24 * 60 * 60 * 1000L
                syncOutboxRepository.pruneSent(sevenDaysAgo)
            }
        } catch (_: Exception) {
        }
    }

    /**
     * Applies an incoming SyncPullOp to local Room SQLite.
     */
    private suspend fun applyDeltaOp(op: SyncPullOp) {
        try {
            when (op.entity) {
                "like" -> {
                    if (op.op == "D") {
                        songRepository.updateLikeStatus(op.key, 0)
                    } else {
                        val existing = songRepository.getSongById(op.key).firstOrNull()
                        if (existing == null) {
                            val meta = op.data?.get("snapshot")?.jsonObject
                                ?: op.data?.get("meta")?.jsonObject
                            val title = meta?.get("title")?.jsonPrimitive?.contentOrNull ?: "Synced Track"
                            val artist = meta?.get("artist")?.jsonPrimitive?.contentOrNull ?: "Unknown Artist"
                            val album = meta?.get("album")?.jsonPrimitive?.contentOrNull ?: "Streamify"
                            val durationSec = meta?.get("duration_sec")?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
                            val thumb = meta?.get("thumb")?.jsonPrimitive?.contentOrNull
                                ?: meta?.get("thumb_url")?.jsonPrimitive?.contentOrNull
                            val entity = SongEntity(
                                videoId = op.key,
                                title = title,
                                artistName = listOf(artist),
                                albumName = album,
                                duration = formatDuration(durationSec),
                                durationSeconds = durationSec,
                                thumbnails = thumb?.ifBlank { null },
                                isAvailable = true,
                                isExplicit = false,
                                likeStatus = "LIKE",
                                videoType = "MUSIC_VIDEO_TYPE_ATV",
                                category = null,
                                resultType = null,
                                liked = true
                            )
                            songRepository.insertSong(entity).lastOrNull()
                        }
                        songRepository.updateLikeStatus(op.key, 1)
                    }
                }
                "favorite" -> {
                    val itemType = op.data?.get("item_type")?.jsonPrimitive?.contentOrNull
                        ?: op.key.substringBefore(":")
                    val itemId = op.data?.get("item_id")?.jsonPrimitive?.contentOrNull
                        ?: op.key.substringAfter(":")
                    val isLiked = op.op != "D"
                    when (itemType) {
                        "artist" -> artistRepository.updateFollowedStatus(itemId, if (isLiked) 1 else 0)
                        "album" -> albumRepository.updateAlbumLiked(itemId, if (isLiked) 1 else 0)
                        "podcast" -> podcastRepository.favoritePodcast(itemId, isLiked).firstOrNull()
                        "playlist" -> playlistRepository.updatePlaylistLiked(itemId, if (isLiked) 1 else 0)
                    }
                }
                "library_track" -> {
                    if (op.op == "D") {
                        songRepository.updateSongInLibrary(Config.REMOVED_SONG_DATE_TIME, op.key).firstOrNull()
                    } else {
                        val existing = songRepository.getSongById(op.key).firstOrNull()
                        if (existing == null) {
                            val entity = SongEntity(
                                videoId = op.key,
                                title = "Synced Track",
                                albumName = "Streamify",
                                duration = "0:00",
                                durationSeconds = 0,
                                isAvailable = true,
                                isExplicit = false,
                                likeStatus = "INDIFFERENT",
                                videoType = "MUSIC_VIDEO_TYPE_ATV",
                                category = null,
                                resultType = null,
                                inLibrary = now()
                            )
                            songRepository.insertSong(entity).lastOrNull()
                        } else {
                            songRepository.updateSongInLibrary(now(), op.key).firstOrNull()
                        }
                    }
                }
                "playlist" -> {
                    val localPlaylists = localPlaylistRepository.getAllLocalPlaylists().firstOrNull() ?: emptyList()
                    val matched = localPlaylists.find { it.syncId == op.key }
                    if (op.op == "D") {
                        if (matched != null) {
                            localPlaylistRepository.deleteLocalPlaylist(matched.id, "Synced deletion").firstOrNull()
                        }
                    } else {
                        val name = op.data?.get("name")?.jsonPrimitive?.contentOrNull ?: "Synced Playlist"
                        val cover = op.data?.get("cover_url")?.jsonPrimitive?.contentOrNull
                        val ytId = op.data?.get("youtube_playlist_id")?.jsonPrimitive?.contentOrNull
                        if (matched == null) {
                            localPlaylistRepository.insertLocalPlaylist(
                                LocalPlaylistEntity(
                                    title = name,
                                    thumbnail = cover?.ifBlank { null },
                                    syncId = op.key,
                                    youtubePlaylistId = ytId
                                ),
                                "Synced from Cloud"
                            ).firstOrNull()
                        }
                    }
                }
                "playlist_track" -> {
                    val playlistIdStr = op.data?.get("playlist_id")?.jsonPrimitive?.contentOrNull
                    val trackId = op.data?.get("track_id")?.jsonPrimitive?.contentOrNull
                    if (!playlistIdStr.isNullOrBlank() && !trackId.isNullOrBlank()) {
                        val localPlaylists = localPlaylistRepository.getAllLocalPlaylists().firstOrNull() ?: emptyList()
                        val matched = localPlaylists.find { it.syncId == playlistIdStr }
                        if (matched != null) {
                            val song = songRepository.getSongById(trackId).firstOrNull() ?: SongEntity(
                                videoId = trackId,
                                title = "Synced Track",
                                duration = "0:00",
                                durationSeconds = 0,
                                isAvailable = true,
                                isExplicit = false,
                                likeStatus = "INDIFFERENT",
                                videoType = "MUSIC_VIDEO_TYPE_ATV",
                                category = null,
                                resultType = null
                            ).also { songRepository.insertSong(it).lastOrNull() }
                            if (op.op == "D") {
                                localPlaylistRepository.removeTrackFromLocalPlaylist(
                                    id = matched.id,
                                    song = song,
                                    successMessage = "",
                                    updatedYtMessage = "",
                                    errorMessage = ""
                                ).firstOrNull()
                            } else {
                                localPlaylistRepository.addTrackToLocalPlaylist(
                                    id = matched.id,
                                    song = song,
                                    successMessage = "",
                                    updatedYtMessage = "",
                                    errorMessage = ""
                                ).firstOrNull()
                            }
                        }
                    }
                }
                "settings" -> {
                    val key = op.data?.get("key")?.jsonPrimitive?.contentOrNull ?: op.key
                    val value = op.data?.get("value")?.jsonPrimitive?.contentOrNull
                        ?: op.data?.get("val")?.jsonPrimitive?.contentOrNull
                    if (key in SYNCABLE_SETTINGS_KEYS && value != null) {
                        dataStoreManager.putString(key, value)
                    }
                }
                "search" -> {
                    if (op.op != "D") {
                        searchRepository.insertSearchHistory(SearchHistory(op.key)).firstOrNull()
                    }
                }
                "history" -> {
                    if (op.op != "D") {
                        val trackId = op.data?.get("track_id")?.jsonPrimitive?.contentOrNull ?: op.key
                        val artistId = op.data?.get("artist_id")?.jsonPrimitive?.contentOrNull
                        val albumId = op.data?.get("album_id")?.jsonPrimitive?.contentOrNull
                        val dur = op.data?.get("listened_duration_sec")?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L
                        if (trackId.isNotBlank() && !trackId.startsWith("trk_")) {
                            analyticsRepository.insertPlaybackEvent(
                                videoId = trackId,
                                channelIds = listOfNotNull(artistId),
                                albumBrowseId = albumId,
                                durationSecond = dur,
                                listenedSecond = dur
                            ).firstOrNull()
                        }
                    }
                }
                "episode_state" -> {
                    val episodeId = op.key
                    val podcastId = op.data?.get("podcast_id")?.jsonPrimitive?.contentOrNull ?: ""
                    val pos = op.data?.get("position_sec")?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
                    val comp = op.data?.get("completed")?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false
                    val opHlc = op.hlc ?: 0L
                    updateLocalEpisodeState(episodeId, podcastId, pos, comp, opHlc)
                }
            }
        } catch (_: Exception) {
        }
    }

    /**
     * V3 Presence Synchronization with HLC guard and dual-write backward compatibility.
     */
    private suspend fun syncPresenceInternal(targetUserId: String, deviceId: String, hlc: Long) {
        try {
            val localMediaId = dataStoreManager.recentMediaId.firstOrNull() ?: ""
            val localPosStr = dataStoreManager.recentPosition.firstOrNull() ?: "0"
            val localPos = localPosStr.toLongOrNull() ?: 0L
            val localQueue = songRepository.getSavedQueue().firstOrNull()?.firstOrNull()?.listTrack?.map { it.videoId } ?: emptyList()

            // 1. Send V3 presence heartbeat with HLC guard
            if (localMediaId.isNotBlank() && !localMediaId.startsWith("trk_")) {
                SupabaseLibrarySync.heartbeatPresence(
                    deviceId = deviceId,
                    trackId = localMediaId,
                    positionMs = localPos,
                    isPlaying = isCurrentPlaybackActive,
                    queueIds = localQueue.take(200),
                    hlc = hlc
                )
            }

            // 2. Remote presence hand-off if local device is idle
            if (localMediaId.isBlank()) {
                val latestPresence = SupabaseLibrarySync.fetchLatestPresence().getOrNull()
                val trackId = latestPresence?.trackId
                if (latestPresence != null && !trackId.isNullOrBlank() && latestPresence.deviceId != deviceId) {
                    dataStoreManager.saveRecentSong(trackId, latestPresence.positionMs)
                }
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

    suspend fun getLocalEpisodeStates(): Map<String, LocalEpisodeState> {
        val raw = dataStoreManager.getString("streamify_podcast_episode_states").firstOrNull() ?: return emptyMap()
        return try {
            val jsonObj = json.parseToJsonElement(raw).jsonObject
            jsonObj.mapValues { (epId, elem) ->
                val obj = elem.jsonObject
                LocalEpisodeState(
                    episodeId = epId,
                    podcastId = obj["podcast_id"]?.jsonPrimitive?.contentOrNull ?: "",
                    positionSec = obj["position_sec"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0,
                    completed = obj["completed"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false,
                    modifiedHlc = obj["modified_hlc"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L
                )
            }
        } catch (_: Exception) {
            emptyMap()
        }
    }

    suspend fun saveLocalEpisodeStates(states: Map<String, LocalEpisodeState>) {
        try {
            val jsonObj = buildJsonObject {
                states.forEach { (epId, state) ->
                    put(epId, buildJsonObject {
                        put("podcast_id", state.podcastId)
                        put("position_sec", state.positionSec)
                        put("completed", state.completed)
                        put("modified_hlc", state.modifiedHlc)
                    })
                }
            }
            dataStoreManager.putString("streamify_podcast_episode_states", jsonObj.toString())
        } catch (_: Exception) {
        }
    }

    suspend fun updateLocalEpisodeState(
        episodeId: String,
        podcastId: String,
        positionSec: Int,
        completed: Boolean,
        hlc: Long = 0L
    ) {
        val current = getLocalEpisodeStates().toMutableMap()
        val existing = current[episodeId]
        val resolvedHlc = if (hlc > 0L) hlc else HybridLogicalClock.now()
        if (existing == null || resolvedHlc >= existing.modifiedHlc) {
            current[episodeId] = LocalEpisodeState(
                episodeId = episodeId,
                podcastId = podcastId.ifBlank { existing?.podcastId ?: "" },
                positionSec = positionSec,
                completed = completed,
                modifiedHlc = resolvedHlc
            )
            saveLocalEpisodeStates(current)
        }
    }

    suspend fun recordEpisodeProgress(
        episodeId: String,
        podcastId: String,
        positionSec: Int,
        completed: Boolean
    ) {
        val hlc = HybridLogicalClock.now()
        updateLocalEpisodeState(episodeId, podcastId, positionSec, completed, hlc)
        try {
            val deviceId = getOrCreateDeviceId()
            val payload = buildJsonObject {
                put("podcast_id", podcastId)
                put("position_sec", positionSec)
                put("completed", completed)
            }.toString()
            syncOutboxRepository.recordPending(
                SyncChangeEntity(
                    changeId = HybridLogicalClock.randomUuid(),
                    entity = SyncKind.EPISODE_STATE,
                    entityKey = episodeId,
                    op = SyncOp.UPSERT,
                    value = payload,
                    changedAt = hlc,
                    deviceId = deviceId,
                )
            )
        } catch (_: Exception) {}
        val targetUserId = SupabaseClient.currentUser.value?.id
        if (!isGuestUserId(targetUserId)) {
            SupabaseLibrarySync.syncEpisodeState(
                episodeId = episodeId,
                podcastId = podcastId,
                positionSec = positionSec,
                completed = completed,
                hlc = hlc,
                userId = targetUserId
            )
        }
    }

    fun recordSettingChange(key: String, value: String) {
        if (key in SYNCABLE_SETTINGS_KEYS) {
            scope.launch {
                try {
                    val deviceId = getOrCreateDeviceId()
                    val change = SyncChangeEntity(
                        changeId = HybridLogicalClock.randomUuid(),
                        entity = SyncKind.SETTINGS,
                        entityKey = key,
                        op = SyncOp.SET,
                        value = value,
                        changedAt = HybridLogicalClock.now(),
                        deviceId = deviceId,
                    )
                    syncOutboxRepository.recordPending(change)
                } catch (_: Exception) {
                }
            }
        }
    }

    suspend fun getEpisodeState(episodeId: String): LocalEpisodeState? {
        return getLocalEpisodeStates()[episodeId]
    }

    suspend fun getAllEpisodeStates(): Map<String, LocalEpisodeState> {
        return getLocalEpisodeStates()
    }

    /**
     * V4.1 Fleet Telemetry: Periodically reports device hardware, OS, and build telemetry.
     * Fires immediately on cold start, on version upgrade or gitSha change,
     * or when the 1-hour keep-alive floor has elapsed.
     */
    private suspend fun syncDeviceTelemetryInternal(targetUserId: String, deviceId: String) {
        try {
            val currentVersionCode = BuildKonfig.versionCode
            val currentGitSha = BuildKonfig.gitSha
            val nowMs = System.currentTimeMillis()

            val lastVersionCode = dataStoreManager.getString("streamify_telemetry_reported_version_code")
                .firstOrNull()?.toIntOrNull() ?: 0
            val lastGitSha = dataStoreManager.getString("streamify_telemetry_reported_git_sha")
                .firstOrNull() ?: ""
            val lastReportedMs = dataStoreManager.getString("streamify_telemetry_reported_timestamp_ms")
                .firstOrNull()?.toLongOrNull() ?: 0L

            val isUpgradeOrRespin = (currentVersionCode != lastVersionCode && currentVersionCode > 0) ||
                (currentGitSha.isNotBlank() && currentGitSha != lastGitSha)
            val isKeepAliveDue = (nowMs - lastReportedMs) > TELEMETRY_INTERVAL_MS

            if (!isUpgradeOrRespin && !isKeepAliveDue && lastReportedMs > 0L) {
                // Throttled: zero redundant network calls
                return
            }

            val platformInfo = resolvePlatformDeviceInfo()
            val regResult = SupabaseLibrarySync.recordDeviceSession(
                deviceId = deviceId,
                platform = platformInfo.platform,
                appVersion = BuildKonfig.versionName,
                versionCode = currentVersionCode,
                gitSha = currentGitSha,
                buildFlavor = platformInfo.buildFlavor,
                deviceModel = platformInfo.deviceModel,
                osVersion = platformInfo.osVersion,
                architecture = platformInfo.architecture
            ).getOrNull()

            if (regResult != null && regResult.status == "ok") {
                dataStoreManager.putString("streamify_telemetry_reported_version_code", currentVersionCode.toString())
                dataStoreManager.putString("streamify_telemetry_reported_git_sha", currentGitSha)
                dataStoreManager.putString("streamify_telemetry_reported_timestamp_ms", nowMs.toString())
            }
        } catch (_: Exception) {
            // Telemetry reporting must never crash syncAll pipeline
        }
    }
}
