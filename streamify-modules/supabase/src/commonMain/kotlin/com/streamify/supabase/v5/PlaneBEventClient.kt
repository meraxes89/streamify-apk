package com.streamify.supabase.v5

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put

@Serializable
data class PlaybackEventInputDto(
    @SerialName("client_event_id") val clientEventId: String,
    @SerialName("track_id") val trackId: String,
    @SerialName("played_at") val playedAt: String,
    @SerialName("listened_duration_sec") val listenedDurationSec: Int,
    @SerialName("track_duration_sec") val trackDurationSec: Int? = null,
    @SerialName("source_context") val sourceContext: String = "unknown",
    @SerialName("source_id") val sourceId: String? = null,
    @SerialName("track_title") val trackTitle: String? = null,
    @SerialName("artist_name") val artistName: String? = null,
    @SerialName("album_name") val albumName: String? = null,
    @SerialName("artwork_url") val artworkUrl: String? = null,
)

@Serializable
data class PlaybackEventResultDto(
    @SerialName("client_event_id") val clientEventId: String,
    @SerialName("status") val status: String,
    @SerialName("accepted_duration_sec") val acceptedDurationSec: Int? = null,
    @SerialName("play_counted") val playCounted: Boolean? = null,
    @SerialName("delta_sec") val deltaSec: Int? = null,
    @SerialName("delta_plays") val deltaPlays: Int? = null,
    @SerialName("error_code") val errorCode: String? = null,
)

@Serializable
data class ProfileTotalsDto(
    @SerialName("total_plays") val totalPlays: Long = 0L,
    @SerialName("listening_seconds") val listeningSeconds: Long = 0L,
)

@Serializable
data class IngestPlaybackEventsResponse(
    @SerialName("status") val status: String,
    @Serializable(with = LongAsStringSerializer::class)
    @SerialName("server_time_ms") val serverTimeMs: Long? = null,
    @SerialName("profile_totals") val profileTotals: ProfileTotalsDto? = null,
    @SerialName("results") val results: List<PlaybackEventResultDto> = emptyList(),
)

@Serializable
data class ListeningHistoryCursorDto(
    @SerialName("played_at") val playedAt: String,
    @SerialName("client_event_id") val clientEventId: String,
)

@Serializable
data class ListeningHistoryItemDto(
    @SerialName("client_event_id") val clientEventId: String,
    @SerialName("track_id") val trackId: String,
    @SerialName("played_at") val playedAt: String,
    @SerialName("listened_duration_sec") val listenedDurationSec: Int,
    @SerialName("track_duration_sec") val trackDurationSec: Int? = null,
    @SerialName("play_counted") val playCounted: Boolean = false,
    @SerialName("source_context") val sourceContext: String = "unknown",
    @SerialName("source_id") val sourceId: String? = null,
    @SerialName("track_title") val trackTitle: String? = null,
    @SerialName("artist_name") val artistName: String? = null,
    @SerialName("album_name") val albumName: String? = null,
    @SerialName("artwork_url") val artworkUrl: String? = null,
)

@Serializable
data class FetchListeningHistoryResponse(
    @SerialName("status") val status: String,
    @SerialName("has_more") val hasMore: Boolean = false,
    @SerialName("next_cursor") val nextCursor: ListeningHistoryCursorDto? = null,
    @SerialName("items") val items: List<ListeningHistoryItemDto> = emptyList(),
)

@Serializable
data class SearchQueryInputDto(
    @SerialName("query") val query: String,
    @SerialName("result_type") val resultType: String = "all",
    @SerialName("searched_at") val searchedAt: String,
)

@Serializable
data class UpsertSearchQueryResponse(
    @SerialName("status") val status: String,
    @SerialName("search_cleared_at") val searchClearedAt: String? = null,
    @Serializable(with = LongAsStringSerializer::class)
    @SerialName("clear_epoch") val clearEpoch: Long = 0L,
    @SerialName("applied_count") val appliedCount: Int = 0,
    @SerialName("ignored_pre_clear_count") val ignoredPreClearCount: Int = 0,
)

