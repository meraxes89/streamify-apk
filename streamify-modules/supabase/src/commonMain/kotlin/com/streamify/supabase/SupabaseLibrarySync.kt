package com.streamify.supabase

import io.ktor.client.request.HttpRequestBuilder
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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.time.Clock

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

    internal fun nextCompatHlc(explicitHlc: Long = 0L): Long =
        if (explicitHlc > 0L) explicitHlc else (Clock.System.now().toEpochMilliseconds() shl 22)

    internal fun buildSyncOp(
        entity: String,
        op: String,
        key: String,
        hlc: Long,
        data: JsonObject = buildJsonObject {},
    ): JsonObject = buildJsonObject {
        put("entity", entity)
        put("op", op)
        put("key", key)
        put("hlc", hlc)
        put("data", data)
    }

    private suspend fun pushCompatOps(ops: JsonArray, deviceId: String): Result<Boolean> =
        syncPush(ops, deviceId.ifBlank { "rest_compat" }).map { it.rejected == 0 }

    /**
     * Synchronizes a liked / unliked track with Supabase via `sync_push` (HLC LWW).
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
        userId: String? = null,
        hlc: Long = 0L,
        deviceId: String = "rest_compat",
    ): Result<Boolean> = withContext(ioDispatcher) {
        try {
            val effectiveHlc = nextCompatHlc(hlc)
            val data = buildJsonObject {
                if (!createdAtIso.isNullOrBlank()) put("created_at", createdAtIso)
                put("meta", buildJsonObject {
                    put("title", title)
                    put("artist", artist)
                    put("album", album ?: "Streamify")
                    put("duration_sec", durationSec)
                    put("cover_url", coverUrl ?: "")
                })
            }
            val ops = buildJsonArray {
                add(buildSyncOp(
                    entity = "like",
                    op = if (isLiked) "U" else "D",
                    key = trackId,
                    hlc = effectiveHlc,
                    data = data,
                ))
            }
            pushCompatOps(ops, deviceId)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Result.failure(e)
        }
    }

    /**
     * Fetches all cloud-liked track IDs with their like timestamp (`created_at`).
     */
    internal fun decodeCloudUserLikes(rawJson: String): List<CloudUserLike> =
        json.decodeFromString<List<CloudUserLike>>(rawJson)

    /**
     * Fetches all cloud-liked track IDs with their creation timestamps for LWW resolution.
     * Distinguishes transport/decode failures from an empty cloud ledger.
     */
    suspend fun fetchCloudLikesWithTimeResult(userId: String? = null): Result<Map<String, String?>> = withContext(ioDispatcher) {
        val allLikes = mutableMapOf<String, String?>()
        try {
            val targetUserId = userId ?: SupabaseClient.currentUser.value?.id ?: SupabaseConfig.DEFAULT_USER.id
            var offset = 0
            val limit = 500
            while (true) {
                val resp = getWithAuthRetry("${SupabaseConfig.SUPABASE_URL}/rest/v1/user_likes") {
                    parameter("user_id", "eq.$targetUserId")
                    parameter("deleted_at", "is.null")
                    parameter("select", "user_id,track_id,created_at")
                    parameter("order", "created_at.asc,track_id.asc")
                    parameter("offset", offset.toString())
                    parameter("limit", limit.toString())
                }
                if (!resp.status.isSuccess()) {
                    return@withContext Result.failure(Exception("HTTP ${resp.status.value} fetching user_likes"))
                }
                val list = decodeCloudUserLikes(resp.bodyAsText())
                if (list.isEmpty()) break
                list.forEach {
                    allLikes[it.track_id] = it.created_at
                }
                offset += list.size
                if (list.size < limit) break
            }
            Result.success(allLikes)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Result.failure(e)
        }
    }

    /**
     * Fetches all cloud-liked track IDs with their creation timestamps for LWW resolution.
     */
    suspend fun fetchCloudLikesWithTime(userId: String? = null): Map<String, String?> =
        fetchCloudLikesWithTimeResult(userId).getOrDefault(emptyMap())

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
                // 1. Bulk upsert tracks metadata (skip placeholder "Synced Track" rows)
                val validMetadataTracks = chunk.filter {
                    it.title.isNotBlank() && it.title != "Synced Track"
                }
                if (validMetadataTracks.isNotEmpty()) {
                    val tracksArray = buildJsonArray {
                        for (t in validMetadataTracks) {
                            val normalizedCover = if (t.coverUrl.startsWith("http://", ignoreCase = true)) {
                                "https://" + t.coverUrl.substring(7)
                            } else {
                                t.coverUrl
                            }
                            add(buildJsonObject {
                                put("id", t.id)
                                put("title", t.title)
                                put("artist", t.artist)
                                put("album", t.album ?: "Streamify")
                                put("duration_sec", t.durationSec)
                                put("cover_url", normalizedCover)
                            })
                        }
                    }.toString()

                    postWithAuthRetry("${SupabaseConfig.SUPABASE_URL}/rest/v1/tracks", tracksArray) {
                        header("Prefer", "resolution=merge-duplicates")
                    }
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

                postWithAuthRetry("${SupabaseConfig.SUPABASE_URL}/rest/v1/user_likes", likesArray) {
                    header("Prefer", "resolution=ignore-duplicates")
                }
            }
            Result.success(true)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Result.failure(e)
        }
    }

    /**
     * Fetches CloudTrack metadata for a list of track IDs from the `tracks` view (`track_index`).
     * Batches requests in chunks of 40 IDs to avoid query string truncation.
     */
    suspend fun fetchTracksByIds(trackIds: List<String>): Result<Map<String, CloudTrack>> = withContext(ioDispatcher) {
        try {
            val cleanIds = trackIds.asSequence()
                .map { it.trim() }
                .filter { it.isNotBlank() && !it.startsWith("trk_") }
                .distinct()
                .toList()
            if (cleanIds.isEmpty()) return@withContext Result.success(emptyMap())

            val resultMap = mutableMapOf<String, CloudTrack>()
            for (chunk in cleanIds.chunked(40)) {
                val inClause = chunk.joinToString(",") { "\"$it\"" }
                val resp = getWithAuthRetry("${SupabaseConfig.SUPABASE_URL}/rest/v1/tracks") {
                    parameter("id", "in.($inClause)")
                }
                if (!resp.status.isSuccess()) {
                    return@withContext Result.failure(Exception("HTTP ${resp.status.value} fetching tracks"))
                }
                val tracks = json.decodeFromString<List<CloudTrack>>(resp.bodyAsText())
                tracks.forEach { resultMap[it.id] = it }
            }
            Result.success(resultMap)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Result.failure(e)
        }
    }

    /**
     * Fetches full CloudTrack metadata for all liked songs of this user, preserving failures.
     * Batches requests in chunks of 40 IDs to avoid query string truncation.
     */
    suspend fun fetchCloudLikedTracksResult(userId: String? = null): Result<List<CloudTrack>> = withContext(ioDispatcher) {
        try {
            val likesMap = fetchCloudLikesWithTimeResult(userId).getOrElse {
                return@withContext Result.failure(it)
            }
            val likedIds = likesMap.keys.toList()
            if (likedIds.isEmpty()) return@withContext Result.success(emptyList())

            val tracksById = fetchTracksByIds(likedIds).getOrElse {
                return@withContext Result.failure(it)
            }
            val resultTracks = tracksById.values.map { track ->
                val likedTime = likesMap[track.id]
                if (likedTime != null) track.copy(createdAt = likedTime) else track
            }
            Result.success(resultTracks)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Result.failure(e)
        }
    }

    /**
     * Fetches full CloudTrack metadata for all liked songs of this user.
     * Batches requests in chunks of 40 IDs to avoid query string truncation.
     */
    suspend fun fetchCloudLikedTracks(userId: String? = null): List<CloudTrack> =
        fetchCloudLikedTracksResult(userId).getOrDefault(emptyList())

    /**
     * Fetches favorited artist or album IDs from user_item_favorites, preserving transport/HTTP errors.
     */
    suspend fun fetchCloudItemFavoritesResult(itemType: String, userId: String? = null): Result<List<String>> = withContext(ioDispatcher) {
        try {
            val targetUserId = userId ?: SupabaseClient.currentUser.value?.id ?: SupabaseConfig.DEFAULT_USER.id
            val resp = getWithAuthRetry("${SupabaseConfig.SUPABASE_URL}/rest/v1/user_item_favorites") {
                parameter("user_id", "eq.$targetUserId")
                parameter("item_type", "eq.$itemType")
                parameter("deleted_at", "is.null")
                parameter("select", "item_id")
            }
            if (resp.status.isSuccess()) {
                val list = json.decodeFromString<List<CloudUserItemFavorite>>(resp.bodyAsText())
                Result.success(list.map { it.itemId })
            } else {
                Result.failure(Exception("HTTP ${resp.status.value} fetching user_item_favorites ($itemType)"))
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Result.failure(e)
        }
    }

    /**
     * Fetches favorited artist or album IDs from user_item_favorites.
     */
    suspend fun fetchCloudItemFavorites(itemType: String, userId: String? = null): List<String> =
        fetchCloudItemFavoritesResult(itemType, userId).getOrDefault(emptyList())

    /**
     * Fetches favorited items with their metadata from user_item_favorites, preserving transport/HTTP errors.
     */
    suspend fun fetchCloudItemFavoritesWithMetadataResult(itemType: String, userId: String? = null): Result<List<CloudUserItemFavorite>> = withContext(ioDispatcher) {
        try {
            val targetUserId = userId ?: SupabaseClient.currentUser.value?.id ?: SupabaseConfig.DEFAULT_USER.id
            val resp = getWithAuthRetry("${SupabaseConfig.SUPABASE_URL}/rest/v1/user_item_favorites") {
                parameter("user_id", "eq.$targetUserId")
                parameter("item_type", "eq.$itemType")
                parameter("deleted_at", "is.null")
                parameter("select", "user_id,item_type,item_id,metadata,created_at")
            }
            if (resp.status.isSuccess()) {
                Result.success(json.decodeFromString<List<CloudUserItemFavorite>>(resp.bodyAsText()))
            } else {
                Result.failure(Exception("HTTP ${resp.status.value} fetching user_item_favorites metadata ($itemType)"))
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Result.failure(e)
        }
    }

    /**
     * Fetches favorited items with their metadata from user_item_favorites.
     */
    suspend fun fetchCloudItemFavoritesWithMetadata(itemType: String, userId: String? = null): List<CloudUserItemFavorite> =
        fetchCloudItemFavoritesWithMetadataResult(itemType, userId).getOrDefault(emptyList())

    /**
     * Synchronizes a favorited/unfavorited artist, album, podcast, or playlist via `sync_push` (HLC LWW).
     */
    suspend fun syncItemFavorite(
        itemType: String,
        itemId: String,
        isFavorited: Boolean,
        userId: String? = null,
        metadata: Map<String, String>? = null,
        hlc: Long = 0L,
        deviceId: String = "rest_compat",
    ): Result<Boolean> = withContext(ioDispatcher) {
        try {
            val effectiveHlc = nextCompatHlc(hlc)
            val data = buildJsonObject {
                put("item_type", itemType)
                put("item_id", itemId)
                if (metadata != null) {
                    put("metadata", buildJsonObject {
                        metadata.forEach { (k, v) -> put(k, v) }
                    })
                }
            }
            val ops = buildJsonArray {
                add(buildSyncOp(
                    entity = "favorite",
                    op = if (isFavorited) "U" else "D",
                    key = "$itemType:$itemId",
                    hlc = effectiveHlc,
                    data = data,
                ))
            }
            pushCompatOps(ops, deviceId)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Result.failure(e)
        }
    }

    /**
     * Synchronizes a user playlist and its tracks via `sync_push` (HLC LWW).
     */
    suspend fun syncPlaylist(
        playlistId: String,
        name: String,
        description: String = "",
        coverUrl: String = "",
        trackIds: List<String> = emptyList(),
        isPublic: Boolean = true,
        youtubePlaylistId: String? = null,
        userId: String? = null,
        hlc: Long = 0L,
        deviceId: String = "rest_compat",
    ): Result<Boolean> = withContext(ioDispatcher) {
        try {
            val effectiveHlc = nextCompatHlc(hlc)
            val existingTracks = fetchCloudPlaylistTracksWithMetadata(playlistId)
            val existingTrackIds = existingTracks.map { it.trackId }

            val ops = buildJsonArray {
                add(buildSyncOp(
                    entity = "playlist",
                    op = "U",
                    key = playlistId,
                    hlc = effectiveHlc,
                    data = buildJsonObject {
                        put("name", name)
                        put("description", description)
                        put("cover_url", coverUrl)
                        put("is_public", isPublic)
                        if (!youtubePlaylistId.isNullOrBlank()) {
                            put("youtube_playlist_id", youtubePlaylistId)
                        }
                    },
                ))

                if (existingTrackIds != trackIds) {
                    val targetTrackSet = trackIds.toSet()
                    val removedItems = existingTracks.filter { it.trackId !in targetTrackSet }
                    for (removed in removedItems) {
                        add(buildSyncOp(
                            entity = "playlist_track",
                            op = "D",
                            key = removed.id ?: removed.trackId,
                            hlc = effectiveHlc,
                            data = buildJsonObject {
                                put("playlist_id", playlistId)
                                put("track_id", removed.trackId)
                            },
                        ))
                    }

                    val existingMap = existingTracks.associateBy { it.trackId }
                    trackIds.forEachIndexed { idx, trackId ->
                        val existing = existingMap[trackId]
                        add(buildSyncOp(
                            entity = "playlist_track",
                            op = "U",
                            key = trackId,
                            hlc = effectiveHlc,
                            data = buildJsonObject {
                                put("id", existing?.id ?: generateUuid())
                                put("playlist_id", playlistId)
                                put("track_id", trackId)
                                put("position", idx)
                            },
                        ))
                    }
                }
            }

            pushCompatOps(ops, deviceId)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Result.failure(e)
        }
    }

    /**
     * Fetches tracks saved to the user's library from `public.user_library_tracks`, preserving errors.
     */
    suspend fun fetchCloudLibraryTracksResult(userId: String? = null): Result<List<CloudLibraryTrack>> = withContext(ioDispatcher) {
        try {
            val targetUserId = userId ?: SupabaseClient.currentUser.value?.id ?: SupabaseConfig.DEFAULT_USER.id
            val resp = getWithAuthRetry("${SupabaseConfig.SUPABASE_URL}/rest/v1/user_library_tracks") {
                parameter("user_id", "eq.$targetUserId")
                parameter("deleted_at", "is.null")
                parameter("select", "track_id,user_id,added_at,modified_hlc")
            }
            if (resp.status.isSuccess()) {
                Result.success(json.decodeFromString<List<CloudLibraryTrack>>(resp.bodyAsText()))
            } else {
                Result.failure(Exception("HTTP ${resp.status.value} fetching user_library_tracks"))
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Result.failure(e)
        }
    }

    /**
     * Fetches tracks saved to the user's library from `public.user_library_tracks`.
     */
    suspend fun fetchCloudLibraryTracks(userId: String? = null): List<CloudLibraryTrack> =
        fetchCloudLibraryTracksResult(userId).getOrDefault(emptyList())

    /**
     * Synchronizes a single track's library state via `sync_push` (HLC LWW).
     */
    suspend fun syncLibraryTrack(
        trackId: String,
        inLibrary: Boolean,
        userId: String? = null,
        hlc: Long = 0L,
        deviceId: String = "rest_compat",
    ): Result<Boolean> = withContext(ioDispatcher) {
        try {
            val effectiveHlc = nextCompatHlc(hlc)
            val ops = buildJsonArray {
                add(buildSyncOp(
                    entity = "library_track",
                    op = if (inLibrary) "U" else "D",
                    key = trackId,
                    hlc = effectiveHlc,
                ))
            }
            pushCompatOps(ops, deviceId)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Result.failure(e)
        }
    }

    /**
     * Batch uploads library tracks via `sync_push` (HLC LWW).
     */
    suspend fun uploadLibraryTracksBatch(
        trackIds: List<String>,
        userId: String? = null,
        hlc: Long = 0L,
        deviceId: String = "rest_compat",
    ): Result<Boolean> = withContext(ioDispatcher) {
        try {
            if (trackIds.isEmpty()) return@withContext Result.success(true)
            val effectiveHlc = nextCompatHlc(hlc)
            for (chunk in trackIds.chunked(50)) {
                val ops = buildJsonArray {
                    for (tid in chunk) {
                        add(buildSyncOp(
                            entity = "library_track",
                            op = "U",
                            key = tid,
                            hlc = effectiveHlc,
                        ))
                    }
                }
                val res = pushCompatOps(ops, deviceId)
                if (res.isFailure) return@withContext res
            }
            Result.success(true)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Result.failure(e)
        }
    }

    /**
     * Fetches podcast episode states from `public.user_podcast_episode_state`, preserving errors.
     */
    suspend fun fetchCloudEpisodeStatesResult(userId: String? = null): Result<List<CloudEpisodeState>> = withContext(ioDispatcher) {
        try {
            val targetUserId = userId ?: SupabaseClient.currentUser.value?.id ?: SupabaseConfig.DEFAULT_USER.id
            val resp = getWithAuthRetry("${SupabaseConfig.SUPABASE_URL}/rest/v1/user_podcast_episode_state") {
                parameter("user_id", "eq.$targetUserId")
                parameter("deleted_at", "is.null")
                parameter("select", "episode_id,podcast_id,position_sec,completed,modified_hlc,updated_at,user_id")
            }
            if (resp.status.isSuccess()) {
                Result.success(json.decodeFromString<List<CloudEpisodeState>>(resp.bodyAsText()))
            } else {
                Result.failure(Exception("HTTP ${resp.status.value} fetching user_podcast_episode_state"))
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Result.failure(e)
        }
    }

    /**
     * Fetches podcast episode states from `public.user_podcast_episode_state`.
     */
    suspend fun fetchCloudEpisodeStates(userId: String? = null): List<CloudEpisodeState> =
        fetchCloudEpisodeStatesResult(userId).getOrDefault(emptyList())

    /**
     * Synchronizes a single podcast episode state via `sync_push` (HLC LWW).
     */
    suspend fun syncEpisodeState(
        episodeId: String,
        podcastId: String,
        positionSec: Int,
        completed: Boolean,
        hlc: Long = 0L,
        userId: String? = null,
        deviceId: String = "rest_compat",
    ): Result<Boolean> = withContext(ioDispatcher) {
        try {
            val effectiveHlc = nextCompatHlc(hlc)
            val ops = buildJsonArray {
                add(buildSyncOp(
                    entity = "episode_state",
                    op = "U",
                    key = episodeId,
                    hlc = effectiveHlc,
                    data = buildJsonObject {
                        put("podcast_id", podcastId)
                        put("position_sec", positionSec)
                        put("completed", completed)
                    },
                ))
            }
            pushCompatOps(ops, deviceId)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Result.failure(e)
        }
    }

    /**
     * Batch uploads podcast episode states via `sync_push` (HLC LWW).
     */
    suspend fun uploadEpisodeStatesBatch(
        states: List<CloudEpisodeState>,
        userId: String? = null,
        hlc: Long = 0L,
        deviceId: String = "rest_compat",
    ): Result<Boolean> = withContext(ioDispatcher) {
        try {
            if (states.isEmpty()) return@withContext Result.success(true)
            val fallbackHlc = nextCompatHlc(hlc)
            for (chunk in states.chunked(50)) {
                val ops = buildJsonArray {
                    for (st in chunk) {
                        add(buildSyncOp(
                            entity = "episode_state",
                            op = "U",
                            key = st.episodeId,
                            hlc = st.modifiedHlc?.takeIf { it > 0L } ?: fallbackHlc,
                            data = buildJsonObject {
                                put("podcast_id", st.podcastId)
                                put("position_sec", st.positionSec)
                                put("completed", st.completed)
                            },
                        ))
                    }
                }
                val res = pushCompatOps(ops, deviceId)
                if (res.isFailure) return@withContext res
            }
            Result.success(true)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Result.failure(e)
        }
    }

    /**
     * Deletes a cloud playlist via `sync_push` tombstone (HLC LWW).
     */
    suspend fun deleteCloudPlaylist(
        playlistId: String,
        hlc: Long = 0L,
        deviceId: String = "rest_compat",
    ): Result<Boolean> = withContext(ioDispatcher) {
        try {
            val effectiveHlc = nextCompatHlc(hlc)
            val ops = buildJsonArray {
                add(buildSyncOp(
                    entity = "playlist",
                    op = "D",
                    key = playlistId,
                    hlc = effectiveHlc,
                ))
            }
            pushCompatOps(ops, deviceId)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Result.failure(e)
        }
    }

    /**
     * Fetches playlists saved in Supabase for this user, preserving errors.
     */
    suspend fun fetchCloudPlaylistsResult(userId: String? = null): Result<List<CloudPlaylist>> = withContext(ioDispatcher) {
        try {
            val targetUserId = userId ?: SupabaseClient.currentUser.value?.id ?: SupabaseConfig.DEFAULT_USER.id
            val resp = getWithAuthRetry("${SupabaseConfig.SUPABASE_URL}/rest/v1/playlists") {
                parameter("user_id", "eq.$targetUserId")
                parameter("deleted_at", "is.null")
            }
            if (resp.status.isSuccess()) {
                Result.success(json.decodeFromString<List<CloudPlaylist>>(resp.bodyAsText()))
            } else {
                Result.failure(Exception("HTTP ${resp.status.value} fetching playlists"))
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Result.failure(e)
        }
    }

    /**
     * Fetches playlists saved in Supabase for this user.
     */
    suspend fun fetchCloudPlaylists(userId: String? = null): List<CloudPlaylist> =
        fetchCloudPlaylistsResult(userId).getOrDefault(emptyList())

    /**
     * Fetches track IDs and full embedded track metadata belonging to a cloud playlist, preserving errors.
     */
    suspend fun fetchCloudPlaylistTracksWithMetadataResult(playlistId: String): Result<List<CloudPlaylistTrackWithMetadata>> = withContext(ioDispatcher) {
        try {
            val resp = getWithAuthRetry("${SupabaseConfig.SUPABASE_URL}/rest/v1/playlist_tracks") {
                parameter("playlist_id", "eq.$playlistId")
                parameter("deleted_at", "is.null")
                parameter("order", "position.asc")
                parameter("select", "id,playlist_id,track_id,position,tracks(*)")
            }
            if (resp.status.isSuccess()) {
                Result.success(json.decodeFromString<List<CloudPlaylistTrackWithMetadata>>(resp.bodyAsText()))
            } else {
                Result.failure(Exception("HTTP ${resp.status.value} fetching playlist_tracks for $playlistId"))
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Result.failure(e)
        }
    }

    /**
     * Fetches track IDs and full embedded track metadata belonging to a cloud playlist.
     */
    suspend fun fetchCloudPlaylistTracksWithMetadata(playlistId: String): List<CloudPlaylistTrackWithMetadata> =
        fetchCloudPlaylistTracksWithMetadataResult(playlistId).getOrDefault(emptyList())

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
     * Uploads the user's settings profile via `sync_push` (HLC LWW).
     */
    suspend fun uploadUserSettings(
        userId: String,
        settingsMap: Map<String, String>,
        hlc: Long = 0L,
        deviceId: String = "rest_compat",
    ): Result<Boolean> = withContext(ioDispatcher) {
        try {
            val effectiveHlc = nextCompatHlc(hlc)
            val settingsJson = buildJsonObject {
                settingsMap.forEach { (k, v) -> put(k, v) }
            }
            val ops = buildJsonArray {
                add(buildSyncOp(
                    entity = "settings",
                    op = "U",
                    key = "settings",
                    hlc = effectiveHlc,
                    data = buildJsonObject {
                        put("settings", settingsJson)
                    },
                ))
            }
            pushCompatOps(ops, deviceId)
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
            val resp = getWithAuthRetry("${SupabaseConfig.SUPABASE_URL}/rest/v1/user_settings") {
                parameter("select", "user_id,settings,updated_at")
                parameter("user_id", "eq.$userId")
                parameter("deleted_at", "is.null")
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
        createdAtIso: String? = null,
        hlc: Long = 0L,
        deviceId: String = "rest_compat",
    ): Result<Boolean> = withContext(ioDispatcher) {
        try {
            val effectiveSyncId = syncId?.ifBlank { null } ?: generateUuid()
            recordPlaybackHistoryBatch(
                userId = userId,
                items = listOf(
                    PlaybackHistoryBatchItem(
                        clientEventId = effectiveSyncId,
                        trackId = trackId,
                        durationSec = durationSec,
                        albumId = albumId,
                        artistId = artistId,
                        createdAtIso = createdAtIso,
                    ),
                ),
                hlc = hlc,
                deviceId = deviceId,
            ).getOrThrow()
            Result.success(true)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Result.failure(e)
        }
    }

    /**
     * High-performance batch uploader for playback events (Analytics & Wrapped) via `sync_push` (HLC).
     * Chunks items into 50-op batches to avoid serial N+1 HTTP calls.
     */
    suspend fun recordPlaybackHistoryBatch(
        userId: String,
        items: List<PlaybackHistoryBatchItem>,
        hlc: Long = 0L,
        deviceId: String = "rest_compat",
    ): Result<Boolean> = withContext(ioDispatcher) {
        try {
            if (items.isEmpty()) return@withContext Result.success(true)
            val effectiveHlc = nextCompatHlc(hlc)
            for (chunk in items.chunked(50)) {
                val ops = buildJsonArray {
                    for (item in chunk) {
                        val eventId = item.clientEventId?.ifBlank { null } ?: generateUuid()
                        add(buildSyncOp(
                            entity = "history",
                            op = "U",
                            key = eventId,
                            hlc = effectiveHlc,
                            data = buildJsonObject {
                                put("client_event_id", eventId)
                                put("track_id", item.trackId)
                                put("listened_duration_sec", item.durationSec)
                                if (!item.albumId.isNullOrBlank()) put("album_id", item.albumId)
                                if (!item.artistId.isNullOrBlank()) put("artist_id", item.artistId)
                                if (!item.createdAtIso.isNullOrBlank()) put("listened_at", item.createdAtIso)
                            },
                        ))
                    }
                }
                val res = pushCompatOps(ops, deviceId)
                if (res.isFailure) return@withContext res
            }
            Result.success(true)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Result.failure(e)
        }
    }

    /**
     * Fetches the user's remote playback history from `public.user_listening_history`, preserving errors.
     */
    suspend fun fetchCloudListeningHistoryResult(
        userId: String,
        limit: Int = 1000
    ): Result<List<CloudListeningHistoryItem>> = withContext(ioDispatcher) {
        try {
            val resp = getWithAuthRetry("${SupabaseConfig.SUPABASE_URL}/rest/v1/user_listening_history") {
                parameter("select", "id,user_id,track_id,listened_duration_sec,album_id,artist_id,client_event_id,created_at")
                parameter("user_id", "eq.$userId")
                parameter("order", "created_at.desc")
                parameter("limit", limit.toString())
            }
            if (resp.status.isSuccess()) {
                Result.success(json.decodeFromString<List<CloudListeningHistoryItem>>(resp.bodyAsText()))
            } else {
                Result.failure(Exception("HTTP ${resp.status.value} fetching user_listening_history"))
            }
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
    ): List<CloudListeningHistoryItem> =
        fetchCloudListeningHistoryResult(userId, limit).getOrDefault(emptyList())

    /**
     * Fetches the user's search history from `public.user_search_history`, preserving errors.
     */
    suspend fun fetchCloudSearchHistoryResult(
        userId: String? = null,
        limit: Int = 1000
    ): Result<List<CloudSearchQuery>> = withContext(ioDispatcher) {
        try {
            val targetUserId = userId ?: SupabaseClient.currentUser.value?.id ?: SupabaseConfig.DEFAULT_USER.id
            val resp = getWithAuthRetry("${SupabaseConfig.SUPABASE_URL}/rest/v1/user_search_history") {
                parameter("user_id", "eq.$targetUserId")
                parameter("deleted_at", "is.null")
                parameter("order", "searched_at.desc")
                parameter("limit", limit.toString())
            }
            if (resp.status.isSuccess()) {
                Result.success(json.decodeFromString<List<CloudSearchQuery>>(resp.bodyAsText()))
            } else {
                Result.failure(Exception("HTTP ${resp.status.value} fetching user_search_history"))
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Result.failure(e)
        }
    }

    /**
     * Fetches the user's search history from `public.user_search_history`.
     */
    suspend fun fetchCloudSearchHistory(
        userId: String? = null,
        limit: Int = 1000
    ): List<CloudSearchQuery> =
        fetchCloudSearchHistoryResult(userId, limit).getOrDefault(emptyList())

    /**
     * Batch uploads search queries via `sync_push` (HLC LWW).
     */
    suspend fun uploadSearchHistory(
        queries: List<String>,
        userId: String? = null,
        hlc: Long = 0L,
        deviceId: String = "rest_compat",
    ): Result<Boolean> = withContext(ioDispatcher) {
        try {
            val validQueries = queries.filter { it.isNotBlank() }
            if (validQueries.isEmpty()) return@withContext Result.success(true)
            val effectiveHlc = nextCompatHlc(hlc)
            for (chunk in validQueries.chunked(50)) {
                val ops = buildJsonArray {
                    for (q in chunk) {
                        add(buildSyncOp(
                            entity = "search",
                            op = "U",
                            key = q,
                            hlc = effectiveHlc,
                        ))
                    }
                }
                val res = pushCompatOps(ops, deviceId)
                if (res.isFailure) return@withContext res
            }
            Result.success(true)
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

    private suspend fun executeWithAuthRetry(
        send: suspend (token: String) -> HttpResponse,
    ): HttpResponse {
        val initialToken = SupabaseClient.ensureValidAccessToken()
        var resp = send(initialToken)
        if (resp.status.value == 401 && SupabaseClient.hasValidUserSession()) {
            val refreshResult = SupabaseClient.refreshSession()
            if (refreshResult.isSuccess) {
                resp = send(SupabaseClient.getAuthToken())
            }
        }
        return resp
    }

    private suspend fun getWithAuthRetry(
        url: String,
        block: HttpRequestBuilder.() -> Unit = {},
    ): HttpResponse = executeWithAuthRetry { token ->
        SupabaseClient.httpClient.get(url) {
            header("apikey", SupabaseConfig.ANON_KEY)
            header("Authorization", "Bearer $token")
            block()
        }
    }

    private suspend fun postWithAuthRetry(
        url: String,
        payload: String,
        block: HttpRequestBuilder.() -> Unit = {},
    ): HttpResponse = executeWithAuthRetry { token ->
        SupabaseClient.httpClient.post(url) {
            header("apikey", SupabaseConfig.ANON_KEY)
            header("Authorization", "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(payload)
            block()
        }
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

            val resp = postWithAuthRetry("${SupabaseConfig.SUPABASE_URL}/rest/v1/rpc/heartbeat_presence", payload)
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
            val resp = postWithAuthRetry("${SupabaseConfig.SUPABASE_URL}/rest/v1/rpc/get_latest_presence", "{}")
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

            val resp = postWithAuthRetry("${SupabaseConfig.SUPABASE_URL}/rest/v1/rpc/record_device_session", payload)
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

            val resp = postWithAuthRetry("${SupabaseConfig.SUPABASE_URL}/rest/v1/rpc/revoke_device_session", payload)
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
            val resp = postWithAuthRetry("${SupabaseConfig.SUPABASE_URL}/rest/v1/rpc/get_my_devices", "{}")
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
