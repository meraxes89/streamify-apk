package com.streamify.app.data.supabase
import com.streamify.app.util.SLog
import android.content.Context
import android.content.SharedPreferences
import com.streamify.app.BuildConfig
import com.streamify.app.data.models.Track
import com.streamify.app.data.repository.TrackRepository
import com.streamify.app.data.repository.EdgeMeshRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import com.streamify.app.data.network.NetworkEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Cloud track + likes sync: upserts, like/unlike, track fetch by id. */
internal object SupabaseTracksClient {
    suspend fun syncCloudLikes(localTracks: List<Track>): List<String> = withContext(Dispatchers.IO) {
        val user = SupabaseClient._currentUser.value ?: return@withContext emptyList()
        try {
            SupabaseClient.ensureProfile(user)

            // 1. Fetch Cloud Likes for this user
            val (code, resp) = SupabaseClient.executeRpc("user_likes?user_id=eq.${user.id}&select=track_id", "GET")

            val cloudLikedIds = mutableListOf<String>()
            if (code in 200..299 && resp != null) {
                val arr = JSONArray(resp)
                for (i in 0 until arr.length()) {
                    val tid = arr.getJSONObject(i).optString("track_id", "")
                    if (tid.isNotBlank()) cloudLikedIds.add(tid)
                }
            }

            // 2. Fetch cloud track details and insert any missing liked tracks into local SQLite
            if (cloudLikedIds.isNotEmpty()) {
                val encodedIds = cloudLikedIds.joinToString(",") { URLEncoder.encode(it, "UTF-8") }
                val (tracksCode, tracksResp) = SupabaseClient.executeRpc("tracks?id=in.($encodedIds)", "GET")

                if (tracksCode in 200..299 && tracksResp != null) {
                    val tracksArr = JSONArray(tracksResp)
                    for (i in 0 until tracksArr.length()) {
                        val to = tracksArr.getJSONObject(i)
                        val title = to.optString("title", "")
                        val artist = to.optString("artist", "")
                        val album = to.optString("album", "Streamify")
                        val streamUrl = to.optString("stream_url", "")
                        val coverUrl = to.optString("cover_url", "")
                        val duration = to.optInt("duration_sec", 0)
                        val bpm = to.optDouble("bpm", 120.0).toFloat()
                        val key = to.optString("key_signature", "C")

                        if (title.isNotBlank()) {
                            val canonicalFilepath = com.streamify.app.data.network.YouTubeStreamResolver.sanitizeForStorage(streamUrl, title, artist)
                            val videoId = com.streamify.app.data.network.YouTubeStreamResolver.extractVideoId(canonicalFilepath, coverUrl)
                            val sanitizedCover = com.streamify.app.data.network.YouTubeStreamResolver.sanitizeCoverUrl(coverUrl, videoId)

                            val localId = com.streamify.app.data.NativeBridge.upsertStreamedTrack(
                                filepath = canonicalFilepath,
                                title = title,
                                artist = artist,
                                album = album,
                                durationSec = duration,
                                coverArtPath = sanitizedCover ?: "",
                                lyricsPath = "",
                                bpm = bpm,
                                key = key
                            )
                            if (localId > 0) {
                                val currentLiked = com.streamify.app.data.NativeBridge.getLikedTracks(1).map { it.id }.toSet()
                                if (!currentLiked.contains(localId)) {
                                    com.streamify.app.data.NativeBridge.toggleLike(1, localId)
                                }
                            }
                        }
                    }
                }
            }

            // 3. Upload un-synced local likes to cloud
            for (track in localTracks.filter { it.isLiked }) {
                val cleanSig = (track.title.trim().lowercase() + "_" + track.artist.trim().lowercase())
                val trackCloudId = "trk_${kotlin.math.abs(cleanSig.hashCode())}"
                if (!cloudLikedIds.contains(trackCloudId)) {
                    upsertCloudTrack(track)
                    addCloudLike(trackCloudId)
                    cloudLikedIds.add(trackCloudId)
                }
            }

            cloudLikedIds
        } catch (e: Exception) {
            SLog.st("SupabaseClient", "SupabaseClient.syncCloudLikes failed", e)
            emptyList()
        }
    }

