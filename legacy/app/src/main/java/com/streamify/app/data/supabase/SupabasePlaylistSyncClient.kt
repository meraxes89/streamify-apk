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

/** Playlist cloud sync: adaptive PostgREST upsert/delete/track add/remove. */
internal object SupabasePlaylistSyncClient {
    suspend fun syncPlaylistUpsert(playlist: com.streamify.app.data.repository.Playlist): Result<Unit> = withContext(Dispatchers.IO) {
        val user = SupabaseClient._currentUser.value ?: return@withContext Result.success(Unit)
        try {
            val url = URL("${BuildConfig.SUPABASE_URL}/rest/v1/playlists?on_conflict=id")
            val body = JSONObject().apply {
                put("id", playlist.id)
                put("user_id", user.id)
                put("name", playlist.name)
                put("description", playlist.description)
                put("is_system", playlist.isSystem)
                put("is_deleted", playlist.isDeleted)
                put("version", playlist.version)
                if (playlist.coverUrl != null) put("cover_url", playlist.coverUrl)
            }
            val (code, text) = SupabaseClient.executeAdaptivePostgrestRequest(
                url = url,
                method = "POST",
                table = "playlists",
                initialBody = body,
                prefer = "resolution=merge-duplicates"
            )
            if (code in 200..299) Result.success(Unit) else Result.failure(Exception("HTTP $code: $text"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun syncPlaylistDelete(playlistId: String): Result<Unit> = withContext(Dispatchers.IO) {
        SupabaseClient._currentUser.value ?: return@withContext Result.success(Unit)
        try {
            val url = URL("${BuildConfig.SUPABASE_URL}/rest/v1/playlists?id=eq.$playlistId")
            val body = JSONObject().apply {
                put("is_deleted", true)
            }
            val (code, text) = SupabaseClient.executeAdaptivePostgrestRequest(
                url = url,
                method = "PATCH",
                table = "playlists",
                initialBody = body
            )
            if (code in 200..299) Result.success(Unit) else Result.failure(Exception("HTTP $code: $text"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun syncPlaylistTrackAdd(playlistId: String, trackId: Int, positionIdx: Double): Result<Unit> = withContext(Dispatchers.IO) {
        SupabaseClient._currentUser.value ?: return@withContext Result.success(Unit)
        try {
            val url = URL("${BuildConfig.SUPABASE_URL}/rest/v1/playlist_tracks?on_conflict=playlist_id,track_id")
            val body = JSONObject().apply {
                put("playlist_id", playlistId)
                put("track_id", trackId.toString())
                put("position_idx", positionIdx)
                put("is_deleted", false)
            }
            val (code, text) = SupabaseClient.executeAdaptivePostgrestRequest(
                url = url,
                method = "POST",
                table = "playlist_tracks",
                initialBody = body,
                prefer = "resolution=merge-duplicates"
            )
            if (code in 200..299) Result.success(Unit) else Result.failure(Exception("HTTP $code: $text"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun syncPlaylistTrackRemove(playlistId: String, trackId: Int): Result<Unit> = withContext(Dispatchers.IO) {
        SupabaseClient._currentUser.value ?: return@withContext Result.success(Unit)
        try {
            val url = URL("${BuildConfig.SUPABASE_URL}/rest/v1/playlist_tracks?playlist_id=eq.$playlistId&track_id=eq.$trackId")
            val body = JSONObject().apply {
                put("is_deleted", true)
            }
            val (code, text) = SupabaseClient.executeAdaptivePostgrestRequest(
                url = url,
                method = "PATCH",
                table = "playlist_tracks",
                initialBody = body
            )
            if (code in 200..299) Result.success(Unit) else Result.failure(Exception("HTTP $code: $text"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

}
