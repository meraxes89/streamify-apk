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

/** Social surface: track comments, community playlists, friends activity, broadcasts, lyric submissions. */
internal object SupabaseCommunityClient {
    suspend fun fetchTrackComments(trackId: String): List<TrackComment> = withContext(Dispatchers.IO) {
        try {
            val safeId = URLEncoder.encode(trackId, "UTF-8")
            val (code, resp) = SupabaseClient.executeRpc("track_comments?track_id=eq.$safeId&order=timestamp_ms.asc", "GET")

            val comments = mutableListOf<TrackComment>()
            if (code in 200..299 && resp != null) {
                val arr = JSONArray(resp)
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    comments.add(
                        TrackComment(
                            id = o.optString("id"),
                            trackId = o.optString("track_id"),
                            userId = o.optString("user_id"),
                            userName = o.optString("user_name", "Anonymous"),
                            userAvatar = o.optString("user_avatar", ""),
                            timestampMs = o.optLong("timestamp_ms", 0L),
                            commentText = o.optString("comment_text", ""),
                            likesCount = o.optInt("likes_count", 0),
                            parentId = o.optString("parent_id")
                                .takeIf { it.isNotBlank() && it != "null" },
                            createdAt = o.optString("created_at", "")
                        )
                    )
                }
            }
            comments
        } catch (e: Exception) {
            SLog.st("SupabaseClient", "SupabaseClient.fetchTrackComments failed", e)
            emptyList()
        }
    }

    suspend fun postTrackComment(trackId: String, timestampMs: Long, commentText: String, parentId: String? = null): Result<TrackComment> = withContext(Dispatchers.IO) {
        val user = SupabaseClient._currentUser.value ?: return@withContext Result.failure(Exception("Sign in to post comments"))
        try {
            val body = JSONObject().apply {
                put("track_id", trackId)
                put("user_id", user.id)
                put("user_name", user.displayName)
                put("user_avatar", user.avatarUrl)
                put("timestamp_ms", timestampMs)
                put("comment_text", commentText)
                // Gap #34 threading: only serialize a real parent — a JSON
                // null (or missing key) keeps the row a root comment.
                if (parentId != null) put("parent_id", parentId)
            }

            val (code, resp) = SupabaseClient.executeRpc("track_comments", "POST", body.toString(), prefer = "return=representation")

            if (code in 200..299 && resp != null) {
                val arr = JSONArray(resp)
                val o = arr.getJSONObject(0)
                Result.success(
                    TrackComment(
                        id = o.optString("id"),
                        trackId = trackId,
                        userId = user.id,
                        userName = user.displayName,
                        userAvatar = user.avatarUrl,
                        timestampMs = timestampMs,
                        commentText = commentText,
                        parentId = parentId
                    )
                )
            } else {
                Result.failure(Exception("Failed to post comment"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Gap #34 comment upvotes: pushes the desired like count for a comment.
     * Best-effort — callers apply the optimistic state first and never
     * surface a failure toast for a like that did not sync.
     */
    suspend fun updateCommentLikes(commentId: String, newLikesCount: Int): Boolean = withContext(Dispatchers.IO) {
        try {
            val safeId = URLEncoder.encode(commentId, "UTF-8")
            val body = JSONObject().put("likes_count", newLikesCount.coerceAtLeast(0))
            val (code, _) = SupabaseClient.executeRpc(
                "track_comments?id=eq.$safeId",
                "PATCH",
                body.toString(),
                prefer = "return=minimal"
            )
            code in 200..299
        } catch (e: Exception) {
            SLog.st("SupabaseClient", "SupabaseClient.updateCommentLikes failed", e)
            false
        }
    }

    // ========================================================================
    // STREAMIFY JAM / LIVE LISTENING ROOMS (SELF-HEALING & EPHEMERAL SYNC)
    // ========================================================================

    suspend fun fetchCommunityPlaylists(limit: Int = 15): List<CommunityPlaylist> = withContext(Dispatchers.IO) {
        try {
            val (code, resp) = SupabaseClient.executeRpc("playlists?is_public=eq.true&order=likes_count.desc&limit=$limit", "GET")

            val list = mutableListOf<CommunityPlaylist>()
            if (code in 200..299 && resp != null) {
                val arr = JSONArray(resp)
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    list.add(
                        CommunityPlaylist(
                            id = o.optString("id"),
                            userId = o.optString("user_id"),
                            creatorName = "Community Curator",
                            name = o.optString("name", "Public Playlist"),
                            description = o.optString("description", "Curated for Streamify listeners"),
                            coverUrl = o.optString("cover_url", ""),
                            isCollaborative = o.optBoolean("is_collaborative", false),
                            likesCount = o.optInt("likes_count", (12..89).random()),
                            trackCount = (10..45).random()
                        )
                    )
                }
            }
            list
        } catch (e: Exception) {
            SLog.st("SupabaseClient", "SupabaseClient.fetchCommunityPlaylists failed", e)
            emptyList()
        }
    }

    suspend fun fetchFriendsActivity(): List<FriendActivity> = withContext(Dispatchers.IO) {
        try {
            val (code, resp) = SupabaseClient.executeRpc("profiles?is_private=eq.false&limit=6&order=last_active_at.desc", "GET")

            val list = mutableListOf<FriendActivity>()
            if (code in 200..299 && resp != null) {
                val arr = JSONArray(resp)
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val email = o.optString("email")
                    if (email != SupabaseClient._currentUser.value?.email) {
                        list.add(
                            FriendActivity(
                                userId = o.optString("id"),
                                displayName = o.optString("display_name", "Listener"),
                                avatarUrl = o.optString("avatar_url"),
                                trackTitle = "Listening on Streamify",
                                trackArtist = o.optString("favorite_genre", "Top Hits"),
                                coverUrl = "",
                                lastActiveAt = "Active now",
                                // Gap #32: live jam presence when the profile row carries it.
                                sessionCode = o.optString("active_jam_code")
                                    .takeIf { it.isNotBlank() && it != "null" }
                            )
                        )
                    }
                }
            }
            list
        } catch (e: Exception) {
            emptyList()
        }
    }

    suspend fun submitSyncedLyrics(trackId: String, lyricsContent: String): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val safeId = URLEncoder.encode(trackId, "UTF-8")
            val body = JSONObject().apply {
                put("lyrics", lyricsContent)
            }
            val (code, _) = SupabaseClient.executeRpc("tracks?id=eq.$safeId", "PATCH", body.toString())
            Result.success(code in 200..299)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun fetchActiveBroadcasts(): List<String> = withContext(Dispatchers.IO) {
        try {
            val (code, resp) = SupabaseClient.executeRpc("admin_broadcasts?is_active=eq.true&order=created_at.desc&limit=3", "GET")

            val list = mutableListOf<String>()
            if (code in 200..299 && resp != null) {
                val arr = JSONArray(resp)
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val msg = o.optString("message", "")
                    if (msg.isNotBlank()) list.add(msg)
                }
            }
            list
        } catch (e: Exception) {
            emptyList()
        }
    }

    // ========================================================================
    // ADMIN TELEMETRY & COMMAND CENTER METHODS (Protected)
    // ========================================================================

}
