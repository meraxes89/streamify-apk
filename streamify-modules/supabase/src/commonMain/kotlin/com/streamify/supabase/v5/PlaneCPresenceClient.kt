package com.streamify.supabase.v5

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.put

@Serializable
data class OtherActiveDeviceDto(
    @SerialName("device_id") val deviceId: String,
    @SerialName("device_name") val deviceName: String = "Unknown Device",
    @SerialName("platform") val platform: String,
    @SerialName("is_playing") val isPlaying: Boolean = false,
    @SerialName("expires_at") val expiresAt: String,
)

@Serializable
data class HeartbeatPresenceV2Response(
    @SerialName("status") val status: String,
    @Serializable(with = LongAsStringSerializer::class)
    @SerialName("server_time_ms") val serverTimeMs: Long? = null,
    @Serializable(with = LongAsStringSerializer::class)
    @SerialName("state_seq") val stateSeq: Long = 0L,
    @SerialName("expires_at") val expiresAt: String? = null,
    @SerialName("other_active_devices") val otherActiveDevices: List<OtherActiveDeviceDto> = emptyList(),
)

@Serializable
data class StopPresenceResponse(
    @SerialName("status") val status: String,
    @Serializable(with = LongAsStringSerializer::class)
    @SerialName("server_time_ms") val serverTimeMs: Long? = null,
    @SerialName("handoff_saved") val handoffSaved: Boolean = false,
)

@Serializable
data class LivePresenceDeviceDto(
    @SerialName("device_id") val deviceId: String,
    @SerialName("device_name") val deviceName: String = "Unknown Device",
    @SerialName("platform") val platform: String,
    @SerialName("is_playing") val isPlaying: Boolean = false,
    @SerialName("track_id") val trackId: String? = null,
    @SerialName("anchor_position_ms") val anchorPositionMs: Long = 0L,
    @SerialName("effective_position_ms") val effectivePositionMs: Long = 0L,
    @SerialName("duration_ms") val durationMs: Long? = null,
    @SerialName("playback_rate") val playbackRate: Float = 1.0f,
    @SerialName("track_title") val trackTitle: String? = null,
    @SerialName("artist_name") val artistName: String? = null,
    @SerialName("artwork_url") val artworkUrl: String? = null,
    @SerialName("state_captured_at") val stateCapturedAt: String? = null,
    @SerialName("expires_at") val expiresAt: String? = null,
)

@Serializable
data class PlaybackHandoffDto(
    @SerialName("device_id") val deviceId: String,
    @SerialName("device_name") val deviceName: String = "Unknown Device",
    @SerialName("platform") val platform: String,
    @SerialName("track_id") val trackId: String,
    @SerialName("position_ms") val positionMs: Long = 0L,
    @SerialName("duration_ms") val durationMs: Long? = null,
    @SerialName("source_context") val sourceContext: String = "unknown",
    @SerialName("source_id") val sourceId: String? = null,
    @SerialName("track_title") val trackTitle: String? = null,
    @SerialName("artist_name") val artistName: String? = null,
    @SerialName("artwork_url") val artworkUrl: String? = null,
    @Serializable(with = LongAsStringSerializer::class)
    @SerialName("handoff_epoch") val handoffEpoch: Long = 1L,
    @SerialName("captured_at") val capturedAt: String? = null,
)

@Serializable
data class GetLivePresenceResponse(
    @SerialName("status") val status: String,
    @Serializable(with = LongAsStringSerializer::class)
    @SerialName("server_time_ms") val serverTimeMs: Long? = null,
    @SerialName("live_devices") val liveDevices: List<LivePresenceDeviceDto> = emptyList(),
    @SerialName("handoff") val handoff: PlaybackHandoffDto? = null,
)

@Serializable
data class RecordDeviceSessionResponse(
    @SerialName("status") val status: String,
    @Serializable(with = LongAsStringSerializer::class)
    @SerialName("server_time_ms") val serverTimeMs: Long? = null,
    @SerialName("device_id") val deviceId: String? = null,
    @SerialName("platform") val platform: String? = null,
    @SerialName("revoked_at") val revokedAt: String? = null,
)

@Serializable
data class RevokeDeviceSessionResponse(
    @SerialName("status") val status: String,
    @SerialName("revoked_device_id") val revokedDeviceId: String? = null,
    @SerialName("revoked_at") val revokedAt: String? = null,
    @SerialName("presence_terminated") val presenceTerminated: Boolean = false,
)

@Serializable
data class MyDeviceDto(
    @SerialName("device_id") val deviceId: String,
    @SerialName("device_name") val deviceName: String = "Unknown Device",
    @SerialName("platform") val platform: String,
    @SerialName("os_version") val osVersion: String? = null,
    @SerialName("device_model") val deviceModel: String? = null,
    @SerialName("app_version") val appVersion: String = "unknown",
    @SerialName("version_code") val versionCode: Int = 0,
    @SerialName("build_flavor") val buildFlavor: String = "prod",
    @SerialName("first_seen_at") val firstSeenAt: String? = null,
    @SerialName("last_active_at") val lastActiveAt: String? = null,
    @SerialName("status") val status: String = "offline",
)

