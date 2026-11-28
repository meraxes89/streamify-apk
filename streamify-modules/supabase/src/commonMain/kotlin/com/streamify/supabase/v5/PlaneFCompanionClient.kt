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
data class HandoffQueueDto(
    @Serializable(with = LongAsStringSerializer::class)
    @SerialName("handoff_epoch") val handoffEpoch: Long = 1L,
    @SerialName("track_ids") val trackIds: List<String> = emptyList(),
    @SerialName("queue_index") val queueIndex: Int = 0,
    @SerialName("shuffle_enabled") val shuffleEnabled: Boolean = false,
    @SerialName("repeat_mode") val repeatMode: String = "off",
    @SerialName("updated_at") val updatedAt: String? = null,
)

@Serializable
data class UpdateActiveSessionV5Response(
    @SerialName("status") val status: String,
    @Serializable(with = LongAsStringSerializer::class)
    @SerialName("server_time_ms") val serverTimeMs: Long? = null,
    @Serializable(with = LongAsStringSerializer::class)
    @SerialName("handoff_epoch") val handoffEpoch: Long = 0L,
    @SerialName("queue_saved") val queueSaved: Boolean = false,
)

@Serializable
data class FetchActiveSessionV5Response(
    @SerialName("status") val status: String,
    @Serializable(with = LongAsStringSerializer::class)
    @SerialName("server_time_ms") val serverTimeMs: Long? = null,
    @SerialName("live_devices") val liveDevices: List<LivePresenceDeviceDto> = emptyList(),
    @SerialName("handoff") val handoff: PlaybackHandoffDto? = null,
    @SerialName("queue") val queue: HandoffQueueDto? = null,
    @SerialName("catalog") val catalog: JsonObject? = null,
)

@Serializable
data class HybridShelfTrackDto(
    @SerialName("track_id") val trackId: String,
    @SerialName("title") val title: String,
    @SerialName("artist_name") val artistName: String,
    @SerialName("album_name") val albumName: String? = null,
    @SerialName("duration_sec") val durationSec: Int = 0,
    @SerialName("thumb_url") val thumbUrl: String? = null,
    @SerialName("metadata_authority") val metadataAuthority: Int = 50,
    @SerialName("catalog_version") val catalogVersion: Int = 1,
    @SerialName("signal") val signal: String = "community",
)

@Serializable
data class HybridHomeShelvesDataDto(
    @SerialName("community_hits") val communityHits: List<HybridShelfTrackDto> = emptyList(),
    @SerialName("circadian_daypart") val circadianDaypart: List<HybridShelfTrackDto> = emptyList(),
)

@Serializable
data class GetHybridHomeShelvesResponse(
    @SerialName("status") val status: String,
    @SerialName("daypart") val daypart: String? = null,
    @SerialName("refreshed_at") val refreshedAt: String? = null,
    @SerialName("ttl_sec") val ttlSec: Int = 900,
    @SerialName("shelves") val shelves: HybridHomeShelvesDataDto? = null,
)

/**
 * Plane F Supabase RPC transport for companion handoff, k-anonymous home shelves,
 * and admin-gated analytics/fleet RPCs.
 */
class PlaneFCompanionClient(
    private val executor: SupabaseRpcExecutor,
    private val json: Json = SupabaseRpcExecutor.DefaultJson,
) {
    suspend fun updateActiveSessionV5(
        deviceId: String,
        deviceName: String,
        platform: String,
        isPlaying: Boolean,
        trackId: String,
        positionMs: Long = 0L,
        durationMs: Long? = null,
        playbackRate: Float = 1.0f,
        sourceContext: String = "unknown",
        sourceId: String? = null,
        trackTitle: String? = null,
        artistName: String? = null,
        artworkUrl: String? = null,
        queueTrackIds: List<String>? = null,
        queueIndex: Int = 0,
        shuffleEnabled: Boolean = false,
        repeatMode: String = "off",
    ): RpcResult<UpdateActiveSessionV5Response> {
        val body = buildJsonObject {
            put("p_device_id", deviceId)
            put("p_device_name", deviceName)
            put("p_platform", platform)
            put("p_is_playing", isPlaying)
            put("p_track_id", trackId)
            put("p_position_ms", positionMs)
            put("p_duration_ms", durationMs)
            put("p_playback_rate", playbackRate)
            put("p_source_context", sourceContext)
            put("p_source_id", sourceId)
            put("p_track_title", trackTitle)
            put("p_artist_name", artistName)
            put("p_artwork_url", artworkUrl)
            if (queueTrackIds != null) put("p_queue_track_ids", json.encodeToJsonElement(queueTrackIds))
            else put("p_queue_track_ids", null as String?)
            put("p_queue_index", queueIndex)
            put("p_shuffle_enabled", shuffleEnabled)
            put("p_repeat_mode", repeatMode)
        }
        return executor.invoke("update_active_session_v5", json.encodeToString(body), deviceId) { root ->
            json.decodeFromJsonElement<UpdateActiveSessionV5Response>(root)
        }
    }

    suspend fun fetchActiveSessionV5(
        excludeDeviceId: String? = null,
        includeCatalog: Boolean = true,
    ): RpcResult<FetchActiveSessionV5Response> {
        val body = buildJsonObject {
            put("p_exclude_device_id", excludeDeviceId)
            put("p_include_catalog", includeCatalog)
        }
        return executor.invoke("fetch_active_session_v5", json.encodeToString(body)) { root ->
            json.decodeFromJsonElement<FetchActiveSessionV5Response>(root)
        }
    }

    suspend fun getHybridHomeShelves(
        tzOffsetMin: Int = 0,
        daypart: String? = null,
        limitPerShelf: Int = 20,
        ifModifiedSince: String? = null,
    ): RpcResult<GetHybridHomeShelvesResponse> {
        val body = buildJsonObject {
            put("p_tz_offset_min", tzOffsetMin)
            put("p_daypart", daypart)
            put("p_limit_per_shelf", limitPerShelf)
            put("p_if_modified_since", ifModifiedSince)
        }
        return executor.invoke("get_hybrid_home_shelves", json.encodeToString(body)) { root ->
            json.decodeFromJsonElement<GetHybridHomeShelvesResponse>(root)
        }
    }

    suspend fun getAdminDashboardStats(): RpcResult<JsonObject> =
        executor.invoke("get_admin_dashboard_stats", "{}") { it }

    suspend fun getAdminEdgeComputeStats(): RpcResult<JsonObject> =
        executor.invoke("get_admin_edge_compute_stats", "{}") { it }

    suspend fun getAdminTopTracks(limit: Int = 25, windowDays: Int = 30): RpcResult<JsonObject> {
        val body = buildJsonObject {
            put("p_limit", limit)
            put("p_window_days", windowDays)
        }
        return executor.invoke("get_admin_top_tracks", json.encodeToString(body)) { it }
    }

    suspend fun getAdminFleetOverview(): RpcResult<JsonObject> =
        executor.invoke("get_admin_fleet_overview", "{}") { it }
}
