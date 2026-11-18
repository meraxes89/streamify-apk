package com.streamify.app.data.network

import com.streamify.app.data.NativeBridge
import com.streamify.app.data.repository.TrackRepository
import com.streamify.app.data.models.Track
import com.streamify.app.media.audio.AudioDeviceType
import com.streamify.app.util.TimeOfDay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder

data class LastfmSimilarTrack(
    val title: String,
    val artist: String,
    val mbid: String = "",
    val weight: Float = 0.0f,
    val imageUrl: String = ""
)

data class LocalVectorTrack(
    val trackId: Int,
    val title: String,
    val artist: String,
    val vectorScore: Float,
    val bpmMatch: Float
)

data class HybridScore(
    val trackId: Int = 0,
    val title: String,
    val artist: String,
    val lastfmScore: Float = 0.0f,
    val vectorScore: Float = 0.0f,
    val bpmMatch: Float = 0.5f,
    val isLocal: Boolean = false,
    val finalScore: Float = 0.0f
)

class HybridGraphFetcher(
    private val client: OkHttpClient = NetworkEngine.client,
    private val nativeBridge: NativeBridge = NativeBridge,
    private val trackRepository: TrackRepository = TrackRepository
) {
    // Default public developer API key for Last.fm track.getsimilar
    private val LASTFM_API_KEY = "b25b959554ed76058ac220b7b2e0a026"
    private val LASTFM_BASE = "https://ws.audioscrobbler.com/2.0/"

    /**
     * Main entry point: Fetches recommendations by blending
     * Last.fm global crowd graph + on-device NEON SIMD contextual vectors.
     */
    suspend fun getHybridRecommendations(
        currentTrack: Track,
        timeOfDay: TimeOfDay,
        audioDevice: AudioDeviceType,
        limit: Int = 20
    ): List<Track> = coroutineScope {
        // 1. Parallel Fetch: Last.fm API + Local Vector Engine
        val lastfmDeferred = async(Dispatchers.IO) {
            fetchLastfmSimilar(currentTrack)
        }
        val localVectorDeferred = async(Dispatchers.IO) {
            fetchLocalVectorRecommendations(currentTrack, timeOfDay, audioDevice)
        }

        val lastfmResults = lastfmDeferred.await()
        val localResults = localVectorDeferred.await()

        val mergedMap = mutableMapOf<String, HybridScore>()

        // 2. Merge & Deduplicate: Blend global + local scores
        lastfmResults.forEach { similar ->
            val key = "${similar.title.trim()}::${similar.artist.trim()}".lowercase()
            mergedMap[key] = HybridScore(
                title = similar.title,
                artist = similar.artist,
                lastfmScore = similar.weight,
                vectorScore = 0.0f,
                bpmMatch = 0.5f,
                isLocal = false
            )
        }

        localResults.forEach { localTrack ->
            val key = "${localTrack.title.trim()}::${localTrack.artist.trim()}".lowercase()
            val existing = mergedMap[key]
            if (existing != null) {
                // Track exists in BOTH Last.fm crowd graph and Local storage -> Massive confidence boost!
                mergedMap[key] = existing.copy(
                    trackId = localTrack.trackId,
                    vectorScore = localTrack.vectorScore,
                    bpmMatch = localTrack.bpmMatch,
                    isLocal = true
                )
            } else {
                // Only exists locally
                mergedMap[key] = HybridScore(
                    trackId = localTrack.trackId,
                    title = localTrack.title,
                    artist = localTrack.artist,
                    lastfmScore = 0.0f,
                    vectorScore = localTrack.vectorScore,
                    bpmMatch = localTrack.bpmMatch,
                    isLocal = true
                )
            }
        }

        // 3. Final Weighted Scoring
        val scoredList = mergedMap.values.map { score ->
            val combined = when {
                score.lastfmScore > 0f && score.vectorScore > 0f -> {
                    // Cross-confirmed in both global crowd graph and local NEON SIMD
                    (0.40f * score.lastfmScore) + (0.60f * score.vectorScore) + 0.20f
                }
                score.lastfmScore > 0f -> {
                    // Global Last.fm only
                    0.35f * score.lastfmScore
                }
                else -> {
                    // Local vector only
                    0.70f * score.vectorScore + (0.30f * score.bpmMatch)
                }
            }
            score.copy(finalScore = combined)
        }.sortedByDescending { it.finalScore }.take(limit)

        // 4. Map into domain Track models
        val allLocalTracks = trackRepository.allTracks.value
        val localTrackMap = allLocalTracks.associateBy { "${it.title.trim()}::${it.artist.trim()}".lowercase() }

        val finalTracks = mutableListOf<Track>()
        scoredList.forEach { score ->
            val key = "${score.title.trim()}::${score.artist.trim()}".lowercase()
            val matchedLocal = localTrackMap[key] ?: allLocalTracks.firstOrNull { it.id == score.trackId }

            if (matchedLocal != null) {
                finalTracks.add(matchedLocal)
            } else {
                // Synthesize streamable domain Track with ytsearch prefix for reliable JIT resolution
                val searchSpec = "ytsearch:${score.artist} ${score.title}".trim()
                finalTracks.add(
                    Track(
                        id = if (score.trackId != 0) score.trackId else kotlin.math.abs(searchSpec.hashCode()),
                        filepath = searchSpec,
                        title = score.title,
                        artist = score.artist,
                        album = "Streamify Radio",
                        durationSec = 210,
                        bpm = 120f,
                        key = "C",
                        coverArtPath = null,
                        lyricsPath = null,
                        source = "online_stream",
                        isLiked = false
                    )
                )
            }
        }

        finalTracks
    }

    /**
     * Last.fm API: Fetch similar tracks with crowd-sourced weights
     */
    private suspend fun fetchLastfmSimilar(track: Track): List<LastfmSimilarTrack> = withContext(Dispatchers.IO) {
        try {
            val encodedTitle = URLEncoder.encode(track.title, "UTF-8")
            val encodedArtist = URLEncoder.encode(track.artist, "UTF-8")
            val url = "$LASTFM_BASE?method=track.getsimilar&artist=$encodedArtist&track=$encodedTitle&api_key=$LASTFM_API_KEY&format=json&limit=50"

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Streamify-Android-Client/4.0")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext emptyList()
                val bodyStr = response.body?.string() ?: return@withContext emptyList()
                val json = JSONObject(bodyStr)
                val similarObj = json.optJSONObject("similartracks") ?: return@withContext emptyList()
                val trackArray = similarObj.optJSONArray("track") ?: return@withContext emptyList()

                val resultList = mutableListOf<LastfmSimilarTrack>()
                val titles = mutableListOf<String>()
                val artists = mutableListOf<String>()
                val mbids = mutableListOf<String>()
                val weights = mutableListOf<Float>()

                for (i in 0 until trackArray.length()) {
                    val item = trackArray.getJSONObject(i)
                    val sTitle = item.optString("name", "")
                    val artistObj = item.optJSONObject("artist")
                    val sArtist = artistObj?.optString("name", "") ?: item.optString("artist", "")
                    val sMbid = item.optString("mbid", "")
                    val matchWeight = item.optString("match", "0").toFloatOrNull() ?: 0.0f

                    if (sTitle.isNotBlank() && sArtist.isNotBlank()) {
                        resultList.add(
                            LastfmSimilarTrack(
                                title = sTitle,
                                artist = sArtist,
                                mbid = sMbid,
                                weight = matchWeight
                            )
                        )
                        titles.add(sTitle)
                        artists.add(sArtist)
                        mbids.add(sMbid)
                        weights.add(matchWeight)
                    }
                }

                // Cache in SQLite for 0ms future reads
                if (titles.isNotEmpty() && track.id > 0) {
                    nativeBridge.cacheSimilarTracks(
                        track.id,
                        titles.toTypedArray(),
                        artists.toTypedArray(),
                        mbids.toTypedArray(),
                        weights.toFloatArray()
                    )
                }

                resultList
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Local C++ Engine: K-Means filtered NEON SIMD vector search
     */
    private suspend fun fetchLocalVectorRecommendations(
        currentTrack: Track,
        timeOfDay: TimeOfDay,
        audioDevice: AudioDeviceType
    ): List<LocalVectorTrack> = withContext(Dispatchers.Default) {
        val timeWeight = when (timeOfDay) {
            TimeOfDay.MORNING -> 0.85f   // High energy bias
            TimeOfDay.AFTERNOON -> 0.50f // Neutral
            TimeOfDay.EVENING -> 0.30f   // Unwind
            TimeOfDay.NIGHT -> 0.10f     // Mellow/chill bias
        }

        val deviceWeight = when (audioDevice) {
            AudioDeviceType.BLUETOOTH_CAR -> 0.90f        // Up-tempo, punchy
            AudioDeviceType.WIRED_DAC -> 0.30f            // Audiophile dynamic
            AudioDeviceType.SPEAKER -> 0.50f              // Balanced
            AudioDeviceType.BLUETOOTH_HEADPHONES -> 0.60f
        }

        val bpmTarget = nativeBridge.getTargetBpmForTimeSlot(timeOfDay.ordinal)

        val nativeRecs = nativeBridge.getVectorRecommendations(
            currentTrackId = currentTrack.id,
            timeWeight = timeWeight,
            deviceWeight = deviceWeight,
            bpmTarget = bpmTarget,
            limit = 50
        )

        val allLocalTracks = trackRepository.allTracks.value.associateBy { it.id }

        nativeRecs.mapNotNull { rec ->
            val track = allLocalTracks[rec.trackId]
            if (track != null) {
                LocalVectorTrack(
                    trackId = rec.trackId,
                    title = track.title,
                    artist = track.artist,
                    vectorScore = if (rec.vectorScore > 0f) rec.vectorScore else rec.score,
                    bpmMatch = rec.bpmMatchScore
                )
            } else null
        }
    }
}
