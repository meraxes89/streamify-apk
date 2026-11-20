package com.streamify.supabase

import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
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
                    put("stream_url", "https://www.youtube.com/watch?v=$trackId")
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
     * Fetches all cloud-liked track IDs for this user to enable cross-device synchronization.
     */
    suspend fun fetchCloudLikes(userId: String? = null): List<String> = withContext(ioDispatcher) {
        try {
            val targetUserId = userId ?: SupabaseClient.currentUser.value?.id ?: SupabaseConfig.DEFAULT_USER.id
            val resp = SupabaseClient.httpClient.get("${SupabaseConfig.SUPABASE_URL}/rest/v1/user_likes") {
                parameter("user_id", "eq.$targetUserId")
                parameter("select", "track_id")
                header("apikey", SupabaseConfig.ANON_KEY)
                header("Authorization", "Bearer ${SupabaseClient.getAuthToken()}")
            }
            if (resp.status.isSuccess()) {
                val list = json.decodeFromString<List<CloudUserLike>>(resp.bodyAsText())
                list.map { it.track_id }
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Fetches full CloudTrack metadata for all liked songs of this user.
     * Batches requests in chunks of 40 IDs to avoid query string truncation.
     */
    suspend fun fetchCloudLikedTracks(userId: String? = null): List<CloudTrack> = withContext(ioDispatcher) {
        try {
            val likedIds = fetchCloudLikes(userId)
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
                    resultTracks.addAll(tracks)
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
            }.toString()

            val plResp = SupabaseClient.httpClient.post("${SupabaseConfig.SUPABASE_URL}/rest/v1/playlists") {
                header("apikey", SupabaseConfig.ANON_KEY)
                header("Authorization", "Bearer ${SupabaseClient.getAuthToken()}")
                header("Prefer", "resolution=merge-duplicates")
                contentType(ContentType.Application.Json)
                setBody(playlistPayload)
            }

            // Sync track relationships if any are provided
            trackIds.forEachIndexed { idx, trackId ->
                val trackPayload = buildJsonObject {
                    put("playlist_id", playlistId)
                    put("track_id", trackId)
                    put("position", idx)
                    put("added_by", targetUserId)
                }.toString()

                SupabaseClient.httpClient.post("${SupabaseConfig.SUPABASE_URL}/rest/v1/playlist_tracks") {
                    header("apikey", SupabaseConfig.ANON_KEY)
                    header("Authorization", "Bearer ${SupabaseClient.getAuthToken()}")
                    header("Prefer", "resolution=merge-duplicates")
                    contentType(ContentType.Application.Json)
                    setBody(trackPayload)
                }
            }
            Result.success(plResp.status.isSuccess())
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
}
