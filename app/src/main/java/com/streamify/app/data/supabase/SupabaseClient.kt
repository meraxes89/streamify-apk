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

/**
 * Public facade over the Supabase-backed backend domains.
 *
 * This object owns shared session state, authentication and the HTTP/RPC
 * plumbing; domain logic lives in the internal Supabase*Client objects in
 * this package (realtime, stats, tracks, community, jam, admin, edge mesh,
 * playlist sync). All external call sites keep using SupabaseClient.* —
 * the facade delegates with identical signatures.
 */
object SupabaseClient {

    private var prefs: SharedPreferences? = null

    internal val _currentUser = MutableStateFlow<UserProfile?>(null)

    val currentUser: StateFlow<UserProfile?> = _currentUser.asStateFlow()

    internal val _accessToken = MutableStateFlow<String?>(null)

    val accessToken: StateFlow<String?> = _accessToken.asStateFlow()

    val liveProfileUpdates = MutableSharedFlow<JSONObject>(extraBufferCapacity = 64)

    val isAdmin: Boolean
        get() = _currentUser.value?.isAdmin == true ||
                _currentUser.value?.email?.contains("sireenyadav", ignoreCase = true) == true ||
                _currentUser.value?.email.equals(BuildConfig.ADMIN_EMAIL, ignoreCase = true) ||
                _currentUser.value?.displayName?.contains("sireen", ignoreCase = true) == true

    fun init(context: Context) {
        if (prefs == null) {
            prefs = context.getSharedPreferences("supabase_session", Context.MODE_PRIVATE)
            val savedToken = prefs?.getString("access_token", null)
            val savedUserId = prefs?.getString("user_id", null)
            val savedEmail = prefs?.getString("user_email", null)
            val savedName = prefs?.getString("display_name", null)
            val savedAvatar = prefs?.getString("avatar_url", null)
            val savedBio = prefs?.getString("bio", "Music lover on Streamify 🎧")
            val savedGenre = prefs?.getString("fav_genre", "All")
            val savedIsAdmin = prefs?.getBoolean("is_admin", false) ?: false

            if (!savedToken.isNullOrBlank() && !savedEmail.isNullOrBlank()) {
                _accessToken.value = savedToken
                val isAdminUser = savedIsAdmin ||
                        savedEmail.contains("sireenyadav", ignoreCase = true) ||
                        savedEmail.equals(BuildConfig.ADMIN_EMAIL, ignoreCase = true) ||
                        (savedName?.contains("sireen", ignoreCase = true) == true)
                val userProf = UserProfile(
                    id = savedUserId ?: "",
                    email = savedEmail,
                    displayName = savedName ?: savedEmail.substringBefore("@"),
                    avatarUrl = savedAvatar ?: "",
                    bio = savedBio ?: "Music lover on Streamify 🎧",
                    favoriteGenre = savedGenre ?: "All",
                    isAdmin = isAdminUser
                )
                _currentUser.value = userProf
                if (!savedUserId.isNullOrBlank()) {
                    CoroutineScope(Dispatchers.IO).launch {
                        SupabaseStatsClient.fetchCloudTelemetryAndMerge(savedUserId)
                    }
                }
            }
        }
    }

