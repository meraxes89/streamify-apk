package com.streamify.supabase

import com.streamify.supabase.v5.PlaneBEventClient
import com.streamify.supabase.v5.PlaybackEventInputDto
import com.streamify.supabase.v5.RpcResult
import com.streamify.supabase.v5.SupabaseRpcExecutor
import com.streamify.supabase.v5.WrappedSnapshotDto
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Continuous Cloud Backup for Listening Telemetry & Wrapped Analytics (Sync v5 Plane B).
 */
object SupabaseWrappedSync {

    private val client by lazy { PlaneBEventClient(SupabaseRpcExecutor.default()) }

    /**
     * Emits one playback event to Supabase via `ingest_playback_events`.
     * Uses a caller-provided [clientEventId] or a deterministic session UUID derived from
     * `(userId, videoId, timestamp)` so transient retries never double-count plays.
     */
    @OptIn(ExperimentalUuidApi::class)
    suspend fun recordPlaybackEvent(
        event: PlaybackTelemetryEvent,
        clientEventId: String? = null,
        deviceId: String = "kmp_telemetry",
    ): Boolean {
        val user = SupabaseClient.currentUser.value ?: return false
        if (event.userId != user.id) return false

        val stableEventId = clientEventId?.takeIf { it.isNotBlank() } ?: deterministicEventUuid(event)
        val playedAtIso = Instant.fromEpochMilliseconds(event.timestamp).toString()
        val payload = PlaybackEventInputDto(
            clientEventId = stableEventId,
            trackId = event.videoId,
            playedAt = playedAtIso,
            listenedDurationSec = event.listenedSeconds.coerceIn(0L, 14400L).toInt(),
            trackDurationSec = event.durationSeconds.takeIf { it in 1L..14400L }?.toInt(),
            sourceContext = "telemetry",
            trackTitle = event.trackTitle,
            artistName = event.artistName,
        )
        return when (val res = client.ingestPlaybackEvents(deviceId.ifBlank { "kmp_telemetry" }, listOf(payload))) {
            is RpcResult.Ok -> res.data.status == "OK"
            else -> false
        }
    }

    @OptIn(ExperimentalUuidApi::class)
    private fun deterministicEventUuid(event: PlaybackTelemetryEvent): String {
        val seed = "${event.userId}|${event.videoId}|${event.timestamp}"
        var h1 = -3750763034362895579L
        var h2 = 1099511628211L
        for (ch in seed) {
            h1 = (h1 xor ch.code.toLong()) * 1099511628211L
            h2 = (h2 * 31L) xor (ch.code.toLong() + 0x9e3779b97f4a7c15L)
        }
        return Uuid.fromLongs(h1, h2).toString()
    }

    /**
     * Backs up the calculated annual Wrapped snapshot to Supabase `user_wrapped_snapshots`.
     */
    suspend fun uploadWrappedSnapshot(snapshot: WrappedCloudSnapshot): Boolean {
        val user = SupabaseClient.currentUser.value ?: return false
        if (snapshot.userId != user.id) return false

        val summary = buildJsonObject {
            put("top_songs_json", snapshot.topSongsJson)
            put("top_artists_json", snapshot.topArtistsJson)
            put("top_albums_json", snapshot.topAlbumsJson)
            put("biggest_day", snapshot.biggestDay)
            put("dominant_genre", snapshot.dominantGenre)
            put("persona_headline", snapshot.personaHeadline)
            put("persona_summary", snapshot.personaSummary)
        }
        val checksum = "v1:${snapshot.year}:${snapshot.totalMinutes}:${snapshot.topSongsJson.hashCode()}:${snapshot.topArtistsJson.hashCode()}"
        val res = client.upsertWrappedSnapshot(
            periodKey = snapshot.year.toString(),
            schemaVersion = 1,
            sourceEventCount = snapshot.totalMinutes.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt(),
            totalListeningSec = (snapshot.totalMinutes * 60L).coerceAtLeast(0L),
            checksum = checksum,
            isFinalized = false,
            summaryJson = summary,
        )
        return res is RpcResult.Ok && res.data.status == "OK" && res.data.applied
    }

    /**
     * Restores a cloud Wrapped snapshot when local Room database has insufficient history.
     */
    suspend fun fetchCloudWrappedSnapshot(year: Int): WrappedCloudSnapshot? {
        val user = SupabaseClient.currentUser.value ?: return null
        val periodKey = year.toString()
        val fetched = when (val res = client.fetchWrappedSnapshots(listOf(periodKey))) {
            is RpcResult.Ok -> res.data.snapshots.firstOrNull()
            else -> null
        } ?: when (val computed = client.computeWrappedSnapshot(periodKey)) {
            is RpcResult.Ok -> computed.data.snapshot
            else -> null
        } ?: return null

        return fetched.toLegacyCloudSnapshot(user.id, year)
    }

    suspend fun fetchSnapshots(periodKeys: List<String>? = null): List<WrappedSnapshotDto> {
        return when (val res = client.fetchWrappedSnapshots(periodKeys)) {
            is RpcResult.Ok -> res.data.snapshots
            else -> emptyList()
        }
    }

    suspend fun computeServerWrapped(
        periodKey: String,
        forceRecompute: Boolean = false,
    ): WrappedSnapshotDto? {
        return when (val res = client.computeWrappedSnapshot(periodKey, forceRecompute)) {
            is RpcResult.Ok -> res.data.snapshot
            else -> null
        }
    }

    private fun WrappedSnapshotDto.toLegacyCloudSnapshot(userId: String, year: Int): WrappedCloudSnapshot {
        fun JsonObject.str(key: String): String =
            this[key]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }.orEmpty()

        val topSongs = summaryJson["top_tracks"]?.toString()
            ?: summaryJson.str("top_songs_json").ifEmpty { "[]" }
        val topArtists = summaryJson["top_artists"]?.toString()
            ?: summaryJson.str("top_artists_json").ifEmpty { "[]" }
        val topAlbums = summaryJson.str("top_albums_json").ifEmpty { "[]" }

        return WrappedCloudSnapshot(
            userId = userId,
            year = year,
            totalMinutes = totalListeningSec / 60L,
            topSongsJson = topSongs,
            topArtistsJson = topArtists,
            topAlbumsJson = topAlbums,
            biggestDay = summaryJson.str("biggest_day"),
            dominantGenre = summaryJson.str("dominant_genre"),
            personaHeadline = summaryJson.str("persona_headline"),
            personaSummary = summaryJson.str("persona_summary"),
            updatedAt = updatedAt?.let { runCatching { Instant.parse(it).toEpochMilliseconds() }.getOrNull() } ?: 0L,
        )
    }
}
