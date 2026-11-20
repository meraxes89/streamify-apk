package com.streamify.app.data.repository
import com.streamify.app.util.SLog
import com.streamify.app.data.models.Track
import com.streamify.app.data.models.toTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.streamify.app.data.persistence.DatabaseInitializer
import com.streamify.app.data.discovery.FuzzyTitleMatcher
import com.streamify.app.data.discovery.ReRanker
import com.streamify.app.data.NativeBridge

object TrackRepository : TrackRepositoryApi {
    var appContext: android.content.Context? = null

    private val _allTracks = MutableStateFlow<List<Track>>(emptyList())
    override val allTracks: StateFlow<List<Track>> = _allTracks.asStateFlow()
    val trackFlow: StateFlow<List<Track>> = allTracks

    private val _localTracks = MutableStateFlow<List<Track>>(emptyList())
    val localTracks: StateFlow<List<Track>> = _localTracks.asStateFlow()

    private val _likedTracks = MutableStateFlow<List<Track>>(emptyList())
    val likedTracks: StateFlow<List<Track>> = _likedTracks.asStateFlow()

    override suspend fun refresh(): List<Track> = withContext(Dispatchers.IO) {
        DatabaseInitializer.ensureInitialized()
        val prefs = appContext?.getSharedPreferences("audio_settings", android.content.Context.MODE_PRIVATE)
        val isLocalAudioEnabled = prefs?.getBoolean("enable_local_audio", false) ?: false

        val likedIds = NativeBridge.getLikedTracks(1).map { it.id }.toSet()
        val allNative = NativeBridge.getAllTracks().map { native ->
            native.toTrack().copy(isLiked = likedIds.contains(native.id))
        }

        val (localOnly, cloudTracks) = allNative.partition { it.source == "local" }
        _localTracks.value = localOnly

        val finalTracks = if (isLocalAudioEnabled) allNative else cloudTracks
        _allTracks.value = finalTracks
        val liked = finalTracks.filter { it.isLiked }
        _likedTracks.value = liked
        rebuildIndex(finalTracks, liked)

        // Background Cloud Sync (only sync cloud tracks to avoid local path pollution)
        try {
            com.streamify.app.data.supabase.SupabaseClient.syncCloudLikes(cloudTracks)
        } catch (e: Exception) {
            // Ignore offline cloud sync errors
        }

        finalTracks
    }

    private val _cadIdIndex = java.util.concurrent.ConcurrentHashMap<Long, Track>()
    private val _idIndex = java.util.concurrent.ConcurrentHashMap<Int, Track>()
    private val _videoIdIndex = java.util.concurrent.ConcurrentHashMap<String, Track>()
    private val _filepathIndex = java.util.concurrent.ConcurrentHashMap<String, Track>()
    @Volatile private var _likedIds = emptySet<Int>()
    @Volatile private var _likedFnvSet = emptySet<Long>()

    // ── Gap #27: Taste Profile exclusions ────────────────────────────────
    // Lock-free immutable snapshot read; empty set short-circuits the hot
    // telemetry paths to a single volatile read.
    private val taste: com.streamify.app.data.discovery.TasteExclusionIndex
        get() = com.streamify.app.data.discovery.TasteProfileGuard.index()

    private fun com.streamify.app.data.discovery.TasteExclusionIndex.anyTrackIdExcluded(
        trackIds: List<Int>
    ): Boolean {
        if (excludedTrackKeys.isEmpty()) return false
        return trackIds.any { id -> _idIndex[id]?.let { isTrackExcluded(it) } ?: false }
    }

    override fun setTrackExcludedFromTaste(track: Track, excluded: Boolean) {
        com.streamify.app.data.discovery.TasteProfileGuard.setTrackExcluded(track, excluded)
    }

    override fun isTrackExcludedFromTaste(track: Track): Boolean = taste.isTrackExcluded(track)

    override fun setPlaylistExcludedFromTaste(playlistName: String, excluded: Boolean) {
        com.streamify.app.data.discovery.TasteProfileGuard.setPlaylistExcluded(playlistName, excluded)
    }