    val supabaseHttpClient: okhttp3.OkHttpClient by lazy {
        NetworkEngine.client.newBuilder()
            .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(6, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    fun executeHttpRequest(
        url: String,
        method: String = "GET",
        headers: Map<String, String> = emptyMap(),
        jsonBody: String? = null
    ): Pair<Int, String?> {
        val reqBuilder = Request.Builder().url(url)
        headers.forEach { (k, v) -> reqBuilder.header(k, v) }

        val body = jsonBody?.toRequestBody(JSON_MEDIA_TYPE)
        when (method.uppercase()) {
            "GET" -> reqBuilder.get()
            "POST" -> reqBuilder.post(body ?: "".toRequestBody(JSON_MEDIA_TYPE))
            "PUT" -> reqBuilder.put(body ?: "".toRequestBody(JSON_MEDIA_TYPE))
            "PATCH" -> reqBuilder.patch(body ?: "".toRequestBody(JSON_MEDIA_TYPE))
            "DELETE" -> if (body != null) reqBuilder.delete(body) else reqBuilder.delete()
        }

        return try {
            supabaseHttpClient.newCall(reqBuilder.build()).execute().use { response ->
                Pair(response.code, response.body?.string())
            }
        } catch (e: Exception) {
            Pair(-1, null)
        }
    }

    fun executeRpc(
        endpoint: String,
        method: String = "POST",
        body: String? = null,
        prefer: String? = null,
        requireAuth: Boolean = true
    ): Pair<Int, String?> {
        val url = if (endpoint.startsWith("http")) endpoint else "${BuildConfig.SUPABASE_URL}/rest/v1/$endpoint"
        val authToken = if (requireAuth) getAuthToken() else BuildConfig.SUPABASE_ANON_KEY
        val headers = mutableMapOf(
            "apikey" to BuildConfig.SUPABASE_ANON_KEY,
            "Authorization" to "Bearer $authToken",
            "Content-Type" to "application/json"
        )
        if (prefer != null) {
            headers["Prefer"] = prefer
        }
        return executeHttpRequest(url, method, headers, body)
    }

    fun isJwtExpired(jwt: String?): Boolean {
        if (jwt.isNullOrBlank()) return true
        try {
            val parts = jwt.split(".")
            if (parts.size >= 2) {
                val decodedBytes = android.util.Base64.decode(parts[1], android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING)
                val payloadJson = String(decodedBytes, Charsets.UTF_8)
                val json = JSONObject(payloadJson)
                val exp = json.optLong("exp", 0L)
                if (exp > 0) {
                    val nowSec = System.currentTimeMillis() / 1000L
                    return nowSec >= (exp - 60L) // Treat as expired if within 60s of expiration
                }
            }
        } catch (e: Exception) {
            // ignore decoding errors
        }
        return false
    }

    suspend fun refreshSession(): Boolean = withContext(Dispatchers.IO) {
        val rt = prefs?.getString("refresh_token", null)
        if (rt.isNullOrBlank()) {
            _accessToken.value = null
            prefs?.edit()?.remove("access_token")?.apply()
            return@withContext false
        }
        try {
            val url = "${BuildConfig.SUPABASE_URL}/auth/v1/token?grant_type=refresh_token"
            val headers = mapOf(
                "apikey" to BuildConfig.SUPABASE_ANON_KEY,
                "Content-Type" to "application/json"
            )
            val body = JSONObject().apply {
                put("refresh_token", rt)
            }

            val (code, respStr) = executeHttpRequest(url, "POST", headers, body.toString())
            if (code in 200..299 && respStr != null) {
                val json = JSONObject(respStr)
                val newToken = json.getString("access_token")
                val newRefreshToken = json.optString("refresh_token", rt)

                _accessToken.value = newToken
                prefs?.edit()?.apply {
                    putString("access_token", newToken)
                    putString("refresh_token", newRefreshToken)
                    apply()
                }
                true
            } else {
                _accessToken.value = null
                prefs?.edit()?.remove("access_token")?.remove("refresh_token")?.apply()
                false
            }
        } catch (e: Exception) {
            _accessToken.value = null
            false
        }
    }

    internal fun getAuthToken(): String {
        val token = _accessToken.value
        if (token.isNullOrBlank() || isJwtExpired(token)) {
            return BuildConfig.SUPABASE_ANON_KEY
        }
        return token
    }

    // ========================================================================
    // AUTHENTICATION & PROFILE
    // ========================================================================

    suspend fun signInWithGoogleIdToken(idToken: String): Result<UserProfile> = withContext(Dispatchers.IO) {
        try {
            val url = "${BuildConfig.SUPABASE_URL}/auth/v1/token?grant_type=id_token"
            val headers = mapOf(
                "apikey" to BuildConfig.SUPABASE_ANON_KEY,
                "Content-Type" to "application/json"
            )

            val body = JSONObject().apply {
                put("provider", "google")
                put("id_token", idToken)
            }

            val (code, respStr) = executeHttpRequest(url, "POST", headers, body.toString())

            if (code in 200..299 && respStr != null) {
                val json = JSONObject(respStr)
                val token = json.getString("access_token")
                val refreshToken = json.optString("refresh_token", "")
                val userObj = json.getJSONObject("user")
                val userId = userObj.getString("id")
                val email = userObj.optString("email", "")
                val meta = userObj.optJSONObject("user_metadata")
                val name = meta?.optString("full_name", meta.optString("name", email.substringBefore("@"))) ?: email.substringBefore("@")
                val avatar = meta?.optString("avatar_url", meta.optString("picture", "")) ?: ""

                val isAdminUser = email.contains("sireenyadav", ignoreCase = true) ||
                        email.equals(BuildConfig.ADMIN_EMAIL, ignoreCase = true) ||
                        name.contains("sireen", ignoreCase = true)

                val profile = UserProfile(
                    id = userId,
                    email = email,
                    displayName = name,
                    avatarUrl = avatar,
                    isAdmin = isAdminUser
                )

                _accessToken.value = token
                _currentUser.value = profile

                prefs?.edit()?.apply {
                    putString("access_token", token)
                    if (refreshToken.isNotBlank()) putString("refresh_token", refreshToken)
                    putString("user_id", userId)
                    putString("user_email", email)
                    putString("display_name", name)
                    putString("avatar_url", avatar)
                    putBoolean("is_admin", isAdminUser)
                    apply()
                }

                ensureProfile(profile)
                SupabaseStatsClient.fetchCloudTelemetryAndMerge(userId)

                Result.success(profile)
            } else {
                Result.failure(Exception("Auth failed: $respStr"))
            }
        } catch (e: Exception) {
            SLog.st("SupabaseClient", "SupabaseClient.signInWithGoogleIdToken failed", e)
            Result.failure(e)
        }
    }

    suspend fun ensureProfile(user: UserProfile) = withContext(Dispatchers.IO) {
        try {
            val body = JSONObject().apply {
                put("id", user.id)
                put("email", user.email)
                put("display_name", user.displayName)
                put("avatar_url", user.avatarUrl)
                put("bio", user.bio)
                put("favorite_genre", user.favoriteGenre)
                put("is_admin", user.isAdmin)
            }
            executeRpc("profiles", "POST", body.toString(), prefer = "resolution=merge-duplicates")
        } catch (e: Exception) {
            SLog.st("SupabaseClient", "SupabaseClient.ensureProfile failed", e)
        }
    }

    suspend fun updateProfile(displayName: String, avatarUrl: String, bio: String, favGenre: String): Result<Boolean> = withContext(Dispatchers.IO) {
        val user = _currentUser.value ?: return@withContext Result.failure(Exception("Not logged in"))
        try {
            val body = JSONObject().apply {
                put("display_name", displayName)
                if (avatarUrl.isNotBlank()) put("avatar_url", avatarUrl)
                put("bio", bio)
                put("favorite_genre", favGenre)
            }

            val (code, _) = executeRpc("profiles?id=eq.${user.id}", "PATCH", body.toString(), prefer = "return=minimal")

            val updated = user.copy(displayName = displayName, avatarUrl = avatarUrl.ifBlank { user.avatarUrl }, bio = bio, favoriteGenre = favGenre)
            _currentUser.value = updated
            prefs?.edit()?.apply {
                putString("display_name", displayName)
                putString("avatar_url", updated.avatarUrl)
                putString("bio", bio)
                putString("fav_genre", favGenre)
                apply()
            }

            Result.success(code in 200..299)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun signOut() {
        _accessToken.value = null
        _currentUser.value = null
        prefs?.edit()?.clear()?.apply()
    }

    // ========================================================================
    // CLOUD LIKED SONGS TWO-WAY SYNC
    // ========================================================================

    private val schemaColumnBlacklist = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.ConcurrentHashMap.KeySetView<String, Boolean>>()

    private val PGRST204_REGEX = java.util.regex.Pattern.compile("Could not find the '(\\w+)' column", java.util.regex.Pattern.CASE_INSENSITIVE)

    internal suspend fun executeAdaptivePostgrestRequest(
        url: URL,
        method: String,
        table: String,
        initialBody: JSONObject?,
        prefer: String = "return=representation"
    ): Pair<Int, String> {
        val sanitized = if (initialBody != null) JSONObject(initialBody.toString()) else null

        // 1. Pre-strip known missing columns from cache
        if (sanitized != null) {
            schemaColumnBlacklist[table]?.forEach { badColumn ->
                sanitized.remove(badColumn)
            }
        }

        val maxRetries = (sanitized?.length() ?: 1) + 3
        var attempts = 0

        while (attempts < maxRetries) {
            attempts++
            val headers = mutableMapOf(
                "apikey" to BuildConfig.SUPABASE_ANON_KEY,
                "Authorization" to "Bearer ${getAuthToken()}",
                "Content-Type" to "application/json",
                "Prefer" to prefer
            )

            val (code, respText) = executeHttpRequest(url.toString(), method, headers, sanitized?.toString())
            val text = respText ?: ""

            if (code in 200..299) {
                return Pair(code, text)
            }

            // JWT Expired / Auth error handling
            if (text.contains("JWT expired", ignoreCase = true) || code == 401 || text.contains("PGRST503")) {
                refreshSession()
                continue
            }

            // PGRST204 Missing Column interceptor
            if (sanitized != null && (code in 400..404 || text.contains("PGRST204") || text.contains("Could not find the", ignoreCase = true))) {
                val matcher = PGRST204_REGEX.matcher(text)
                if (matcher.find()) {
                    val missingCol = matcher.group(1)
                    if (!missingCol.isNullOrBlank() && sanitized.has(missingCol)) {
                        schemaColumnBlacklist.getOrPut(table) { java.util.concurrent.ConcurrentHashMap.newKeySet() }.add(missingCol)
                        sanitized.remove(missingCol)
                        continue // Retry immediately in-flight with healed payload
                    }
                }
            }

            return Pair(code, text)
        }
        return Pair(400, "Adaptive retry limit reached")
    }


    // ── Domain delegations (implementation lives in the domain clients) ──

    val isRealtimeConnected get() = SupabaseRealtimeClient.isRealtimeConnected
    fun startRealtimeSync(userId: String) = SupabaseRealtimeClient.startRealtimeSync(userId)
    fun stopRealtimeSync() = SupabaseRealtimeClient.stopRealtimeSync()
    suspend fun fetchDeltasSince(sinceTimestampMs: Long): List<String> = SupabaseRealtimeClient.fetchDeltasSince(sinceTimestampMs)

    suspend fun fetchCloudTelemetryAndMerge(userId: String) = SupabaseStatsClient.fetchCloudTelemetryAndMerge(userId)
    suspend fun upsertTelemetry(payload: TelemetryPayload): Result<Boolean> = SupabaseStatsClient.upsertTelemetry(payload)
    suspend fun rpcUpsertTelemetryMonotonic(seconds: Long, plays: Int, topTrack: String): Boolean = SupabaseStatsClient.rpcUpsertTelemetryMonotonic(seconds, plays, topTrack)
    suspend fun fetchUserTrackPlays(limit: Int = 50): Result<JSONArray> = SupabaseStatsClient.fetchUserTrackPlays(limit)
    suspend fun ingestTelemetryBatch(events: List<JSONObject>): Boolean = SupabaseStatsClient.ingestTelemetryBatch(events)
    suspend fun fetchAdminTopTracks(limit: Int = 20): Result<JSONArray> = SupabaseStatsClient.fetchAdminTopTracks(limit)

    suspend fun syncCloudLikes(localTracks: List<Track>): List<String> = SupabaseTracksClient.syncCloudLikes(localTracks)
    suspend fun addCloudLike(trackCloudId: String): Boolean = SupabaseTracksClient.addCloudLike(trackCloudId)
    suspend fun removeCloudLike(trackCloudId: String): Boolean = SupabaseTracksClient.removeCloudLike(trackCloudId)
    suspend fun upsertCloudTrack(track: Track): Boolean = SupabaseTracksClient.upsertCloudTrack(track)
    suspend fun fetchTrackById(trackId: String): Track? = SupabaseTracksClient.fetchTrackById(trackId)

    suspend fun fetchTrackComments(trackId: String): List<TrackComment> = SupabaseCommunityClient.fetchTrackComments(trackId)
    suspend fun postTrackComment(trackId: String, timestampMs: Long, commentText: String, parentId: String? = null): Result<TrackComment> = SupabaseCommunityClient.postTrackComment(trackId, timestampMs, commentText, parentId)
    suspend fun updateCommentLikes(commentId: String, newLikesCount: Int): Boolean = SupabaseCommunityClient.updateCommentLikes(commentId, newLikesCount)
    suspend fun fetchCommunityPlaylists(limit: Int = 15): List<CommunityPlaylist> = SupabaseCommunityClient.fetchCommunityPlaylists(limit)
    suspend fun fetchFriendsActivity(): List<FriendActivity> = SupabaseCommunityClient.fetchFriendsActivity()
    suspend fun submitSyncedLyrics(trackId: String, lyricsContent: String): Result<Boolean> = SupabaseCommunityClient.submitSyncedLyrics(trackId, lyricsContent)
    suspend fun fetchActiveBroadcasts(): List<String> = SupabaseCommunityClient.fetchActiveBroadcasts()


    suspend fun getAdminTelemetry(): Result<AdminTelemetry> = SupabaseAdminClient.getAdminTelemetry()
    suspend fun setUserAdminRole(targetUserId: String, isAdmin: Boolean): Result<Boolean> = SupabaseAdminClient.setUserAdminRole(targetUserId, isAdmin)
    suspend fun terminateJamSessionAdmin(sessionId: String): Result<Boolean> = SupabaseAdminClient.terminateJamSessionAdmin(sessionId)
    suspend fun deleteCommentAdmin(commentId: String): Result<Boolean> = SupabaseAdminClient.deleteCommentAdmin(commentId)
    suspend fun getAdminJamSessions(): Result<List<AdminJamSession>> = SupabaseAdminClient.getAdminJamSessions()
    suspend fun getAdminRecentComments(limit: Int = 50): Result<List<AdminCommentItem>> = SupabaseAdminClient.getAdminRecentComments(limit)
    suspend fun postAdminBroadcast(message: String): Result<Boolean> = SupabaseAdminClient.postAdminBroadcast(message)

    suspend fun submitEdgeResult(
        taskId: String,
        deviceId: String,
        bpm: Float,
        key: String,
        embedding: FloatArray?,
        proof: String,
        bandwidthSavedBytes: Long = 0L
    ): Result<Boolean> = SupabaseEdgeMeshClient.submitEdgeResult(taskId, deviceId, bpm, key, embedding, proof, bandwidthSavedBytes)
    suspend fun getAdminEdgeComputeStats(): Result<AdminEdgeMeshStats> = SupabaseEdgeMeshClient.getAdminEdgeComputeStats()

    suspend fun syncPlaylistUpsert(playlist: com.streamify.app.data.repository.Playlist): Result<Unit> = SupabasePlaylistSyncClient.syncPlaylistUpsert(playlist)
    suspend fun syncPlaylistDelete(playlistId: String): Result<Unit> = SupabasePlaylistSyncClient.syncPlaylistDelete(playlistId)
    suspend fun syncPlaylistTrackAdd(playlistId: String, trackId: Int, positionIdx: Double): Result<Unit> = SupabasePlaylistSyncClient.syncPlaylistTrackAdd(playlistId, trackId, positionIdx)
    suspend fun syncPlaylistTrackRemove(playlistId: String, trackId: Int): Result<Unit> = SupabasePlaylistSyncClient.syncPlaylistTrackRemove(playlistId, trackId)
}