@Serializable
data class DeleteSearchQueryResponse(
    @SerialName("status") val status: String,
    @SerialName("query_norm") val queryNorm: String? = null,
    @SerialName("deleted_at") val deletedAt: String? = null,
)

@Serializable
data class ClearSearchHistoryResponse(
    @SerialName("status") val status: String,
    @SerialName("search_cleared_at") val searchClearedAt: String? = null,
    @Serializable(with = LongAsStringSerializer::class)
    @SerialName("clear_epoch") val clearEpoch: Long = 0L,
    @SerialName("purged_rows") val purgedRows: Long = 0L,
)

@Serializable
data class SearchHistoryItemDto(
    @SerialName("query_norm") val queryNorm: String,
    @SerialName("query") val query: String,
    @SerialName("result_type") val resultType: String = "all",
    @SerialName("searched_at") val searchedAt: String,
    @SerialName("updated_at") val updatedAt: String,
)

@Serializable
data class SearchHistoryTombstoneDto(
    @SerialName("query_norm") val queryNorm: String,
    @SerialName("deleted_at") val deletedAt: String,
)

@Serializable
data class FetchSearchHistoryResponse(
    @SerialName("status") val status: String,
    @SerialName("server_time") val serverTime: String? = null,
    @SerialName("search_cleared_at") val searchClearedAt: String? = null,
    @Serializable(with = LongAsStringSerializer::class)
    @SerialName("clear_epoch") val clearEpoch: Long = 0L,
    @SerialName("full_reset_required") val fullResetRequired: Boolean = false,
    @SerialName("active_items") val activeItems: List<SearchHistoryItemDto> = emptyList(),
    @SerialName("tombstones") val tombstones: List<SearchHistoryTombstoneDto> = emptyList(),
)

@Serializable
data class WrappedSnapshotDto(
    @SerialName("period_key") val periodKey: String,
    @SerialName("schema_version") val schemaVersion: Int = 1,
    @SerialName("source_event_count") val sourceEventCount: Int = 0,
    @Serializable(with = LongAsStringSerializer::class)
    @SerialName("total_listening_sec") val totalListeningSec: Long = 0L,
    @SerialName("summary_json") val summaryJson: JsonObject,
    @SerialName("checksum") val checksum: String,
    @SerialName("is_finalized") val isFinalized: Boolean = false,
    @SerialName("computed_at") val computedAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
)

@Serializable
data class FetchWrappedSnapshotsResponse(
    @SerialName("status") val status: String,
    @SerialName("snapshots") val snapshots: List<WrappedSnapshotDto> = emptyList(),
)

@Serializable
data class UpsertWrappedSnapshotResponse(
    @SerialName("status") val status: String,
    @SerialName("applied") val applied: Boolean = false,
    @SerialName("period_key") val periodKey: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
)

@Serializable
data class ComputeWrappedSnapshotResponse(
    @SerialName("status") val status: String,
    @SerialName("cached") val cached: Boolean = false,
    @SerialName("snapshot") val snapshot: WrappedSnapshotDto? = null,
)

/**
 * Plane B Supabase RPC transport for append-only playback events, search history, and Wrapped snapshots.
 */