    override fun isPlaylistExcludedFromTaste(playlistName: String): Boolean =
        taste.isPlaylistExcluded(playlistName)

    private fun rebuildIndex(tracks: List<Track>, liked: List<Track>) {
        _idIndex.clear()
        _videoIdIndex.clear()
        _filepathIndex.clear()
        _cadIdIndex.clear()

        for (track in tracks) {
            if (track.id > 0) _idIndex[track.id] = track
            if (!track.ytmVideoId.isNullOrBlank()) _videoIdIndex[track.ytmVideoId] = track
            if (track.filepath.isNotBlank()) _filepathIndex[track.filepath] = track
            val hash = FuzzyTitleMatcher.extractRootHash("${track.title}\u0001${track.artist}")
            if (hash != 0L) _cadIdIndex[hash] = track
        }

        _likedIds = liked.map { it.id }.filter { it > 0 }.toSet()
        _likedFnvSet = liked.map { FuzzyTitleMatcher.extractRootHash("${it.title}\u0001${it.artist}") }.filter { it != 0L }.toSet()
    }
    
    suspend fun getAllTracks(): List<Track> = withContext(Dispatchers.IO) {
        refresh()
    }
    
    override suspend fun getTracksByIds(ids: List<Int>): List<Track> = withContext(Dispatchers.IO) {
        val tracks = _allTracks.value.ifEmpty { refresh() }
        val idSet = ids.toSet()
        val foundTracks = tracks.filter { it.id in idSet }.associateBy { it.id }
        ids.mapNotNull { foundTracks[it] }
    }
    
    override suspend fun searchTracks(query: String): List<Track> = withContext(Dispatchers.IO) {
        // Close the init race honestly: wait for the native DB instead of racing it.
        // ensureInitialized() is an idempotent shared CompletableDeferred that always
        // completes (even if initDatabase failed), so this never hangs.
        DatabaseInitializer.ensureInitialized()
        val likedIds = try {
            NativeBridge.getLikedTracks(1).map { it.id }.toSet()
        } catch (t: Throwable) {
            SLog.e("TrackRepository", "Native liked-tracks fetch failed, degrading", t)
            emptySet<Int>()
        }
        val directMatches = try {
            NativeBridge.searchTracks(query).map { native ->
                native.toTrack().copy(isLiked = likedIds.contains(native.id))
            }
        } catch (t: Throwable) {
            // UnsatisfiedLinkError is an Error, not an Exception — only catch(Throwable)
            // keeps a missing/mismatched native lib from killing the process. Degraded
            // local search falls through to the fuzzy in-memory path below.
            SLog.e("TrackRepository", "Native search failed, degrading to fuzzy fallback", t)
            emptyList()
        }
        if (directMatches.isNotEmpty()) {
            return@withContext directMatches
        }
        // Typo-tolerant fuzzy fallback across in-memory tracks
        val all = _allTracks.value
        val cleanQ = query.trim().lowercase()
        if (cleanQ.length >= 2) {
            val fuzzyMatches = all.mapNotNull { track ->
                val simTitle = com.streamify.app.data.discovery.FuzzyTitleMatcher.calculateSimilarity(cleanQ, track.title)
                val simArtist = com.streamify.app.data.discovery.FuzzyTitleMatcher.calculateSimilarity(cleanQ, track.artist)
                val maxSim = maxOf(simTitle, simArtist)
                if (maxSim >= 0.65) Pair(track.copy(isLiked = likedIds.contains(track.id)), maxSim) else null
            }.sortedByDescending { it.second }.map { it.first }
            return@withContext fuzzyMatches.take(20)
        }
        emptyList()
    }
    
    suspend fun getLikedTracks(userId: Int = 1): List<Track> = withContext(Dispatchers.IO) {
        val liked = NativeBridge.getLikedTracks(userId).map { it.toTrack().copy(isLiked = true) }
        _likedTracks.value = liked
        _likedIds = liked.map { it.id }.filter { it > 0 }.toSet()
        _likedFnvSet = liked.map { FuzzyTitleMatcher.extractRootHash("${it.title}\u0001${it.artist}") }.filter { it != 0L }.toSet()
        liked
    }
    
