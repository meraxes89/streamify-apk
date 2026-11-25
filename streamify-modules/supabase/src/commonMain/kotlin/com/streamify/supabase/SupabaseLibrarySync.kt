package com.streamify.supabase

import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
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
            if (e is CancellationException) throw e
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
            val limit = 500
            while (true) {
                val resp = SupabaseClient.httpClient.get("${SupabaseConfig.SUPABASE_URL}/rest/v1/user_likes") {
                    parameter("user_id", "eq.$targetUserId")
                    parameter("deleted_at", "is.null")
                    parameter("select", "track_id,created_at")
                    parameter("order", "created_at.asc,track_id.asc")
                    parameter("offset", offset.toString())
                    parameter("limit", limit.toString())
                    header("apikey", SupabaseConfig.ANON_KEY)
                    header("Authorization", "Bearer ${SupabaseClient.getAuthToken()}")
                }
                if (!resp.status.isSuccess()) break
                val list = json.decodeFromString<List<CloudUserLike>>(resp.bodyAsText())
                if (list.isEmpty()) break
                list.forEach {
                    allLikes[it.track_id] = it.created_at
                }
                offset += list.size
                if (list.size < limit) break
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
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
            if (e is CancellationException) throw e
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
            if (e is CancellationException) throw e
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
                parameter("deleted_at", "is.null")
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
            if (e is CancellationException) throw e
            emptyList()
        }
    }

    /**
     * Fetches favorited items with their metadata from user_item_favorites.
     */
    suspend fun fetchCloudItemFavoritesWithMetadata(itemType: String, userId: String? = null): List<CloudUserItemFavorite> = withContext(ioDispatcher) {
        try {
            val targetUserId = userId ?: SupabaseClient.currentUser.value?.id ?: SupabaseConfig.DEFAULT_USER.id
            val resp = SupabaseClient.httpClient.get("${SupabaseConfig.SUPABASE_URL}/rest/v1/user_item_favorites") {
                parameter("user_id", "eq.$targetUserId")
                parameter("item_type", "eq.$itemType")
                parameter("deleted_at", "is.null")
                parameter("select", "user_id,item_type,item_id,metadata,created_at")
                header("apikey", SupabaseConfig.ANON_KEY)
                header("Authorization", "Bearer ${SupabaseClient.getAuthToken()}")
            }
            if (resp.status.isSuccess()) {
                json.decodeFromString<List<CloudUserItemFavorite>>(resp.bodyAsText())
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            emptyList()
        }
    }

    /**
     * Synchronizes a favorited/unfavorited artist, album, podcast, or playlist with user_item_favorites.
     */
    suspend fun syncItemFavorite(
        itemType: String,
        itemId: String,
        isFavorited: Boolean,
        userId: String? = null,
        metadata: Map<String, String>? = null
    ): Result<Boolean> = withContext(ioDispatcher) {
        try {
            val targetUserId = userId ?: SupabaseClient.currentUser.value?.id ?: SupabaseConfig.DEFAULT_USER.id
            if (isFavorited) {
                val payload = buildJsonObject {
                    put("user_id", targetUserId)
                    put("item_type", itemType)
                    put("item_id", itemId)
                    if (metadata != null) {
                        put("metadata", buildJsonObject {
                            metadata.forEach { (k, v) -> put(k, v) }
                        })
                    }
                }.toString()

                val resp = SupabaseClient.httpClient.post("${SupabaseConfig.SUPABASE_URL}/rest/v1/user_item_favorites") {
                    header("apikey", SupabaseConfig.ANON_KEY)
                    header("Authorization", "Bearer ${SupabaseClient.getAuthToken()}")
                    header("Prefer", "resolution=merge-duplicates")
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
            if (e is CancellationException) throw e
            Result.failure(e)
        }
    }

    /**
     * Synchronizes a user playlist and its tracks into Supabase cloud storage.
     * Uses atomic diffing and safe deletions of only removed tracks (Defect D2 resolution).
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

            // Defect D2 fix: Atomic diffing instead of destructive DELETE-all
            val existingTracks = fetchCloudPlaylistTracksWithMetadata(playlistId)
            val existingTrackIds = existingTracks.map { it.trackId }

            if (existingTrackIds != trackIds) {
                val targetTrackSet = trackIds.toSet()
                val removedItems = existingTracks.filter { it.trackId !in targetTrackSet }

                // 1. Delete ONLY removed tracks by id (retaining all kept tracks safely)
                if (removedItems.isNotEmpty()) {
                    val idList = removedItems.map { it.id }.joinToString(",")
                    SupabaseClient.httpClient.delete("${SupabaseConfig.SUPABASE_URL}/rest/v1/playlist_tracks") {
                        parameter("id", "in.($idList)")
                        header("apikey", SupabaseConfig.ANON_KEY)
                        header("Authorization", "Bearer ${SupabaseClient.getAuthToken()}")
                    }
                }

                // 2. Upsert added/reordered tracks with position preservation
                if (trackIds.isNotEmpty()) {
                    val existingMap = existingTracks.associateBy { it.trackId }
                    val tracksArray = buildJsonArray {
                        trackIds.forEachIndexed { idx, trackId ->
                            val existing = existingMap[trackId]
                            add(buildJsonObject {
                                if (existing != null) {
                                    put("id", existing.id)
                                } else {
                                    put("id", generateUuid())
                                }
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
            }

            Result.success(plResp.status.isSuccess())
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Result.failure(e)
        }
    }

    /**
     * Fetches tracks saved to the user's library from `public.user_library_tracks`.
     */
    suspend fun fetchCloudLibraryTracks(userId: String? = null): List<CloudLibraryTrack> = withContext(ioDispatcher) {
        try {
            val targetUserId = userId ?: SupabaseClient.currentUser.value?.id ?: SupabaseConfig.DEFAULT_USER.id
            val resp = SupabaseClient.httpClient.get("${SupabaseConfig.SUPABASE_URL}/rest/v1/user_library_tracks") {
                parameter("user_id", "eq.$targetUserId")
                parameter("deleted_at", "is.null")
                parameter("select", "track_id,user_id,added_at,modified_hlc")
                header("apikey", SupabaseConfig.ANON_KEY)
                header("Authorization", "Bearer ${SupabaseClient.getAuthToken()}")
            }
            if (resp.status.isSuccess()) {
                json.decodeFromString<List<CloudLibraryTrack>>(resp.bodyAsText())
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            emptyList()
        }
    }

    /**
     * Synchronizes a single track's library state with `public.user_library_tracks`.
     */
    suspend fun syncLibraryTrack(
        trackId: String,
        inLibrary: Boolean,
        userId: String? = null
    ): Result<Boolean> = withContext(ioDispatcher) {
        try {
            val targetUserId = userId ?: SupabaseClient.currentUser.value?.id ?: SupabaseConfig.DEFAULT_USER.id
            if (inLibrary) {
                val payload = buildJsonObject {
                    put("user_id", targetUserId)
                    put("track_id", trackId)
                }.toString()
                val resp = SupabaseClient.httpClient.post("${SupabaseConfig.SUPABASE_URL}/rest/v1/user_library_tracks") {
                    header("apikey", SupabaseConfig.ANON_KEY)
                    header("Authorization", "Bearer ${SupabaseClient.getAuthToken()}")
                    header("Prefer", "resolution=merge-duplicates")
                    contentType(ContentType.Application.Json)
                    setBody(payload)
                }
                Result.success(resp.status.isSuccess())
            } else {
                val resp = SupabaseClient.httpClient.delete("${SupabaseConfig.SUPABASE_URL}/rest/v1/user_library_tracks") {
                    parameter("user_id", "eq.$targetUserId")
                    parameter("track_id", "eq.$trackId")
                    header("apikey", SupabaseConfig.ANON_KEY)
                    header("Authorization", "Bearer ${SupabaseClient.getAuthToken()}")
                }
                Result.success(resp.status.isSuccess())
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Result.failure(e)
        }
    }

    /**
     * Batch uploads library tracks to `public.user_library_tracks`.
     */
    suspend fun uploadLibraryTracksBatch(
        trackIds: List<String>,
        userId: String? = null
    ): Result<Boolean> = withContext(ioDispatcher) {
        try {
            val targetUserId = userId ?: SupabaseClient.currentUser.value?.id ?: SupabaseConfig.DEFAULT_USER.id
            if (trackIds.isEmpty()) return@withContext Result.success(true)
            val chunks = trackIds.chunked(50)
            for (chunk in chunks) {
                val arrayPayload = buildJsonArray {
                    for (tid in chunk) {
                        add(buildJsonObject {
                            put("user_id", targetUserId)
                            put("track_id", tid)
                        })
                    }
                }.toString()
                val resp = SupabaseClient.httpClient.post("${SupabaseConfig.SUPABASE_URL}/rest/v1/user_library_tracks") {
                    header("apikey", SupabaseConfig.ANON_KEY)
                    header("Authorization", "Bearer ${SupabaseClient.getAuthToken()}")
                    header("Prefer", "resolution=merge-duplicates")
                    contentType(ContentType.Application.Json)
                    setBody(arrayPayload)
                }
                if (!resp.status.isSuccess()) {
                    return@withContext Result.failure(Exception("HTTP ${resp.status.value}: ${resp.bodyAsText()}"))
                }
            }
            Result.success(true)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Result.failure(e)
        }
    }

    /**
     * Fetches podcast episode states from `public.user_podcast_episode_state`.
     */
    suspend fun fetchCloudEpisodeStates(userId: String? = null): List<CloudEpisodeState> = withContext(ioDispatcher) {
        try {
            val targetUserId = userId ?: SupabaseClient.currentUser.value?.id ?: SupabaseConfig.DEFAULT_USER.id
            val resp = SupabaseClient.httpClient.get("${SupabaseConfig.SUPABASE_URL}/rest/v1/user_podcast_episode_state") {
                parameter("user_id", "eq.$targetUserId")
                parameter("deleted_at", "is.null")
                parameter("select", "episode_id,podcast_id,position_sec,completed,modified_hlc,updated_at,user_id")
                header("apikey", SupabaseConfig.ANON_KEY)
                header("Authorization", "Bearer ${SupabaseClient.getAuthToken()}")
            }
            if (resp.status.isSuccess()) {
                json.decodeFromString<List<CloudEpisodeState>>(resp.bodyAsText())
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            emptyList()
        }
    }

    /**
     * Synchronizes a single podcast episode state with `public.user_podcast_episode_state`.
     */
    suspend fun syncEpisodeState(
        episodeId: String,
        podcastId: String,
        positionSec: Int,
        completed: Boolean,
        hlc: Long = 0L,
        userId: String? = null
    ): Result<Boolean> = withContext(ioDispatcher) {
        try {
            val targetUserId = userId ?: SupabaseClient.currentUser.value?.id ?: SupabaseConfig.DEFAULT_USER.id
            val payload = buildJsonObject {
                put("user_id", targetUserId)
                put("episode_id", episodeId)
                put("podcast_id", podcastId)
                put("position_sec", positionSec)
                put("completed", completed)
                put("modified_hlc", hlc)
            }.toString()

            val resp = SupabaseClient.httpClient.post("${SupabaseConfig.SUPABASE_URL}/rest/v1/user_podcast_episode_state") {
                header("apikey", SupabaseConfig.ANON_KEY)
                header("Authorization", "Bearer ${SupabaseClient.getAuthToken()}")
                header("Prefer", "resolution=merge-duplicates")
                contentType(ContentType.Application.Json)
                setBody(payload)
            }
            Result.success(resp.status.isSuccess())
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Result.failure(e)
        }
    }

    /**
     * Batch uploads podcast episode states to `public.user_podcast_episode_state`.
     */
    suspend fun uploadEpisodeStatesBatch(
        states: List<CloudEpisodeState>,
        userId: String? = null
    ): Result<Boolean> = withContext(ioDispatcher) {
        try {
            val targetUserId = userId ?: SupabaseClient.currentUser.value?.id ?: SupabaseConfig.DEFAULT_USER.id
            if (states.isEmpty()) return@withContext Result.success(true)
            val chunks = states.chunked(50)
            for (chunk in chunks) {
                val arrayPayload = buildJsonArray {
                    for (st in chunk) {
                        add(buildJsonObject {
                            put("user_id", targetUserId)
                            put("episode_id", st.episodeId)
                            put("podcast_id", st.podcastId)
                            put("position_sec", st.positionSec)
                            put("completed", st.completed)
                            if (st.modifiedHlc != null) {
                                put("modified_hlc", st.modifiedHlc)
                            }
                        })
                    }
                }.toString()
                val resp = SupabaseClient.httpClient.post("${SupabaseConfig.SUPABASE_URL}/rest/v1/user_podcast_episode_state") {
                    header("apikey", SupabaseConfig.ANON_KEY)
                    header("Authorization", "Bearer ${SupabaseClient.getAuthToken()}")
                    header("Prefer", "resolution=merge-duplicates")
                    contentType(ContentType.Application.Json)
                    setBody(arrayPayload)
                }
                if (!resp.status.isSuccess()) {
                    return@withContext Result.failure(Exception("HTTP ${resp.status.value}: ${resp.bodyAsText()}"))
                }
            }
            Result.success(true)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
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
            if (e is CancellationException) throw e
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
                parameter("deleted_at", "is.null")
                header("apikey", SupabaseConfig.ANON_KEY)
                header("Authorization", "Bearer ${SupabaseClient.getAuthToken()}")
            }
            if (resp.status.isSuccess()) {
                json.decodeFromString<List<CloudPlaylist>>(resp.bodyAsText())
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
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
                parameter("deleted_at", "is.null")
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
            if (e is CancellationException) throw e
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
                parameter("deleted_at", "is.null")
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
            if (e is CancellationException) throw e
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
            if (e is CancellationException) throw e
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
                parameter("deleted_at", "is.null")
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
            if (e is CancellationException) throw e
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
                    put("client_event_id", syncId)
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
            if (e is CancellationException) throw e
            Result.failure(e)
        }
    }

    /**
     * High-performance batch uploader for playback events (Analytics & Wrapped).
     * Chunks items into 50-event JSON arrays to avoid serial N+1 HTTP calls.
     */
    suspend fun recordPlaybackHistoryBatch(
        userId: String,
        items: List<PlaybackHistoryBatchItem>
    ): Result<Boolean> = withContext(ioDispatcher) {
        try {
            if (items.isEmpty()) return@withContext Result.success(true)
            val chunks = items.chunked(50)
            for (chunk in chunks) {
                val arrayPayload = buildJsonArray {
                    for (item in chunk) {
                        add(buildJsonObject {
                            if (!item.clientEventId.isNullOrBlank()) {
                                put("client_event_id", item.clientEventId)
                            }
                            put("user_id", userId)
                            put("track_id", item.trackId)
                            put("listened_duration_sec", item.durationSec)
                            if (!item.albumId.isNullOrBlank()) {
                                put("album_id", item.albumId)
                            }
                            if (!item.artistId.isNullOrBlank()) {
                                put("artist_id", item.artistId)
                            }
                            if (!item.createdAtIso.isNullOrBlank()) {
                                put("created_at", item.createdAtIso)
                            }
                        })
                    }
                }.toString()

                val resp = SupabaseClient.httpClient.post("${SupabaseConfig.SUPABASE_URL}/rest/v1/user_listening_history") {
                    header("apikey", SupabaseConfig.ANON_KEY)
                    header("Authorization", "Bearer ${SupabaseClient.getAuthToken()}")
                    header("Prefer", "resolution=ignore-duplicates")
                    contentType(ContentType.Application.Json)
                    setBody(arrayPayload)
                }
                if (!resp.status.isSuccess()) {
                    return@withContext Result.failure(Exception("HTTP ${resp.status.value}: ${resp.bodyAsText()}"))
                }
            }
            Result.success(true)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Result.failure(e)
        }
    }

    /**
     * Fetches the user's remote playback history from `public.user_listening_history`.
     */
    suspend fun fetchCloudListeningHistory(
        userId: String,
        limit: Int = 1000
    ): List<CloudListeningHistoryItem> = withContext(ioDispatcher) {
        try {
            val resp = SupabaseClient.httpClient.get("${SupabaseConfig.SUPABASE_URL}/rest/v1/user_listening_history") {
                parameter("select", "id,user_id,track_id,listened_duration_sec,album_id,artist_id,client_event_id,created_at")
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
            if (e is CancellationException) throw e
            emptyList()
        }
    }

    /**
     * Fetches the user's search history from `public.user_search_history`.
     */
    suspend fun fetchCloudSearchHistory(
        userId: String? = null,
        limit: Int = 1000
    ): List<CloudSearchQuery> = withContext(ioDispatcher) {
        try {
            val targetUserId = userId ?: SupabaseClient.currentUser.value?.id ?: SupabaseConfig.DEFAULT_USER.id
            val resp = SupabaseClient.httpClient.get("${SupabaseConfig.SUPABASE_URL}/rest/v1/user_search_history") {
                parameter("user_id", "eq.$targetUserId")
                parameter("deleted_at", "is.null")
                parameter("order", "searched_at.desc")
                parameter("limit", limit.toString())
                header("apikey", SupabaseConfig.ANON_KEY)
                header("Authorization", "Bearer ${SupabaseClient.getAuthToken()}")
            }
            if (resp.status.isSuccess()) {
                json.decodeFromString<List<CloudSearchQuery>>(resp.bodyAsText())
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
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
            if (e is CancellationException) throw e
            Result.failure(e)
        }
    }

    /**
     * Updates the user's active playback session.
     * Note: Decommissioned in Phase P4; active presence is heartbeated via heartbeatPresence into user_presence.
     */
    suspend fun updateActiveSession(
        trackId: String?,
        positionMs: Long,
        queueIds: List<String> = emptyList(),
        updatedAtIso: String? = null,
        userId: String? = null
    ): Result<Boolean> = withContext(ioDispatcher) {
        Result.success(true)
    }

    /**
     * Fetches the user's active playback session, delegating to `user_presence` for backward compatibility.
     */
    suspend fun fetchActiveSession(userId: String? = null): Result<CloudActiveSession?> = withContext(ioDispatcher) {
        try {
            val presence = fetchLatestPresence(userId).getOrNull()
            if (presence != null) {
                Result.success(
                    CloudActiveSession(
                        trackId = presence.trackId,
                        positionMs = presence.positionMs,
                        queueIds = presence.queueIds,
                        updatedAt = presence.updatedAt
                    )
                )
            } else {
                Result.success(null)
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Result.failure(e)
        }
    }

    private suspend fun postWithAuthRetry(
        url: String,
        payload: String,
    ): HttpResponse {
        var resp = SupabaseClient.httpClient.post(url) {
            header("apikey", SupabaseConfig.ANON_KEY)
            header("Authorization", "Bearer ${SupabaseClient.getAuthToken()}")
            contentType(ContentType.Application.Json)
            setBody(payload)
        }
        if (resp.status.value == 401 && SupabaseClient.hasValidUserSession()) {
            val refreshResult = SupabaseClient.refreshSession()
            if (refreshResult.isSuccess) {
                resp = SupabaseClient.httpClient.post(url) {
                    header("apikey", SupabaseConfig.ANON_KEY)
                    header("Authorization", "Bearer ${SupabaseClient.getAuthToken()}")
                    contentType(ContentType.Application.Json)
                    setBody(payload)
                }
            }
        }
        return resp
    }

    /**
     * V3 Sync Contract: Idempotent batched push via `sync_push` RPC.
     */
    suspend fun syncPush(
        ops: kotlinx.serialization.json.JsonArray,
        deviceId: String
    ): Result<SyncPushResult> = withContext(ioDispatcher) {
        try {
            val payload = buildJsonObject {
                put("p_ops", ops)
                put("p_device_id", deviceId)
            }.toString()

            val resp = postWithAuthRetry("${SupabaseConfig.SUPABASE_URL}/rest/v1/rpc/sync_push", payload)
            if (resp.status.isSuccess()) {
                val serverTimeMs = parseHttpDateToEpochMs(resp.headers[HttpHeaders.Date])
                val result = json.decodeFromString<SyncPushResult>(resp.bodyAsText())
                    .copy(serverTimeMs = serverTimeMs)
                Result.success(result)
            } else {
                Result.failure(Exception("sync_push failed HTTP ${resp.status.value}: ${resp.bodyAsText()}"))
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Result.failure(e)
        }
    }

    /**
     * V3 Sync Contract: Delta pull via `sync_pull` RPC.
     */
    suspend fun syncPull(
        since: Long,
        limit: Int = 500,
        deviceId: String? = null
    ): Result<SyncPullResult> = withContext(ioDispatcher) {
        try {
            val payload = buildJsonObject {
                put("p_since", since)
                put("p_limit", limit)
                if (!deviceId.isNullOrBlank()) {
                    put("p_device_id", deviceId)
                }
            }.toString()

            val resp = postWithAuthRetry("${SupabaseConfig.SUPABASE_URL}/rest/v1/rpc/sync_pull", payload)
            if (resp.status.isSuccess()) {
                val serverTimeMs = parseHttpDateToEpochMs(resp.headers[HttpHeaders.Date])
                val result = json.decodeFromString<SyncPullResult>(resp.bodyAsText())
                    .copy(serverTimeMs = serverTimeMs)
                Result.success(result)
            } else {
                Result.failure(Exception("sync_pull failed HTTP ${resp.status.value}: ${resp.bodyAsText()}"))
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Result.failure(e)
        }
    }

    /**
     * V3 Presence: Playback presence heartbeat with HLC guard.
     */
    suspend fun heartbeatPresence(
        deviceId: String,
        trackId: String? = null,
        positionMs: Long = 0L,
        isPlaying: Boolean = false,
        queueIds: List<String> = emptyList(),
        hlc: Long = 0L
    ): Result<Boolean> = withContext(ioDispatcher) {
        try {
            val payload = buildJsonObject {
                put("p_device_id", deviceId)
                if (trackId != null) put("p_track_id", trackId)
                put("p_position_ms", positionMs)
                put("p_is_playing", isPlaying)
                put("p_queue_ids", buildJsonArray { queueIds.forEach { add(it) } })
                put("p_hlc", hlc)
            }.toString()

            val resp = SupabaseClient.httpClient.post("${SupabaseConfig.SUPABASE_URL}/rest/v1/rpc/heartbeat_presence") {
                header("apikey", SupabaseConfig.ANON_KEY)
                header("Authorization", "Bearer ${SupabaseClient.getAuthToken()}")
                contentType(ContentType.Application.Json)
                setBody(payload)
            }
            Result.success(resp.status.isSuccess())
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Result.failure(e)
        }
    }

    /**
     * V3 Presence: Fetch most recent cross-device presence state.
     */
    suspend fun fetchLatestPresence(userId: String? = null): Result<CloudPresence?> = withContext(ioDispatcher) {
        try {
            val resp = SupabaseClient.httpClient.post("${SupabaseConfig.SUPABASE_URL}/rest/v1/rpc/get_latest_presence") {
                header("apikey", SupabaseConfig.ANON_KEY)
                header("Authorization", "Bearer ${SupabaseClient.getAuthToken()}")
                contentType(ContentType.Application.Json)
                setBody("{}")
            }
            if (resp.status.isSuccess()) {
                val list = json.decodeFromString<List<CloudPresence>>(resp.bodyAsText())
                Result.success(list.firstOrNull())
            } else {
                Result.failure(Exception("get_latest_presence failed HTTP ${resp.status.value}: ${resp.bodyAsText()}"))
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Result.failure(e)
        }
    }

    /**
     * V4.1 Fleet Telemetry: Registers or updates device hardware, OS, and build telemetry.
     * Records version transitions in `user_device_version_history` when versionCode or gitSha changes.
     */
    suspend fun recordDeviceSession(
        deviceId: String,
        platform: String,
        appVersion: String,
        versionCode: Int,
        gitSha: String = "",
        buildFlavor: String = "standard",
        deviceModel: String = "",
        osVersion: String = "",
        architecture: String = ""
    ): Result<DeviceRegistrationResult> = withContext(ioDispatcher) {
        try {
            val payload = buildJsonObject {
                put("p_device_id", deviceId)
                put("p_platform", platform)
                put("p_app_version", appVersion)
                put("p_version_code", versionCode)
                put("p_git_sha", gitSha)
                put("p_build_flavor", buildFlavor)
                put("p_device_model", deviceModel)
                put("p_os_version", osVersion)
                put("p_architecture", architecture)
            }.toString()

            val resp = SupabaseClient.httpClient.post("${SupabaseConfig.SUPABASE_URL}/rest/v1/rpc/record_device_session") {
                header("apikey", SupabaseConfig.ANON_KEY)
                header("Authorization", "Bearer ${SupabaseClient.getAuthToken()}")
                contentType(ContentType.Application.Json)
                setBody(payload)
            }
            if (resp.status.isSuccess()) {
                val result = json.decodeFromString<DeviceRegistrationResult>(resp.bodyAsText())
                Result.success(result)
            } else {
                Result.failure(Exception("record_device_session failed HTTP ${resp.status.value}: ${resp.bodyAsText()}"))
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Result.failure(e)
        }
    }

    /**
     * V4.1 Fleet Telemetry: Remotely marks a device session as revoked.
     */
    suspend fun revokeDeviceSession(deviceId: String): Result<DeviceRevocationResult> = withContext(ioDispatcher) {
        try {
            val payload = buildJsonObject {
                put("p_device_id", deviceId)
            }.toString()

            val resp = SupabaseClient.httpClient.post("${SupabaseConfig.SUPABASE_URL}/rest/v1/rpc/revoke_device_session") {
                header("apikey", SupabaseConfig.ANON_KEY)
                header("Authorization", "Bearer ${SupabaseClient.getAuthToken()}")
                contentType(ContentType.Application.Json)
                setBody(payload)
            }
            if (resp.status.isSuccess()) {
                val result = json.decodeFromString<DeviceRevocationResult>(resp.bodyAsText())
                Result.success(result)
            } else {
                Result.failure(Exception("revoke_device_session failed HTTP ${resp.status.value}: ${resp.bodyAsText()}"))
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Result.failure(e)
        }
    }

    /**
     * V4.1 Fleet Telemetry: Retrieves all registered devices for the authenticated user.
     */
    suspend fun getMyDevices(): Result<List<CloudDeviceSession>> = withContext(ioDispatcher) {
        try {
            val resp = SupabaseClient.httpClient.post("${SupabaseConfig.SUPABASE_URL}/rest/v1/rpc/get_my_devices") {
                header("apikey", SupabaseConfig.ANON_KEY)
                header("Authorization", "Bearer ${SupabaseClient.getAuthToken()}")
                contentType(ContentType.Application.Json)
                setBody("{}")
            }
            if (resp.status.isSuccess()) {
                val devices = json.decodeFromString<List<CloudDeviceSession>>(resp.bodyAsText())
                Result.success(devices)
            } else {
                Result.failure(Exception("get_my_devices failed HTTP ${resp.status.value}: ${resp.bodyAsText()}"))
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Result.failure(e)
        }
    }

    /**
     * Parses an HTTP `Date` header (RFC 1123, e.g. `Thu, 26 Nov 2026 01:30:00 GMT`) into epoch ms.
     * Returns `null` if the header is missing or malformed.
     */
    internal fun parseHttpDateToEpochMs(dateHeader: String?): Long? {
        if (dateHeader.isNullOrBlank()) return null
        val afterComma = dateHeader.substringAfter(',', dateHeader).trim()
        val parts = afterComma.split(' ').filter { it.isNotBlank() }
        if (parts.size < 4) return null
        val day = parts[0].toIntOrNull() ?: return null
        val month = when (parts[1].lowercase()) {
            "jan" -> 1
            "feb" -> 2
            "mar" -> 3
            "apr" -> 4
            "may" -> 5
            "jun" -> 6
            "jul" -> 7
            "aug" -> 8
            "sep" -> 9
            "oct" -> 10
            "nov" -> 11
            "dec" -> 12
            else -> return null
        }
        val year = parts[2].toIntOrNull() ?: return null
        val timeParts = parts[3].split(':')
        if (timeParts.size != 3) return null
        val hour = timeParts[0].toIntOrNull() ?: return null
        val minute = timeParts[1].toIntOrNull() ?: return null
        val second = timeParts[2].toIntOrNull() ?: return null
        return try {
            LocalDateTime(year, month, day, hour, minute, second)
                .toInstant(TimeZone.UTC)
                .toEpochMilliseconds()
        } catch (_: Exception) {
            null
        }
    }

    private fun generateUuid(): String {
        val bytes = kotlin.random.Random.nextBytes(16)
        bytes[6] = ((bytes[6].toInt() and 0x0f) or 0x40).toByte()
        bytes[8] = ((bytes[8].toInt() and 0x3f) or 0x80).toByte()
        return buildString(36) {
            for (i in 0 until 16) {
                if (i == 4 || i == 6 || i == 8 || i == 10) append('-')
                val b = bytes[i].toInt() and 0xff
                append(b.toString(16).padStart(2, '0'))
            }
        }
    }
}