class PlaneBEventClient(
    private val executor: SupabaseRpcExecutor,
    private val json: Json = SupabaseRpcExecutor.DefaultJson,
) {
    suspend fun ingestPlaybackEvents(
        deviceId: String,
        events: List<PlaybackEventInputDto>,
    ): RpcResult<IngestPlaybackEventsResponse> {
        val body = buildJsonObject {
            put("p_device_id", deviceId)
            put("p_events", json.encodeToJsonElement(events))
        }
        return executor.invoke("ingest_playback_events", json.encodeToString(body), deviceId) { root ->
            json.decodeFromJsonElement<IngestPlaybackEventsResponse>(root)
        }
    }

    suspend fun fetchListeningHistory(
        cursorPlayedAt: String? = null,
        cursorEventId: String? = null,
        limit: Int = 50,
        since: String? = null,
    ): RpcResult<FetchListeningHistoryResponse> {
        val body = buildJsonObject {
            put("p_cursor_played_at", cursorPlayedAt)
            put("p_cursor_event_id", cursorEventId)
            put("p_limit", limit)
            put("p_since", since)
        }
        return executor.invoke("fetch_listening_history", json.encodeToString(body)) { root ->
            json.decodeFromJsonElement<FetchListeningHistoryResponse>(root)
        }
    }

    suspend fun upsertSearchQuery(
        queries: List<SearchQueryInputDto>,
    ): RpcResult<UpsertSearchQueryResponse> {
        val body = buildJsonObject {
            put("p_queries", json.encodeToJsonElement(queries))
        }
        return executor.invoke("upsert_search_query", json.encodeToString(body)) { root ->
            json.decodeFromJsonElement<UpsertSearchQueryResponse>(root)
        }
    }

    suspend fun deleteSearchQuery(
        query: String,
        deletedAt: String? = null,
    ): RpcResult<DeleteSearchQueryResponse> {
        val body = buildJsonObject {
            put("p_query", query)
            if (deletedAt != null) put("p_deleted_at", deletedAt)
        }
        return executor.invoke("delete_search_query", json.encodeToString(body)) { root ->
            json.decodeFromJsonElement<DeleteSearchQueryResponse>(root)
        }
    }

    suspend fun clearSearchHistory(
        clearedAt: String? = null,
    ): RpcResult<ClearSearchHistoryResponse> {
        val body = buildJsonObject {
            if (clearedAt != null) put("p_cleared_at", clearedAt)
        }
        return executor.invoke("clear_search_history", json.encodeToString(body)) { root ->
            json.decodeFromJsonElement<ClearSearchHistoryResponse>(root)
        }
    }

    suspend fun fetchSearchHistory(
        sinceUpdatedAt: String? = null,
        knownClearEpoch: Long = 0L,
        limit: Int = 100,
    ): RpcResult<FetchSearchHistoryResponse> {
        val body = buildJsonObject {
            put("p_since_updated_at", sinceUpdatedAt)
            put("p_known_clear_epoch", knownClearEpoch.toString())
            put("p_limit", limit)
        }
        return executor.invoke("fetch_search_history", json.encodeToString(body)) { root ->
            json.decodeFromJsonElement<FetchSearchHistoryResponse>(root)
        }
    }

    suspend fun fetchWrappedSnapshots(
        periodKeys: List<String>? = null,
    ): RpcResult<FetchWrappedSnapshotsResponse> {
        val body = buildJsonObject {
            if (periodKeys != null) {
                put("p_period_keys", json.encodeToJsonElement(periodKeys))
            } else {
                put("p_period_keys", null as String?)
            }
        }
        return executor.invoke("fetch_wrapped_snapshots", json.encodeToString(body)) { root ->
            json.decodeFromJsonElement<FetchWrappedSnapshotsResponse>(root)
        }
    }

    suspend fun upsertWrappedSnapshot(
        periodKey: String,
        schemaVersion: Int,
        sourceEventCount: Int,
        totalListeningSec: Long,
        checksum: String,
        isFinalized: Boolean,
        summaryJson: JsonObject,
        force: Boolean = false,
    ): RpcResult<UpsertWrappedSnapshotResponse> {
        val body = buildJsonObject {
            put("p_period_key", periodKey)
            put("p_schema_version", schemaVersion)
            put("p_source_event_count", sourceEventCount)
            put("p_total_listening_sec", totalListeningSec)
            put("p_checksum", checksum)
            put("p_is_finalized", isFinalized)
            put("p_summary_json", summaryJson)
            put("p_force", force)
        }
        return executor.invoke("upsert_wrapped_snapshot", json.encodeToString(body)) { root ->
            json.decodeFromJsonElement<UpsertWrappedSnapshotResponse>(root)
        }
    }

    suspend fun computeWrappedSnapshot(
        periodKey: String,
        forceRecompute: Boolean = false,
    ): RpcResult<ComputeWrappedSnapshotResponse> {
        val body = buildJsonObject {
            put("p_period_key", periodKey)
            put("p_force_recompute", forceRecompute)
        }
        return executor.invoke("compute_wrapped_snapshot", json.encodeToString(body)) { root ->
            json.decodeFromJsonElement<ComputeWrappedSnapshotResponse>(root)
        }
    }
}
