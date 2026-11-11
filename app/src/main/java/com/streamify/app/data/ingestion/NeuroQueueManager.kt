package com.streamify.app.data.ingestion
import com.streamify.app.data.models.Track
import com.streamify.app.data.network.YouTubeStreamResolver
import com.streamify.app.data.youtube.PlaylistLinkScraper
import kotlinx.coroutines.*
import java.util.Calendar
import com.streamify.app.util.SLog
import com.streamify.app.data.discovery.TasteLane
import com.streamify.app.data.discovery.toTasteTrack
import com.streamify.app.data.discovery.toTrack

/**
 * Psychological Neuro-Acoustic Queue Manager
 *
 * Coordinates Tri-Engine Candidate Sourcing (Spotify, YouTube, Local Liked) and
 * delegates sub-millisecond scoring to Rust's NeuroQueueEngine with a 5-slot cinematic micro-arc.
 */
object NeuroQueueManager {

    const val STATE_FLOW = 0       // Normal playback (>80% listened): 45% Spotify : 40% YouTube : 15% Liked
    const val STATE_DISTRESS = 1   // Fast skip (<10s): 10% Spotify : 0% YouTube : 90% Liked (Emergency Reset)
    const val STATE_HYPNOSIS = 2   // Passive dwell (3+ consecutive songs): 35% Spotify : 55% YouTube : 10% Liked
    const val STATE_IMPATIENCE = 3 // Scrubbing / Fast-Forward: 50% Spotify : 40% YouTube : 10% Liked (Energy >= 0.75)
    const val STATE_OBSESSION = 4  // Loop / Repeat track: 70% Spotify : 20% YouTube : 10% Liked (Sim >= 0.90)

    @Volatile
    private var currentBrainState = STATE_FLOW

    @Volatile
    private var consecutivePassiveCount = 0

    @Volatile
    private var lastTrackStartTimeMs = 0L

    fun onTrackStarted(track: Track) {
        lastTrackStartTimeMs = System.currentTimeMillis()
    }

    fun onTrackFinished(track: Track, listenedMs: Long, totalDurationMs: Long, isRepeat: Boolean) {
        if (isRepeat) {
            currentBrainState = STATE_OBSESSION
            consecutivePassiveCount = 0
            return
        }

        val fraction = if (totalDurationMs > 0) listenedMs.toFloat() / totalDurationMs else 0f
        if (listenedMs < 10_000L && totalDurationMs >= 30_000L) {
            // Fast Skip (<10s) -> Emergency Reset
            currentBrainState = STATE_DISTRESS
            consecutivePassiveCount = 0
        } else if (fraction >= 0.80f) {
            consecutivePassiveCount++
            currentBrainState = if (consecutivePassiveCount >= 3) STATE_HYPNOSIS else STATE_FLOW
        }
    }

    fun onUserScrubbing() {
        currentBrainState = STATE_IMPATIENCE
        consecutivePassiveCount = 0
    }

    fun getActiveBrainState(): Int = currentBrainState

    /**
     * Synthesizes and scores a 5-slot micro-arc queue using the Tri-Engine pipeline in pure Rust
     */
    suspend fun generateAdaptiveQueue(
        seedTrack: Track,
        likedTracks: List<Track>,
        targetCount: Int = 15
    ): List<Track> = withContext(Dispatchers.IO) {
        val nowSec = System.currentTimeMillis() / 1000L
        val hourOfDay = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)

        // 1. Parallel Tri-Engine Candidate Fetching
        val spotifyDeferred = async { fetchSpotifyCandidates(seedTrack) }
        val youtubeDeferred = async { fetchYouTubeCandidates(seedTrack) }

        val spotifyTracks = try { spotifyDeferred.await() } catch (_: Exception) { emptyList() }
        val youtubeTracks = try { youtubeDeferred.await() } catch (_: Exception) { emptyList() }

        // 2. Build taste-lane candidates (the unified engine lane —
        // WS-D D3: the raw JSON/JNI twin was deleted with its C-ABI).
        // D7: the exclusion guard is consulted at the boundary; the
        // engine then filters at score time.
        val guard = com.streamify.app.data.discovery.TasteProfileGuard.index()
        val seed = seedTrack.toTasteTrack(source = 1, guard = guard, lastPlayedSec = nowSec)
        val tasteCandidates = mutableListOf<TasteLane.TasteTrack>()

        // Source 0: Liked Tracks (Strict Acoustic & Artist Affinity Gated - Max 5 tracks)
        val filteredLiked = likedTracks.filter { t ->
            if (t.id == seedTrack.id || (t.title.equals(seedTrack.title, ignoreCase = true) && t.artist.equals(seedTrack.artist, ignoreCase = true))) {
                false
            } else {
                val sameArtist = t.artist.equals(seedTrack.artist, ignoreCase = true) ||
                                 t.artist.contains(seedTrack.artist, ignoreCase = true) ||
                                 seedTrack.artist.contains(t.artist, ignoreCase = true)
                val bpmDiff = if (seedTrack.bpm > 0f && t.bpm > 0f) kotlin.math.abs(seedTrack.bpm - t.bpm) else 0f
                // Strict Gate: Must share artist OR have tight tempo proximity (<= 15 BPM) to prevent out-of-context vibe clashes
                sameArtist || (seedTrack.bpm > 0f && t.bpm > 0f && bpmDiff <= 15f)
            }
        }.take(8)