    override fun isTrackLiked(track: Track): Boolean {
        if (track.id > 0 && _likedIds.contains(track.id)) {
            return true
        }
        val hash = FuzzyTitleMatcher.extractRootHash("${track.title}\u0001${track.artist}")
        if (hash != 0L && _likedFnvSet.contains(hash)) {
            return true
        }
        return _likedTracks.value.any { liked ->
            com.streamify.app.data.discovery.FuzzyTitleMatcher.isSameSongVariation(liked.title, liked.artist, track.title, track.artist)
        }
    }

    override fun hydrateTrack(track: Track): Track {
        val liked = isTrackLiked(track)
        val matchedInDb = if (track.id > 0) {
            _idIndex[track.id] ?: _allTracks.value.find { it.id == track.id }
        } else {
            // O(1) Fast paths: Direct VideoId -> Filepath -> FNV-1a Root Hash
            val byVid = track.ytmVideoId?.let { _videoIdIndex[it] }
            val byPath = if (track.filepath.isNotBlank()) _filepathIndex[track.filepath] else null
            val rootHash = FuzzyTitleMatcher.extractRootHash("${track.title}\u0001${track.artist}")
            val byHash = if (rootHash != 0L) {
                val candidate = _cadIdIndex[rootHash]
                // 6-second tolerance check to prevent duration poisoning across remix variations
                if (candidate != null && (candidate.durationSec <= 0 || track.durationSec <= 0 || kotlin.math.abs(candidate.durationSec - track.durationSec) <= 6)) {
                    candidate
                } else null
            } else null

            byVid ?: byPath ?: byHash ?: _allTracks.value.find {
                it.id > 0 && (
                    it.filepath == track.filepath ||
                    // Identity guard: two tracks carrying DIFFERENT explicit video
                    // IDs are provably different recordings — never let weak fuzzy
                    // matching merge them (poisoned-pin protection: adopting the
                    // wrong row's id lets vault-swaps serve wrong audio and lets
                    // registerStreamedTrack overwrite another song's canonical pin).
                    (it.ytmVideoId != null && track.ytmVideoId != null && it.ytmVideoId == track.ytmVideoId) ||
                    (
                        com.streamify.app.data.discovery.FuzzyTitleMatcher.isSameSongVariation(it.title, it.artist, track.title, track.artist) &&
                        // Artist gate: title-only fuzzy matching used to merge
                        // different artists' same-titled songs into one identity.
                        com.streamify.app.data.discovery.FuzzyTitleMatcher.artistsMatch(it.artist, track.artist) &&
                        // Same rule inside the fuzzy branch: conflicting explicit IDs veto the merge.
                        (it.ytmVideoId == null || track.ytmVideoId == null || it.ytmVideoId == track.ytmVideoId)
                    )
                )
            }
        }

        val resolvedVid = track.ytmVideoId ?: matchedInDb?.ytmVideoId ?: com.streamify.app.data.network.YouTubeStreamResolver.extractVideoId(track.filepath, track.coverArtPath)

        return if (matchedInDb != null) {
            track.copy(
                id = matchedInDb.id,
                coverArtPath = if (!matchedInDb.coverArtPath.isNullOrBlank()) matchedInDb.coverArtPath else track.coverArtPath,
                isLiked = liked,
                ytmVideoId = resolvedVid
            )
        } else {
            track.copy(isLiked = liked, ytmVideoId = resolvedVid)
        }
    }

