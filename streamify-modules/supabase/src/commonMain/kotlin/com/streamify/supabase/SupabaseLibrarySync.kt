package com.streamify.supabase

import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * High-performance Supabase Library Sync Engine.
 * Synchronizes user likes, tracks metadata, and custom playlists across all devices.
 */
object SupabaseLibrarySync {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    /**
     * Synchronizes a liked / unliked track with Supabase.
     * When liked: upserts track metadata into `tracks` and records the relation in `user_likes`.
     * When unliked: removes the row from `user_likes`.
     */
    suspend fun syncLike(
        trackId: String,
        title: String,
        artist: String,
        album: String? = null,
        coverUrl: String? = null,
        durationSec: Int = 0,
        isLiked: Boolean,
        createdAtIso: String? = null,
        userId: String? = null
    ): Result<Boolean> = withContext(ioDispatcher) {
        try {
            val user = SupabaseClient.currentUser.value
            val targetUserId = userId ?: user?.id ?: SupabaseConfig.DEFAULT_USER.id

            if (isLiked) {
                // 1. Ensure track metadata exists in `tracks`
                val trackPayload = buildJsonObject {
                    put("id", trackId)
                    put("title", title)
                    put("artist", artist)
                    put("album", album ?: "Streamify")
                    put("duration_sec", durationSec)
                    put("cover_url", coverUrl ?: "")
                }.toString()

                SupabaseClient.httpClient.post("${SupabaseConfig.SUPABASE_URL}/rest/v1/tracks") {
                    header("apikey", SupabaseConfig.ANON_KEY)
                    header("Authorization", "Bearer ${SupabaseClient.getAuthToken()}")
                    header("Prefer", "resolution=merge-duplicates")
                    contentType(ContentType.Application.Json)
                    setBody(trackPayload)
                }

                // 2. Insert like relationship in `user_likes`
                val likePayload = buildJsonObject {
                    put("user_id", targetUserId)
                    put("track_id", trackId)
                    if (!createdAtIso.isNullOrBlank()) {
                        put("created_at", createdAtIso)
                    }
                }.toString()

                val resp = SupabaseClient.httpClient.post("${SupabaseConfig.SUPABASE_URL}/rest/v1/user_likes") {
                    header("apikey", SupabaseConfig.ANON_KEY)
                    header("Authorization", "Bearer ${SupabaseClient.getAuthToken()}")
                    header("Prefer", "resolution=ignore-duplicates")
                    contentType(ContentType.Application.Json)
                    setBody(likePayload)
                }
                Result.success(resp.status.isSuccess())
            } else {
                // Remove like relation from `user_likes`
                val resp = SupabaseClient.httpClient.delete("${SupabaseConfig.SUPABASE_URL}/rest/v1/user_likes") {
                    parameter("user_id", "eq.$targetUserId")
                    parameter("track_id", "eq.$trackId")
                    header("apikey", SupabaseConfig.ANON_KEY)
                    header("Authorization", "Bearer ${SupabaseClient.getAuthToken()}")
                }
                Result.success(resp.status.isSuccess())
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Fetches all cloud-liked track IDs with their like timestamp (`created_at`).
     */
    suspend fun fetchCloudLikesWithTime(userId: String? = null): Map<String, String?> = withContext(ioDispatcher) {
        val allLikes = mutableMapOf<String, String?>()
        try {
            val targetUserId = userId ?: SupabaseClient.currentUser.value?.id ?: SupabaseConfig.DEFAULT_USER.id
            var offset = 0
            val limit = 1000
            while (true) {
                val resp = SupabaseClient.httpClient.get("${SupabaseConfig.SUPABASE_URL}/rest/v1/user_likes") {
                    parameter("user_id", "eq.$targetUserId")
                    parameter("select", "track_id,created_at")
                    parameter("limit", limit.toString())
                    parameter("offset", offset.toString())
                    header("apikey", SupabaseConfig.ANON_KEY)
                    header("Authorization", "Bearer ${SupabaseClient.getAuthToken()}")
                }
                if (!resp.status.isSuccess()) break
                val list = json.decodeFromString<List<CloudUserLike>>(resp.bodyAsText())
                list.forEach { allLikes[it.track_id] = it.created_at }
                if (list.size < limit) break
                offset += limit
            }
        } catch (_: Exception) {
        }
        allLikes
    }

    /**
     * Fetches all cloud-liked track IDs for this user with automatic pagination beyond PostgREST 1000-row limit.
     */
    suspend fun fetchCloudLikes(userId: String? = null): List<String> = withContext(ioDispatcher) {
        fetchCloudLikesWithTime(userId).keys.toList()
    }

    /**
     * High-performance batch uploader for local liked tracks:
     * Chunks items into 50-track JSON arrays to avoid serial N+1 HTTP calls.
     */
    suspend fun uploadLikedTracksBatch(
        tracks: List<CloudTrack>,
        userId: String? = null
    ): Result<Boolean> = withContext(ioDispatcher) {
        try {
            val targetUserId = userId ?: SupabaseClient.currentUser.value?.id ?: SupabaseConfig.DEFAULT_USER.id
            val chunks = tracks.chunked(50)
            for (chunk in chunks) {
                // 1. Bulk upsert tracks metadata
                val tracksArray = buildJsonArray {
                    for (t in chunk) {
                        add(buildJsonObject {
                            put("id", t.id)
                            put("title", t.title)
                            put("artist", t.artist)
                            put("album", t.album ?: "Streamify")
                            put("duration_sec", t.durationSec)
                            put("cover_url", t.coverUrl)
                        })
                    }
                }.toString()

                SupabaseClient.httpClient.post("${SupabaseConfig.SUPABASE_URL}/rest/v1/tracks") {
                    header("apikey", SupabaseConfig.ANON_KEY)
                    header("Authorization", "Bearer ${SupabaseClient.getAuthToken()}")
                    header("Prefer", "resolution=merge-duplicates")
                    contentType(ContentType.Application.Json)
                    setBody(tracksArray)
                }

                // 2. Bulk insert user_likes
                val likesArray = buildJsonArray {
                    for (t in chunk) {
                        add(buildJsonObject {
                            put("user_id", targetUserId)
                            put("track_id", t.id)
                            if (!t.createdAt.isNullOrBlank()) {
                                put("created_at", t.createdAt)
                            }
                        })
                    }
                }.toString()

                SupabaseClient.httpClient.post("${SupabaseConfig.SUPABASE_URL}/rest/v1/user_likes") {
                    header("apikey", SupabaseConfig.ANON_KEY)
                    header("Authorization", "Bearer ${SupabaseClient.getAuthToken()}")
                    header("Prefer", "resolution=ignore-duplicates")
                    contentType(ContentType.Application.Json)
                    setBody(likesArray)
                }
            }
            Result.success(true)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Fetches full CloudTrack metadata for all liked songs of this user.
     * Batches requests in chunks of 40 IDs to avoid query string truncation.
     */
    suspend fun fetchCloudLikedTracks(userId: String? = null): List<CloudTrack> = withContext(ioDispatcher) {
        try {
            val likesMap = fetchCloudLikesWithTime(userId)
            val likedIds = likesMap.keys.toList()
            if (likedIds.isEmpty()) return@withContext emptyList()

            val resultTracks = mutableListOf<CloudTrack>()
            val chunks = likedIds.chunked(40)
            for (chunk in chunks) {
                val inClause = chunk.joinToString(",") { "\"$it\"" }
                val resp = SupabaseClient.httpClient.get("${SupabaseConfig.SUPABASE_URL}/rest/v1/tracks") {
                    parameter("id", "in.($inClause)")
                    header("apikey", SupabaseConfig.ANON_KEY)
                    header("Authorization", "Bearer ${SupabaseClient.getAuthToken()}")
                }
                if (resp.status.isSuccess()) {
                    val tracks = json.decodeFromString<List<CloudTrack>>(resp.bodyAsText())
                    resultTracks.addAll(tracks.map { track ->
                        val likedTime = likesMap[track.id]
                        if (likedTime != null) track.copy(createdAt = likedTime) else track
                    })
                }
            }
            resultTracks
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Fetches favorited artist or album IDs from user_item_favorites.
     */
    suspend fun fetchCloudItemFavorites(itemType: String, userId: String? = null): List<String> = withContext(ioDispatcher) {
        try {
            val targetUserId = userId ?: SupabaseClient.currentUser.value?.id ?: SupabaseConfig.DEFAULT_USER.id
            val resp = SupabaseClient.httpClient.get("${SupabaseConfig.SUPABASE_URL}/rest/v1/user_item_favorites") {
                parameter("user_id", "eq.$targetUserId")
                parameter("item_type", "eq.$itemType")
                parameter("select", "item_id")
                header("apikey", SupabaseConfig.ANON_KEY)
                header("Authorization", "Bearer ${SupabaseClient.getAuthToken()}")
            }
            if (resp.status.isSuccess()) {
                val list = json.decodeFromString<List<CloudUserItemFavorite>>(resp.bodyAsText())
                list.map { it.itemId }
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Synchronizes a favorited/unfavorited artist or album with user_item_favorites.
     */
    suspend fun syncItemFavorite(
        itemType: String,
        itemId: String,
        isFavorited: Boolean,
        userId: String? = null
    ): Result<Boolean> = withContext(ioDispatcher) {
        try {
            val targetUserId = userId ?: SupabaseClient.currentUser.value?.id ?: SupabaseConfig.DEFAULT_USER.id
            if (isFavorited) {
                val payload = buildJsonObject {
                    put("user_id", targetUserId)
                    put("item_type", itemType)
                    put("item_id", itemId)
                }.toString()

                val resp = SupabaseClient.httpClient.post("${SupabaseConfig.SUPABASE_URL}/rest/v1/user_item_favorites") {
                    header("apikey", SupabaseConfig.ANON_KEY)
                    header("Authorization", "Bearer ${SupabaseClient.getAuthToken()}")
                    header("Prefer", "resolution=ignore-duplicates")
                    contentType(ContentType.Application.Json)
                    setBody(payload)
                }
                Result.success(resp.status.isSuccess())
            } else {
                val resp = SupabaseClient.httpClient.delete("${SupabaseConfig.SUPABASE_URL}/rest/v1/user_item_favorites") {
                    parameter("user_id", "eq.$targetUserId")
                    parameter("item_type", "eq.$itemType")
                    parameter("item_id", "eq.$itemId")
                    header("apikey", SupabaseConfig.ANON_KEY)
                    header("Authorization", "Bearer ${SupabaseClient.getAuthToken()}")
                }
                Result.success(resp.status.isSuccess())
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Synchronizes a user playlist and its tracks into Supabase cloud storage.
     */
    suspend fun syncPlaylist(
        playlistId: String,
        name: String,
        description: String = "",
        coverUrl: String = "",
        trackIds: List<String> = emptyList(),
        isPublic: Boolean = true,
        youtubePlaylistId: String? = null,
        userId: String? = null
    ): Result<Boolean> = withContext(ioDispatcher) {
        try {
            val targetUserId = userId ?: SupabaseClient.currentUser.value?.id ?: SupabaseConfig.DEFAULT_USER.id

            val playlistPayload = buildJsonObject {
                put("id", playlistId)
                put("user_id", targetUserId)
                put("name", name)
                put("description", description)
                put("cover_url", coverUrl)
                put("is_public", isPublic)
                if (!youtubePlaylistId.isNullOrBlank()) {
                    put("youtube_playlist_id", youtubePlaylistId)
                }
            }.toString()

            val plResp = SupabaseClient.httpClient.post("${SupabaseConfig.SUPABASE_URL}/rest/v1/playlists") {
                header("apikey", SupabaseConfig.ANON_KEY)
                header("Authorization", "Bearer ${SupabaseClient.getAuthToken()}")
                header("Prefer", "resolution=merge-duplicates")
                contentType(ContentType.Application.Json)
                setBody(playlistPayload)
            }

            // Clear old track mappings for this playlist to prevent duplicate rows and clean deleted items
            SupabaseClient.httpClient.delete("${SupabaseConfig.SUPABASE_URL}/rest/v1/playlist_tracks") {
                parameter("playlist_id", "eq.$playlistId")
                header("apikey", SupabaseConfig.ANON_KEY)
                header("Authorization", "Bearer ${SupabaseClient.getAuthToken()}")
            }

            // Sync all track relationships in a SINGLE bulk array request
            if (trackIds.isNotEmpty()) {
                val tracksArray = buildJsonArray {
                    trackIds.forEachIndexed { idx, trackId ->
                        add(buildJsonObject {
                            put("playlist_id", playlistId)
                            put("track_id", trackId)
                            put("position", idx)
                            put("added_by", targetUserId)
                        })
                    }
                }.toString()

                SupabaseClient.httpClient.post("${SupabaseConfig.SUPABASE_URL}/rest/v1/playlist_tracks") {
                    header("apikey", SupabaseConfig.ANON_KEY)
                    header("Authorization", "Bearer ${SupabaseClient.getAuthToken()}")
                    header("Prefer", "resolution=merge-duplicates")
                    contentType(ContentType.Application.Json)
                    setBody(tracksArray)
                }
            }
            Result.success(plResp.status.isSuccess())
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Deletes a cloud playlist and cascades to its playlist_tracks mappings.
     */
    suspend fun deleteCloudPlaylist(playlistId: String): Result<Boolean> = withContext(ioDispatcher) {
        try {
            val resp = SupabaseClient.httpClient.delete("${SupabaseConfig.SUPABASE_URL}/rest/v1/playlists") {
                parameter("id", "eq.$playlistId")
                header("apikey", SupabaseConfig.ANON_KEY)
                header("Authorization", "Bearer ${SupabaseClient.getAuthToken()}")
            }
            Result.success(resp.status.isSuccess())
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Fetches playlists saved in Supabase for this user.
     */
    suspend fun fetchCloudPlaylists(userId: String? = null): List<CloudPlaylist> = withContext(ioDispatcher) {
        try {
            val targetUserId = userId ?: SupabaseClient.currentUser.value?.id ?: SupabaseConfig.DEFAULT_USER.id
            val resp = SupabaseClient.httpClient.get("${SupabaseConfig.SUPABASE_URL}/rest/v1/playlists") {
                parameter("user_id", "eq.$targetUserId")
                header("apikey", SupabaseConfig.ANON_KEY)
                header("Authorization", "Bearer ${SupabaseClient.getAuthToken()}")
            }
            if (resp.status.isSuccess()) {
                json.decodeFromString<List<CloudPlaylist>>(resp.bodyAsText())
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Fetches track IDs and full embedded track metadata belonging to a cloud playlist.
     */
    suspend fun fetchCloudPlaylistTracksWithMetadata(playlistId: String): List<CloudPlaylistTrackWithMetadata> = withContext(ioDispatcher) {
        try {
            val resp = SupabaseClient.httpClient.get("${SupabaseConfig.SUPABASE_URL}/rest/v1/playlist_tracks") {
                parameter("playlist_id", "eq.$playlistId")
                parameter("order", "position.asc")
                parameter("select", "id,playlist_id,track_id,position,tracks(*)")
                header("apikey", SupabaseConfig.ANON_KEY)
                header("Authorization", "Bearer ${SupabaseClient.getAuthToken()}")
            }
            if (resp.status.isSuccess()) {
                json.decodeFromString<List<CloudPlaylistTrackWithMetadata>>(resp.bodyAsText())
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Fetches track IDs belonging to a cloud playlist, ordered by position.
     */
    suspend fun fetchCloudPlaylistTracks(playlistId: String): List<String> = withContext(ioDispatcher) {
        try {
            val resp = SupabaseClient.httpClient.get("${SupabaseConfig.SUPABASE_URL}/rest/v1/playlist_tracks") {
                parameter("playlist_id", "eq.$playlistId")
                parameter("order", "position.asc")
                parameter("select", "track_id")
                header("apikey", SupabaseConfig.ANON_KEY)
                header("Authorization", "Bearer ${SupabaseClient.getAuthToken()}")
            }
            if (resp.status.isSuccess()) {
                val list = json.decodeFromString<List<CloudPlaylistTrack>>(resp.bodyAsText())
                list.map { it.trackId }.filter { it.isNotBlank() }
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Uploads the user's settings profile to `public.user_settings`.
     */
    suspend fun uploadUserSettings(
        userId: String,
        settingsMap: Map<String, String>
    ): Result<Boolean> = withContext(ioDispatcher) {
        try {
            val settingsJson = buildJsonObject {
                settingsMap.forEach { (k, v) -> put(k, v) }
            }
            val payload = buildJsonObject {
                put("user_id", userId)
                put("settings", settingsJson)
            }.toString()

            val resp = SupabaseClient.httpClient.post("${SupabaseConfig.SUPABASE_URL}/rest/v1/user_settings") {
                header("apikey", SupabaseConfig.ANON_KEY)
                header("Authorization", "Bearer ${SupabaseClient.getAuthToken()}")
                header("Prefer", "resolution=merge-duplicates")
                contentType(ContentType.Application.Json)
                setBody(payload)
            }
            Result.success(resp.status.isSuccess())
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Fetches the user's cloud settings from `public.user_settings`.
     */
    suspend fun fetchUserSettings(
        userId: String
    ): Result<Map<String, String>> = withContext(ioDispatcher) {
        try {
            val resp = SupabaseClient.httpClient.get("${SupabaseConfig.SUPABASE_URL}/rest/v1/user_settings") {
                parameter("select", "user_id,settings,updated_at")
                parameter("user_id", "eq.$userId")
                header("apikey", SupabaseConfig.ANON_KEY)
                header("Authorization", "Bearer ${SupabaseClient.getAuthToken()}")
            }
            if (resp.status.isSuccess()) {
                val list = json.decodeFromString<List<CloudUserSettings>>(resp.bodyAsText())
                Result.success(list.firstOrNull()?.settings ?: emptyMap())
            } else {
                Result.failure(Exception("HTTP ${resp.status.value}: ${resp.bodyAsText()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Records a playback event to `public.user_listening_history` for Analytics & Wrapped.
     */
    suspend fun recordPlaybackHistory(
        userId: String,
        trackId: String,
        durationSec: Int,
        syncId: String? = null,
        albumId: String? = null,
        artistId: String? = null,
        createdAtIso: String? = null
    ): Result<Boolean> = withContext(ioDispatcher) {
        try {
            val payload = buildJsonObject {
                if (!syncId.isNullOrBlank()) {
                    put("id", syncId)
                }
                put("user_id", userId)
                put("track_id", trackId)
                put("listened_duration_sec", durationSec)
                if (!albumId.isNullOrBlank()) {
                    put("album_id", albumId)
                }
                if (!artistId.isNullOrBlank()) {
                    put("artist_id", artistId)
                }
                if (!createdAtIso.isNullOrBlank()) {
                    put("created_at", createdAtIso)
                }
            }.toString()

            val resp = SupabaseClient.httpClient.post("${SupabaseConfig.SUPABASE_URL}/rest/v1/user_listening_history") {
                header("apikey", SupabaseConfig.ANON_KEY)
                header("Authorization", "Bearer ${SupabaseClient.getAuthToken()}")
                header("Prefer", "resolution=ignore-duplicates")
                contentType(ContentType.Application.Json)
                setBody(payload)
            }
            Result.success(resp.status.isSuccess())
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Fetches the user's remote playback history from `public.user_listening_history`.
     */
    suspend fun fetchCloudListeningHistory(
        userId: String,
        limit: Int = 100
    ): List<CloudListeningHistoryItem> = withContext(ioDispatcher) {
        try {
            val resp = SupabaseClient.httpClient.get("${SupabaseConfig.SUPABASE_URL}/rest/v1/user_listening_history") {
                parameter("select", "id,user_id,track_id,listened_duration_sec,album_id,artist_id,created_at")
                parameter("user_id", "eq.$userId")
                parameter("order", "created_at.desc")
                parameter("limit", limit.toString())
                header("apikey", SupabaseConfig.ANON_KEY)
                header("Authorization", "Bearer ${SupabaseClient.getAuthToken()}")
            }
            if (resp.status.isSuccess()) {
                json.decodeFromString<List<CloudListeningHistoryItem>>(resp.bodyAsText())
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Fetches the user's search history from `public.user_search_history`.
     */
    suspend fun fetchCloudSearchHistory(userId: String? = null): List<CloudSearchQuery> = withContext(ioDispatcher) {
        try {
            val targetUserId = userId ?: SupabaseClient.currentUser.value?.id ?: SupabaseConfig.DEFAULT_USER.id
            val resp = SupabaseClient.httpClient.get("${SupabaseConfig.SUPABASE_URL}/rest/v1/user_search_history") {
                parameter("user_id", "eq.$targetUserId")
                parameter("is_deleted", "eq.false")
                parameter("order", "searched_at.desc")
                parameter("limit", "100")
                header("apikey", SupabaseConfig.ANON_KEY)
                header("Authorization", "Bearer ${SupabaseClient.getAuthToken()}")
            }
            if (resp.status.isSuccess()) {
                json.decodeFromString<List<CloudSearchQuery>>(resp.bodyAsText())
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Batch uploads search queries to `public.user_search_history`.
     */
    suspend fun uploadSearchHistory(
        queries: List<String>,
        userId: String? = null
    ): Result<Boolean> = withContext(ioDispatcher) {
        try {
            val targetUserId = userId ?: SupabaseClient.currentUser.value?.id ?: SupabaseConfig.DEFAULT_USER.id
            val validQueries = queries.filter { it.isNotBlank() }
            if (validQueries.isEmpty()) return@withContext Result.success(true)

            val arrayPayload = buildJsonArray {
                for (q in validQueries) {
                    add(buildJsonObject {
                        put("user_id", targetUserId)
                        put("query", q)
                    })
                }
            }.toString()

            val resp = SupabaseClient.httpClient.post("${SupabaseConfig.SUPABASE_URL}/rest/v1/user_search_history") {
                header("apikey", SupabaseConfig.ANON_KEY)
                header("Authorization", "Bearer ${SupabaseClient.getAuthToken()}")
                header("Prefer", "resolution=ignore-duplicates")
                contentType(ContentType.Application.Json)
                setBody(arrayPayload)
            }
            Result.success(resp.status.isSuccess())
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Updates the user's active playback session in `public.profiles` for cross-device continuity.
     */
    suspend fun updateActiveSession(
        trackId: String?,
        positionMs: Long,
        queueIds: List<String> = emptyList(),
        updatedAtIso: String? = null,
        userId: String? = null
    ): Result<Boolean> = withContext(ioDispatcher) {
        try {
            val targetUserId = userId ?: SupabaseClient.currentUser.value?.id ?: SupabaseConfig.DEFAULT_USER.id
            val sessionObj = buildJsonObject {
                put("track_id", trackId)
                put("position_ms", positionMs)
                put("queue_ids", buildJsonArray { queueIds.forEach { add(it) } })
                if (!updatedAtIso.isNullOrBlank()) {
                    put("updated_at", updatedAtIso)
                }
            }
            val payload = buildJsonObject {
                put("active_session", sessionObj)
            }.toString()

            val resp = SupabaseClient.httpClient.patch("${SupabaseConfig.SUPABASE_URL}/rest/v1/profiles") {
                parameter("id", "eq.$targetUserId")
                header("apikey", SupabaseConfig.ANON_KEY)
                header("Authorization", "Bearer ${SupabaseClient.getAuthToken()}")
                contentType(ContentType.Application.Json)
                setBody(payload)
            }
            Result.success(resp.status.isSuccess())
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Fetches the user's active playback session from `public.profiles`.
     */
    suspend fun fetchActiveSession(userId: String? = null): Result<CloudActiveSession?> = withContext(ioDispatcher) {
        try {
            val targetUserId = userId ?: SupabaseClient.currentUser.value?.id ?: SupabaseConfig.DEFAULT_USER.id
            val resp = SupabaseClient.httpClient.get("${SupabaseConfig.SUPABASE_URL}/rest/v1/profiles") {
                parameter("id", "eq.$targetUserId")
                parameter("select", "active_session")
                header("apikey", SupabaseConfig.ANON_KEY)
                header("Authorization", "Bearer ${SupabaseClient.getAuthToken()}")
            }
            if (resp.status.isSuccess()) {
                val list = json.decodeFromString<List<UserProfile>>(resp.bodyAsText())
                Result.success(list.firstOrNull()?.activeSession)
            } else {
                Result.failure(Exception("HTTP ${resp.status.value}: ${resp.bodyAsText()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