        filteredLiked.forEach { t ->
            val sameArtist = t.artist.equals(seedTrack.artist, ignoreCase = true) ||
                             t.artist.contains(seedTrack.artist, ignoreCase = true) ||
                             seedTrack.artist.contains(t.artist, ignoreCase = true)
            val bpmDiff = if (seedTrack.bpm > 0f && t.bpm > 0f) kotlin.math.abs(seedTrack.bpm - t.bpm) else 0f
            val computedSim = if (sameArtist) 0.95 else (0.75 - (bpmDiff / 50.0).coerceIn(0.0, 0.25))

            tasteCandidates.add(
                TasteLane.TasteTrack(
                    id = "liked_${t.id}",
                    title = t.title,
                    artist = t.artist,
                    album = t.album,
                    source = 0,
                    bpm = if (t.bpm > 0f) t.bpm else 120f,
                    key = t.key.ifBlank { "8A" },
                    energy = 0.65f,
                    valence = 0.7f,
                    acousticSim = computedSim.toFloat(),
                    userAffinity = if (sameArtist) 0.85f else 0.40f,
                    excludedFromTaste = guard.isTrackExcluded(t)
                )
            )
        }

        // Source 1: Spotify Candidates (Vibe & Genre continuity)
        spotifyTracks.take(30).forEachIndexed { idx, t ->
            tasteCandidates.add(
                TasteLane.TasteTrack(
                    id = "spotify_${idx}_${t.title.hashCode()}",
                    title = t.title,
                    artist = t.artist,
                    album = t.album,
                    source = 1,
                    bpm = if (t.bpm > 0f) t.bpm else (seedTrack.bpm.takeIf { it > 0f } ?: 120f),
                    key = t.key.ifBlank { seedTrack.key.ifBlank { "8A" } },
                    energy = 0.75f,
                    valence = 0.70f,
                    acousticSim = 0.95f,
                    userAffinity = 0.60f
                )
            )
        }

        // Source 2: YouTube Candidates (Discovery & Seed Continuum)
        youtubeTracks.take(30).forEachIndexed { idx, t ->
            tasteCandidates.add(
                TasteLane.TasteTrack(
                    id = "yt_${idx}_${t.title.hashCode()}",
                    title = t.title,
                    artist = t.artist,
                    album = t.album,
                    source = 2,
                    bpm = if (t.bpm > 0f) t.bpm else (seedTrack.bpm.takeIf { it > 0f } ?: 120f),
                    key = t.key.ifBlank { seedTrack.key.ifBlank { "8A" } },
                    energy = 0.75f,
                    valence = 0.70f,
                    acousticSim = 0.88f,
                    userAffinity = 0.50f
                )
            )
        }

        // 3. Delegate to the unified Rust TasteEngine lane (WS-D D3):
        // every row returns with its D4 reason string attached.
        val brainState = when (currentBrainState) {
            STATE_DISTRESS -> TasteLane.BrainStateKt.DISTRESS
            STATE_HYPNOSIS -> TasteLane.BrainStateKt.HYPNOSIS
            STATE_IMPATIENCE -> TasteLane.BrainStateKt.IMPATIENCE
            STATE_OBSESSION -> TasteLane.BrainStateKt.OBSESSION
            else -> TasteLane.BrainStateKt.FLOW
        }
        val rows = TasteLane.generateRadioQueue(
            seed = seed,
            candidates = tasteCandidates,
            state = brainState,
            nowSec = nowSec,
            hourOfDay = hourOfDay,
            targetCount = targetCount,
            profile = TasteLane.CircadianProfileKt.classic()
        )
        if (rows.isNotEmpty()) {
            return@withContext rows.map { it.toTrack(sourceTag = "neuro_queue") }
        }

        // Fallback: Interleaved combination
        val fallback = mutableListOf<Track>()
        val all = (likedTracks.take(5) + spotifyTracks.take(5) + youtubeTracks.take(5))
        fallback.addAll(all.distinctBy { (it.title + it.artist).lowercase() }.take(targetCount))
        return@withContext fallback
    }

    private suspend fun fetchSpotifyCandidates(seedTrack: Track): List<Track> = withContext(Dispatchers.IO) {
        try {
            // High-speed embed query using seed track artist search
            val query = "${seedTrack.artist} ${seedTrack.title}".trim()
            val scraped = PlaylistLinkScraper.scrapePlaylist("https://open.spotify.com/search/${java.net.URLEncoder.encode(query, "UTF-8")}")
            scraped.tracks.map { st ->
                Track(
                    id = (st.title + st.artist).hashCode(),
                    title = st.title,
                    artist = st.artist,
                    durationSec = st.durationSec,
                    source = "spotify_vibe"
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private suspend fun fetchYouTubeCandidates(seedTrack: Track): List<Track> = withContext(Dispatchers.IO) {
        try {
            val vid = YouTubeStreamResolver.extractVideoId(seedTrack.filepath)
            val mixId = if (!vid.isNullOrBlank()) "RDAMVM$vid" else ""
            if (mixId.isNotBlank()) {
                val scraped = PlaylistLinkScraper.scrapePlaylist("https://music.youtube.com/playlist?list=$mixId")
                scraped.tracks.map { st ->
                    Track(
                        id = (st.title + st.artist).hashCode(),
                        title = st.title,
                        artist = st.artist,
                        durationSec = st.durationSec,
                        source = "youtube_discovery"
                    )
                }
            } else {
                emptyList()
            }
        } catch (_: Exception) {
            emptyList()
        }
    }
}