    override suspend fun registerStreamedTrack(
        track: Track,
        context: android.content.Context?,
        addToDefaultPlaylist: Boolean
    ): Track = withContext(Dispatchers.IO) {
        val albumName = if (track.album.isNotBlank() && !track.album.equals("Single", ignoreCase = true)) track.album else "Streamify"

        // 1. Guard against canonical overwrites: prioritize explicit ytmVideoId or extracted ID
        val existingVideoId = track.ytmVideoId?.takeIf { com.streamify.app.data.network.YouTubeStreamResolver.extractVideoId(it) != null }
            ?: com.streamify.app.data.network.YouTubeStreamResolver.extractVideoId(track.filepath, track.coverArtPath)

        var canonicalPath = if (!existingVideoId.isNullOrBlank()) {
            "https://www.youtube.com/watch?v=$existingVideoId"
        } else {
            var path = com.streamify.app.data.network.YouTubeStreamResolver.sanitizeForStorage(track.filepath, track.title, track.artist, track.coverArtPath)
            if (path.startsWith("ytsearch:") || path.isBlank()) {
                val cid = com.streamify.app.data.network.CanonicalSeedResolver.resolveToCanonicalId(track)
                if (cid.length == 11 && cid != "dQw4w9WgXcQ") {
                    path = "https://www.youtube.com/watch?v=$cid"
                }
            }
            path
        }

        val videoId = existingVideoId ?: com.streamify.app.data.network.YouTubeStreamResolver.extractVideoId(canonicalPath, track.coverArtPath)
        val sanitizedCover = com.streamify.app.data.network.YouTubeStreamResolver.sanitizeCoverUrl(track.coverArtPath, videoId)


        val validId = NativeBridge.upsertStreamedTrack(
            filepath = canonicalPath,
            title = track.title,
            artist = track.artist,
            album = albumName,
            durationSec = track.durationSec,
            coverArtPath = sanitizedCover ?: "",
            lyricsPath = track.lyricsPath ?: "",
            bpm = track.bpm,
            key = track.key
        )

        val savedId = if (validId > 0) validId else track.id
        val isLikedInDb = if (savedId > 0) {
            try {
                val likedIds = NativeBridge.getLikedTracks(1).map { it.id }.toSet()
                likedIds.contains(savedId)
            } catch (e: Exception) {
                isTrackLiked(track)
            }
        } else {
            isTrackLiked(track)
        }

        val updatedTrack = track.copy(
            id = savedId,
            filepath = canonicalPath,
            coverArtPath = sanitizedCover,
            album = albumName,
            source = "online_stream",
            isLiked = isLikedInDb,
            ytmVideoId = videoId
        )

        if (savedId > 0) {
            // 1. Auto-add to "Streamify" playlist only if explicitly requested
            if (addToDefaultPlaylist) {
                try {
                    var streamifyPl = PlaylistRepository.getPlaylists().find { it.name.equals("Streamify", ignoreCase = true) }
                    if (streamifyPl == null) {
                        streamifyPl = PlaylistRepository.createPlaylist("Streamify", "Auto-saved Streamify songs")
                    }
                    PlaylistRepository.addTrackToPlaylist(streamifyPl.id, savedId)
                } catch (e: Exception) {
                    SLog.st("TrackRepository", "TrackRepository.registerStreamedTrack failed", e)
                }
            }

            // 2. High-speed zero-audio text embedding for instant AI indexing
            val ctx = context ?: appContext
            if (ctx != null) {
                try {
                    val textEngine = com.streamify.app.media.ingestion.TextEmbeddingEngine.getInstance(ctx)
                    val embedding = textEngine.generateEmbedding("${track.artist} - ${track.title} [${albumName}]")
                    NativeBridge.updateTrackEmbedding(savedId, embedding)
                } catch (e: Exception) {
                    SLog.st("TrackRepository", "TrackRepository.registerStreamedTrack failed", e)
                }
            }

            // 3. Instant Push to Supabase Cloud
            kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
                try {
                    com.streamify.app.data.supabase.SupabaseClient.upsertCloudTrack(updatedTrack)
                } catch (e: Exception) {
                    SLog.st("TrackRepository", "TrackRepository.registerStreamedTrack failed", e)
                }
            }

            // 4. Enqueue to OnlineTrackProcessor for autonomous background native C++ DSP processing
            if (updatedTrack.bpm <= 0f || !updatedTrack.isProcessed) {
                com.streamify.app.media.playback.OnlineTrackProcessor.enqueue(updatedTrack, ctx)
            }
        }

