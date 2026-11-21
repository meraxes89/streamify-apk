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

/** Listening-stats telemetry: merge, upsert RPCs, batch ingestion, admin leaderboards. */
internal object SupabaseStatsClient {
    suspend fun fetchCloudTelemetryAndMerge(userId: String) = withContext(Dispatchers.IO) {
        try {
            val (code, resp) = SupabaseClient.executeRpc("profiles?id=eq.$userId&select=listening_seconds,total_plays,top_track,favorite_genre,bio", "GET")
            if (code in 200..299 && resp != null) {
                val arr = JSONArray(resp)
                if (arr.length() > 0) {
                    val o = arr.getJSONObject(0)
                    val cloudSec = o.optLong("listening_seconds", 0L)
                    val cloudPlays = o.optInt("total_plays", 0)
                    val topTrack = o.optString("top_track", "")
                    com.streamify.app.data.telemetry.YtStatsTelemetryEngine.mergeCloudTelemetry(cloudSec, cloudPlays, topTrack)
                }
            }
        } catch (e: Exception) {
            SLog.st("SupabaseClient", "SupabaseClient.fetchCloudTelemetryAndMerge failed", e)
        }
    }

    suspend fun upsertTelemetry(payload: TelemetryPayload): Result<Boolean> = withContext(Dispatchers.IO) {
        val user = SupabaseClient._currentUser.value ?: return@withContext Result.failure(Exception("Not logged in"))
        try {
            val body = JSONObject().apply {
                put("id", user.id)
                put("email", user.email)
                put("display_name", user.displayName)
                put("listening_seconds", payload.listeningSeconds)
                put("total_plays", payload.totalPlays)
                if (payload.topTrack.isNotBlank()) put("top_track", payload.topTrack)
                if (payload.favoriteGenre.isNotBlank()) put("favorite_genre", payload.favoriteGenre)
                if (payload.bio.isNotBlank()) put("bio", payload.bio)
                put("last_active_at", payload.lastActiveAt)
            }

            val (code, _) = SupabaseClient.executeRpc("profiles?on_conflict=id", "POST", body.toString(), prefer = "resolution=merge-duplicates")

            val updated = user.copy(
                listeningSeconds = payload.listeningSeconds,
                totalPlays = payload.totalPlays,
                topTrack = payload.topTrack.ifBlank { user.topTrack },
                favoriteGenre = payload.favoriteGenre.ifBlank { user.favoriteGenre },
                bio = payload.bio.ifBlank { user.bio },
                lastActiveAt = payload.lastActiveAt
            )
            SupabaseClient._currentUser.value = updated
            Result.success(code in 200..299)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ========================================================================
    // JAM-STYLE STATS TRANSPORT v2 — monotonic aggregates + per-track deltas
    // ========================================================================

    /**
     * Server-side GREATEST() upsert: a device can never regress cloud truth.
     * Returns false when the migration isn't applied yet (caller falls back).
     */

    suspend fun rpcUpsertTelemetryMonotonic(seconds: Long, plays: Int, topTrack: String): Boolean = withContext(Dispatchers.IO) {
        try {
            if (SupabaseClient.currentUser.value == null) return@withContext false
            val body = JSONObject().apply {
                put("p_listening_seconds", seconds)
                put("p_total_plays", plays)
                put("p_top_track", topTrack)
            }
            val (code, _) = SupabaseClient.executeRpc("rpc/upsert_user_telemetry", "POST", body.toString())
            code in 200..299
        } catch (e: Exception) { false }
    }

    /** Atomic per-track delta increment (concurrent-device safe). */

    suspend fun fetchUserTrackPlays(limit: Int = 50): Result<JSONArray> = withContext(Dispatchers.IO) {
        try {
            val user = SupabaseClient.currentUser.value ?: return@withContext Result.failure(Exception("Not logged in"))
            val (code, resp) = SupabaseClient.executeRpc("user_track_plays?user_id=eq.${user.id}&order=plays.desc&limit=$limit", "GET")
            if (code in 200..299 && resp != null) {
                Result.success(JSONArray(resp))
            } else Result.failure(Exception("track plays fetch: $code"))
        } catch (e: Exception) { Result.failure(e) }
    }

    /**
     * ADMIN: cross-user Top Songs leaderboard from user_track_plays.
     * Backed by get_admin_top_tracks() (security definer, is_admin gated).
     * Returns the raw "tracks" JSON array: {track_sig, plays, seconds,
     * listeners, snapshot{title,artist,coverArtPath,...}}.
     */

    suspend fun ingestTelemetryBatch(events: List<JSONObject>): Boolean = withContext(Dispatchers.IO) {
        val user = SupabaseClient._currentUser.value ?: return@withContext false
        if (events.isEmpty()) return@withContext true
        try {
            val body = JSONArray()
            for (evt in events) {
                body.put(JSONObject().apply {
                    put("user_id", user.id)
                    put("track_sig", evt.optString("track_sig",
                        evt.optString("track_id", "").lowercase()))
                    put("track_title", evt.optString("track_title", ""))
                    put("track_artist", evt.optString("track_artist", ""))
                    put("duration_played_sec", evt.optLong("duration_sec", 0L).toInt())
                    put("completion_ratio", evt.optDouble("completion_ratio", 1.0))
                    put("hour_of_day", evt.optInt("hour_of_day", 12))
                })
            }

            val (code, _) = SupabaseClient.executeRpc("user_play_events", "POST", body.toString(), prefer = "return=minimal")
            code in 200..299
        } catch (e: Exception) {
            false
        }
    }

    suspend fun fetchAdminTopTracks(limit: Int = 20): Result<JSONArray> = withContext(Dispatchers.IO) {
        try {
            val body = JSONObject().put("p_limit", limit).toString()
            val (code, resp) = SupabaseClient.executeRpc("rpc/get_admin_top_tracks", "POST", body)
            if (code in 200..299 && resp != null) {
                val tracks = JSONObject(resp).optJSONArray("tracks") ?: JSONArray()
                Result.success(tracks)
            } else {
                Result.failure(Exception("Top tracks RPC failed: $code"))
            }
        } catch (e: Exception) { Result.failure(e) }
    }

}