@Serializable
data class GetMyDevicesResponse(
    @SerialName("status") val status: String,
    @Serializable(with = LongAsStringSerializer::class)
    @SerialName("server_time_ms") val serverTimeMs: Long? = null,
    @SerialName("devices") val devices: List<MyDeviceDto> = emptyList(),
)

/**
 * Plane C Supabase RPC transport for presence leases, Connect handoff, and device fleet management.
 */
class PlaneCPresenceClient(
    private val executor: SupabaseRpcExecutor,
    private val json: Json = SupabaseRpcExecutor.DefaultJson,
) {
    suspend fun heartbeatPresenceV2(
        deviceId: String,
        deviceName: String,
        platform: String,
        isPlaying: Boolean,
        trackId: String? = null,
        positionMs: Long = 0L,
        durationMs: Long? = null,
        playbackRate: Float = 1.0f,
        volumePct: Int? = null,
        leaseTtlSec: Int = 45,
        sourceContext: String = "unknown",
        sourceId: String? = null,
        trackTitle: String? = null,
        artistName: String? = null,
        artworkUrl: String? = null,
    ): RpcResult<HeartbeatPresenceV2Response> {
        val body = buildJsonObject {
            put("p_device_id", deviceId)
            put("p_device_name", deviceName)
            put("p_platform", platform)
            put("p_is_playing", isPlaying)
            put("p_track_id", trackId)
            put("p_position_ms", positionMs)
            put("p_duration_ms", durationMs)
            put("p_playback_rate", playbackRate)
            put("p_volume_pct", volumePct)
            put("p_lease_ttl_sec", leaseTtlSec)
            put("p_source_context", sourceContext)
            put("p_source_id", sourceId)
            put("p_track_title", trackTitle)
            put("p_artist_name", artistName)
            put("p_artwork_url", artworkUrl)
        }
        return executor.invoke("heartbeat_presence_v2", json.encodeToString(body), deviceId) { root ->
            json.decodeFromJsonElement<HeartbeatPresenceV2Response>(root)
        }
    }

    suspend fun stopPresence(
        deviceId: String,
        reason: String = "stopped",
        saveHandoff: Boolean = true,
        trackId: String? = null,
        positionMs: Long = 0L,
        durationMs: Long? = null,
        sourceContext: String = "unknown",
        sourceId: String? = null,
        trackTitle: String? = null,
        artistName: String? = null,
        artworkUrl: String? = null,
    ): RpcResult<StopPresenceResponse> {
        val body = buildJsonObject {
            put("p_device_id", deviceId)
            put("p_reason", reason)
            put("p_save_handoff", saveHandoff)
            put("p_track_id", trackId)
            put("p_position_ms", positionMs)
            put("p_duration_ms", durationMs)
            put("p_source_context", sourceContext)
            put("p_source_id", sourceId)
            put("p_track_title", trackTitle)
            put("p_artist_name", artistName)
            put("p_artwork_url", artworkUrl)
        }
        return executor.invoke("stop_presence", json.encodeToString(body), deviceId) { root ->
            json.decodeFromJsonElement<StopPresenceResponse>(root)
        }
    }

    suspend fun getLivePresence(
        excludeDeviceId: String? = null,
    ): RpcResult<GetLivePresenceResponse> {
        val body = buildJsonObject {
            put("p_exclude_device_id", excludeDeviceId)
        }
        return executor.invoke("get_live_presence", json.encodeToString(body)) { root ->
            json.decodeFromJsonElement<GetLivePresenceResponse>(root)
        }
    }

    suspend fun recordDeviceSession(
        deviceId: String,
        deviceName: String,
        platform: String,
        osVersion: String? = null,
        deviceModel: String? = null,
        appVersion: String = "unknown",
        versionCode: Int = 0,
        buildFlavor: String = "prod",
    ): RpcResult<RecordDeviceSessionResponse> {
        val body = buildJsonObject {
            put("p_device_id", deviceId)
            put("p_device_name", deviceName)
            put("p_platform", platform)
            put("p_os_version", osVersion)
            put("p_device_model", deviceModel)
            put("p_app_version", appVersion)
            put("p_version_code", versionCode)
            put("p_build_flavor", buildFlavor)
        }
        return executor.invoke("record_device_session", json.encodeToString(body), deviceId) { root ->
            json.decodeFromJsonElement<RecordDeviceSessionResponse>(root)
        }
    }

    suspend fun revokeDeviceSession(
        targetDeviceId: String,
        actorDeviceId: String? = null,
        reason: String = "user_revoked_from_settings",
        allowSelf: Boolean = false,
    ): RpcResult<RevokeDeviceSessionResponse> {
        val body = buildJsonObject {
            put("p_target_device_id", targetDeviceId)
            put("p_actor_device_id", actorDeviceId)
            put("p_reason", reason)
            put("p_allow_self", allowSelf)
        }
        return executor.invoke("revoke_device_session", json.encodeToString(body), actorDeviceId) { root ->
            json.decodeFromJsonElement<RevokeDeviceSessionResponse>(root)
        }
    }

    suspend fun getMyDevices(): RpcResult<GetMyDevicesResponse> =
        executor.invoke("get_my_devices", "{}") { root ->
            json.decodeFromJsonElement<GetMyDevicesResponse>(root)
        }
}