        // Targeted in-memory state patch: Zero full SQLite rescan
        val currentAll = _allTracks.value
        val existingIndex = currentAll.indexOfFirst { it.id == updatedTrack.id || (it.filepath.isNotBlank() && it.filepath == updatedTrack.filepath) }
        val newAll = if (existingIndex >= 0) {
            currentAll.toMutableList().apply { set(existingIndex, updatedTrack) }
        } else {
            currentAll + updatedTrack
        }
        _allTracks.value = newAll
        if (updatedTrack.isLiked) {
            _likedTracks.value = (_likedTracks.value.filter { it.id != updatedTrack.id } + updatedTrack)
        }

        updatedTrack
    }

    override suspend fun toggleLike(trackId: Int, userId: Int, track: Track?): Boolean = withContext(Dispatchers.IO) {
        var targetId = trackId
        val trackObj = track ?: _allTracks.value.find { it.id == trackId }

        // Guarantee DB row exists before adding to user_liked_songs
        if (targetId <= 0 || _allTracks.value.none { it.id == targetId }) {
            if (trackObj != null) {
                val registered = registerStreamedTrack(trackObj)
                targetId = registered.id
            }
        }

        if (targetId > 0) {
            val isNowLiked = NativeBridge.toggleLike(userId, targetId)
            val updated = trackObj ?: _allTracks.value.find { it.id == targetId }
            if (updated != null) {
                val patchedTrack = updated.copy(isLiked = isNowLiked)
                val cleanSig = (updated.title.trim().lowercase() + "_" + updated.artist.trim().lowercase())
                val cloudId = "trk_${kotlin.math.abs(cleanSig.hashCode())}"
                
                // Targeted in-memory state patch: Zero JNI DB thrashing
                _allTracks.value = _allTracks.value.map { if (it.id == targetId) patchedTrack else it }
                _likedTracks.value = if (isNowLiked) {
                    (_likedTracks.value.filter { it.id != targetId } + patchedTrack)
                } else {
                    _likedTracks.value.filter { it.id != targetId }
                }

                // Instant asynchronous push to Supabase Cloud
                kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
                    try {
                        if (isNowLiked) {
                            com.streamify.app.data.supabase.SupabaseClient.upsertCloudTrack(patchedTrack)
                            com.streamify.app.data.supabase.SupabaseClient.addCloudLike(cloudId)
                        } else {
                            com.streamify.app.data.supabase.SupabaseClient.removeCloudLike(cloudId)
                        }
                    } catch (e: Exception) {
                        SLog.st("TrackRepository", "TrackRepository.toggleLike failed", e)
                    }
                }
            }
            isNowLiked
        } else {
            false
        }
    }

    suspend fun getRecommendations(trackId: Int, recentHistory: IntArray = intArrayOf(), userId: Int = 1, limit: Int = 10): List<Track> = withContext(Dispatchers.IO) {
        val recs = NativeBridge.getRecommendations(trackId, recentHistory, userId, limit)
        if (recs.isEmpty()) return@withContext emptyList()
        val all = getAllTracks().associateBy { it.id }
        recs.mapNotNull { rec -> all[rec.trackId] }
    }

    suspend fun processAudioFile(trackId: Int, filePath: String): Int = withContext(Dispatchers.IO) {
        val result = NativeBridge.processAudioFile(trackId, filePath)
        refresh()
        result
    }

    suspend fun updateTrackCoverArt(trackId: Int, coverArtPath: String): Boolean = withContext(Dispatchers.IO) {
        val result = NativeBridge.updateTrackCoverArt(trackId, coverArtPath)
        refresh()
        result
    }

    suspend fun updateTrackMetadata(trackId: Int, title: String, artist: String, album: String): Boolean = withContext(Dispatchers.IO) {
        val result = NativeBridge.updateTrackMetadata(trackId, title, artist, album)
        refresh()
        result
    }

    suspend fun updateTrack(track: Track): Boolean = updateTrackMetadata(track.id, track.title, track.artist, track.album)

    override suspend fun logPlayEvent(fromTrackId: Int, toTrackId: Int, userId: Int) = withContext(Dispatchers.IO) {
        // Gap #27: excluded tracks never feed the transition telemetry.
        if (!taste.anyTrackIdExcluded(listOf(fromTrackId, toTrackId))) {
            NativeBridge.logPlayEvent(fromTrackId, toTrackId, userId)
        }
    }

    override suspend fun logSkipEvent(fromTrackId: Int, toTrackId: Int, userId: Int) = withContext(Dispatchers.IO) {
        if (!taste.anyTrackIdExcluded(listOf(fromTrackId, toTrackId))) {
            NativeBridge.logSkipEvent(fromTrackId, toTrackId, userId)
        }
    }

    suspend fun upsertStreamedTrack(track: Track): Int = withContext(Dispatchers.IO) {
        val existingVideoId = track.ytmVideoId?.takeIf { com.streamify.app.data.network.YouTubeStreamResolver.extractVideoId(it) != null }
            ?: com.streamify.app.data.network.YouTubeStreamResolver.extractVideoId(track.filepath, track.coverArtPath)

        var canonicalPath = if (!existingVideoId.isNullOrBlank()) {
            "https://www.youtube.com/watch?v=$existingVideoId"
        } else {
            var path = com.streamify.app.data.network.YouTubeStreamResolver.sanitizeForStorage(track.filepath, track.title, track.artist, track.coverArtPath)
            if (path.startsWith("ytsearch:") || path.isBlank()) {
                val cid = com.streamify.app.data.network.CanonicalSeedResolver.resolveToCanonicalId(track)
                if (cid.length == 11 && cid != "dQw4w9WgXcQ") {
                    path = "https://www.youtube.com/watch?v=$cid"
                }
            }
            path
        }

        val videoId = existingVideoId ?: com.streamify.app.data.network.YouTubeStreamResolver.extractVideoId(canonicalPath, track.coverArtPath)
        val sanitizedCover = com.streamify.app.data.network.YouTubeStreamResolver.sanitizeCoverUrl(track.coverArtPath, videoId)


        val id = NativeBridge.upsertStreamedTrack(
            filepath = canonicalPath,
            title = track.title,
            artist = track.artist,
            album = track.album,
            durationSec = track.durationSec,
            coverArtPath = sanitizedCover ?: "",
            lyricsPath = track.lyricsPath ?: "",
            bpm = track.bpm,
            key = track.key
        )
        val sanitizedTrack = track.copy(id = id, filepath = canonicalPath, coverArtPath = sanitizedCover, ytmVideoId = videoId)
        // Also mirror to Supabase cloud catalog asynchronously
        try {
            com.streamify.app.data.supabase.SupabaseClient.upsertCloudTrack(sanitizedTrack)
        } catch (e: Exception) {
            // Ignore
        }
        refresh()
        id
    }

    override suspend fun recordTrackPlay(trackId: Int): Boolean = withContext(Dispatchers.IO) {
        if (taste.anyTrackIdExcluded(listOf(trackId))) {
            false // excluded from taste — play count does not feed the seed pool
        } else {
            NativeBridge.recordTrackPlay(trackId)
        }
    }

    override suspend fun getTopPlayedTracks(limit: Int): List<Track> = withContext(Dispatchers.IO) {
        val nativeTracks = NativeBridge.getTopPlayedTracks(limit)
        nativeTracks.map { it.toTrack() }.filterNot { taste.isTrackExcluded(it) }
    }

    override suspend fun updateSessionVector(trackId: Int, alpha: Float) = withContext(Dispatchers.IO) {
        if (trackId > 0 && !taste.anyTrackIdExcluded(listOf(trackId))) {
            NativeBridge.updateSessionVector(trackId, alpha)
        }
    }

    override suspend fun getSessionRecommendations(limit: Int): List<Track> = withContext(Dispatchers.IO) {
        val recs = NativeBridge.getSessionRecommendations(limit)
        if (recs.isEmpty()) return@withContext emptyList()
        val all = getAllTracks().associateBy { it.id }
        recs.mapNotNull { rec -> all[rec.trackId] }.filterNot { taste.isTrackExcluded(it) }
    }

    override suspend fun getLongTermRecommendations(userId: Int, limit: Int): List<Track> = withContext(Dispatchers.IO) {
        val recs = NativeBridge.getLongTermRecommendations(userId, limit)
        if (recs.isEmpty()) return@withContext emptyList()
        val all = getAllTracks().associateBy { it.id }
        recs.mapNotNull { rec -> all[rec.trackId] }.filterNot { taste.isTrackExcluded(it) }
    }

    suspend fun logEngagementEvent(trackId: Int, durationSec: Int, completionRatio: Float, hourOfDay: Int): Boolean = withContext(Dispatchers.IO) {
        if (trackId > 0 && !taste.anyTrackIdExcluded(listOf(trackId))) {
            NativeBridge.logEngagementEvent(trackId, durationSec, completionRatio, hourOfDay)
        } else false
    }

    override suspend fun getCircadianRecommendations(hourOfDay: Int, limit: Int): List<Track> = withContext(Dispatchers.IO) {
        val recs = NativeBridge.getCircadianRecommendations(hourOfDay, limit)
        if (recs.isEmpty()) return@withContext emptyList()
        val all = getAllTracks().associateBy { it.id }
        recs.mapNotNull { rec -> all[rec.trackId] }.filterNot { taste.isTrackExcluded(it) }
    }

    override fun getCircadianSlot(hourOfDay: Int): String {
        return NativeBridge.getCircadianSlot(hourOfDay)
    }

    suspend fun logHookTelemetry(trackId: Int, favoriteSeekMs: Long, lyricsDwellSec: Int, volumeFlare: Int): Boolean = withContext(Dispatchers.IO) {
        if (trackId > 0 && !taste.anyTrackIdExcluded(listOf(trackId))) {
            NativeBridge.logHookTelemetry(trackId, favoriteSeekMs, lyricsDwellSec, volumeFlare)
        } else false
    }

    suspend fun recordTrackCooccurrence(trackAId: Int, trackBId: Int): Boolean = withContext(Dispatchers.IO) {
        if (trackAId > 0 && trackBId > 0 && trackAId != trackBId) {
            NativeBridge.recordTrackCooccurrence(trackAId, trackBId)
        } else false
    }

    suspend fun getFavoriteSeekMs(trackId: Int): Long = withContext(Dispatchers.IO) {
        if (trackId > 0) NativeBridge.getFavoriteSeekMs(trackId) else 0L
    }

    suspend fun getCooccurrenceRecommendations(trackId: Int, limit: Int = 10): List<Track> = withContext(Dispatchers.IO) {
        val ids = NativeBridge.getCooccurrenceRecommendations(trackId, limit)
        if (ids.isEmpty()) return@withContext emptyList()
        val all = getAllTracks().associateBy { it.id }
        ids.toList().mapNotNull { all[it] }
    }

    suspend fun getCloudSongRadio(seedTrack: Track, limit: Int = 25): List<Track> = withContext(Dispatchers.IO) {
        val candidates = com.streamify.app.data.network.CandidateAggregator.aggregateCandidates(seedTrack, limit = 100)
        com.streamify.app.data.discovery.ReRanker.scoreAndRankCandidates(
            candidates = candidates,
            seedTrack = seedTrack,
            limit = limit
        )
    }

    override suspend fun getEmergencyComfortTrack(): Track? = withContext(Dispatchers.IO) {
        val liked = getLikedTracks(1)
        if (liked.isNotEmpty()) {
            liked.shuffled().firstOrNull()
        } else {
            getTopPlayedTracks(10).shuffled().firstOrNull()
        }
    }

    fun hardResetState() {
        _allTracks.value = emptyList()
        _likedTracks.value = emptyList()
    }
}