    suspend fun addCloudLike(trackCloudId: String): Boolean = withContext(Dispatchers.IO) {
        val user = SupabaseClient._currentUser.value ?: return@withContext false
        try {
            val body = JSONObject().apply {
                put("user_id", user.id)
                put("track_id", trackCloudId)
            }
            val (code, _) = SupabaseClient.executeRpc("user_likes", "POST", body.toString(), prefer = "resolution=ignore-duplicates")
            code in 200..299
        } catch (e: Exception) {
            false
        }
    }

    suspend fun removeCloudLike(trackCloudId: String): Boolean = withContext(Dispatchers.IO) {
        val user = SupabaseClient._currentUser.value ?: return@withContext false
        try {
            val (code, _) = SupabaseClient.executeRpc("user_likes?user_id=eq.${user.id}&track_id=eq.$trackCloudId", "DELETE")
            code in 200..299
        } catch (e: Exception) {
            false
        }
    }

    // ========================================================================
    // 16. SUPABASE REALTIME V1 WEBSOCKET CDC (Change Data Capture)
    // ========================================================================

    suspend fun upsertCloudTrack(track: Track): Boolean = withContext(Dispatchers.IO) {
        try {
            val cleanSig = (track.title.trim().lowercase() + "_" + track.artist.trim().lowercase())
            val trackCloudId = "trk_${kotlin.math.abs(cleanSig.hashCode())}"
            
            val canonicalStreamUrl = com.streamify.app.data.network.YouTubeStreamResolver.sanitizeForStorage(track.filepath, track.title, track.artist)
            val videoId = com.streamify.app.data.network.YouTubeStreamResolver.extractVideoId(canonicalStreamUrl, track.coverArtPath)
            val sanitizedCover = com.streamify.app.data.network.YouTubeStreamResolver.sanitizeCoverUrl(track.coverArtPath, videoId)

            val body = JSONObject().apply {
                put("id", trackCloudId)
                put("title", track.title)
                put("artist", track.artist)
                put("album", track.album)
                put("duration_sec", track.durationSec)
                put("cover_url", sanitizedCover ?: "")
                put("stream_url", canonicalStreamUrl)
                put("bpm", track.bpm)
                put("key_signature", track.key)
            }

            val (code, _) = SupabaseClient.executeRpc("tracks", "POST", body.toString(), prefer = "resolution=merge-duplicates")
            code in 200..299
        } catch (e: Exception) {
            false
        }
    }

    suspend fun fetchTrackById(trackId: String): Track? = withContext(Dispatchers.IO) {
        try {
            val safeId = URLEncoder.encode(trackId.trim(), "UTF-8")
            val (code, resp) = SupabaseClient.executeRpc("tracks?id=eq.$safeId", "GET")
            if (code in 200..299 && resp != null) {
                val arr = JSONArray(resp)
                if (arr.length() > 0) {
                    val o = arr.getJSONObject(0)
                    val title = o.optString("title", "")
                    val artist = o.optString("artist", "")
                    val cover = o.optString("cover_url", "")
                    val streamUrl = o.optString("stream_url", "")
                    // 0 (not 180): a fabricated duration would poison CAD-ID
                    // duration-bucket identity for this track across devices.
                    val duration = o.optInt("duration_sec", 0)
                    val bpm = o.optDouble("bpm", 120.0).toFloat()
                    val key = o.optString("key_signature", "C")

                    val videoId = com.streamify.app.data.network.YouTubeStreamResolver.extractVideoId(streamUrl, cover)
                    val canonicalPath = if (videoId != null) "https://www.youtube.com/watch?v=$videoId" else streamUrl

                    return@withContext Track(
                        id = trackId.toIntOrNull() ?: -(trackId.hashCode()),
                        title = title.ifBlank { "Jam Track" },
                        artist = artist.ifBlank { "Artist" },
                        album = o.optString("album", "Streamify Jam"),
                        durationSec = duration,
                        filepath = canonicalPath,
                        coverArtPath = cover.takeIf { it.isNotBlank() },
                        bpm = bpm,
                        key = key,
                        lyricsPath = null,
                        source = "cloud_jam",
                        ytmVideoId = videoId
                    )
                }
            }
            null
        } catch (e: Exception) {
            null
        }
    }

    // ========================================================================
    // PGVECTOR CLOUD AI RECOMMENDATIONS (SONG RADIO)
    // ========================================================================

}
