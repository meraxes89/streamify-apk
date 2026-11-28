package com.streamify.sync.v5.planec

import com.streamify.supabase.v5.HeartbeatPresenceV2Response
import com.streamify.supabase.v5.PlaneCPresenceClient
import com.streamify.supabase.v5.RpcResult
import com.streamify.supabase.v5.StopPresenceResponse
import kotlin.math.max
import kotlin.math.min

enum class PresenceLeaseState {
    IDLE,
    PLAYING_LEASE_ACTIVE,
    VIEWING_CONNECT,
}

data class PlaybackLeaseSnapshot(
    val deviceId: String,
    val deviceName: String,
    val platform: String,
    val isPlaying: Boolean,
    val trackId: String? = null,
    val positionMs: Long = 0L,
    val durationMs: Long? = null,
    val playbackRate: Float = 1.0f,
    val volumePct: Int? = null,
    val sourceContext: String = "unknown",
    val sourceId: String? = null,
    val trackTitle: String? = null,
    val artistName: String? = null,
    val artworkUrl: String? = null,
    val isHiddenWebTab: Boolean = false,
)

/**
 * Event-driven Plane C lease controller.
 * Keeps 0 RPCs/min while paused/idle, 25s heartbeat (45s TTL, or 70s for hidden WasmJS tabs),
 * and calls `stop_presence` immediately on pause/stop/sign-out.
 */
class PresenceLeaseClient(
    private val client: PlaneCPresenceClient,
    private val nowMs: () -> Long,
) {
    var state: PresenceLeaseState = PresenceLeaseState.IDLE
        private set

    var lastHeartbeatAtMs: Long = 0L
        private set

    fun recommendedTtlSec(isHiddenWebTab: Boolean): Int =
        if (isHiddenWebTab) HIDDEN_WEB_LEASE_TTL_SEC else DEFAULT_LEASE_TTL_SEC

    fun recommendedHeartbeatIntervalMs(isHiddenWebTab: Boolean): Long =
        if (isHiddenWebTab) HIDDEN_WEB_HEARTBEAT_INTERVAL_MS else DEFAULT_HEARTBEAT_INTERVAL_MS

    suspend fun sendPlaybackHeartbeat(
        snapshot: PlaybackLeaseSnapshot,
    ): RpcResult<HeartbeatPresenceV2Response> {
        val ttl = recommendedTtlSec(snapshot.isHiddenWebTab)
        val res = client.heartbeatPresenceV2(
            deviceId = snapshot.deviceId,
            deviceName = snapshot.deviceName,
            platform = snapshot.platform,
            isPlaying = snapshot.isPlaying,
            trackId = snapshot.trackId,
            positionMs = snapshot.positionMs,
            durationMs = snapshot.durationMs,
            playbackRate = snapshot.playbackRate,
            volumePct = snapshot.volumePct,
            leaseTtlSec = ttl,
            sourceContext = snapshot.sourceContext,
            sourceId = snapshot.sourceId,
            trackTitle = snapshot.trackTitle,
            artistName = snapshot.artistName,
            artworkUrl = snapshot.artworkUrl,
        )
        if (res is RpcResult.Ok && res.data.status == "OK") {
            lastHeartbeatAtMs = nowMs()
            state = if (snapshot.isPlaying) PresenceLeaseState.PLAYING_LEASE_ACTIVE else PresenceLeaseState.IDLE
        }
        return res
    }

    suspend fun stopLease(
        snapshot: PlaybackLeaseSnapshot,
        reason: String = "paused",
        saveHandoff: Boolean = true,
    ): RpcResult<StopPresenceResponse> {
        val res = client.stopPresence(
            deviceId = snapshot.deviceId,
            reason = reason,
            saveHandoff = saveHandoff,
            trackId = snapshot.trackId,
            positionMs = snapshot.positionMs,
            durationMs = snapshot.durationMs,
            sourceContext = snapshot.sourceContext,
            sourceId = snapshot.sourceId,
            trackTitle = snapshot.trackTitle,
            artistName = snapshot.artistName,
            artworkUrl = snapshot.artworkUrl,
        )
        state = PresenceLeaseState.IDLE
        return res
    }

    fun setViewingConnect(viewing: Boolean) {
        if (state != PresenceLeaseState.PLAYING_LEASE_ACTIVE) {
            state = if (viewing) PresenceLeaseState.VIEWING_CONNECT else PresenceLeaseState.IDLE
        }
    }

    companion object {
        const val DEFAULT_HEARTBEAT_INTERVAL_MS = 25_000L
        const val HIDDEN_WEB_HEARTBEAT_INTERVAL_MS = 45_000L
        const val DEFAULT_LEASE_TTL_SEC = 45
        const val HIDDEN_WEB_LEASE_TTL_SEC = 70

        fun deadReckonPositionMs(
            anchorPositionMs: Long,
            durationMs: Long?,
            playbackRate: Float,
            isPlaying: Boolean,
            elapsedSinceCaptureMs: Long,
        ): Long {
            if (!isPlaying) return max(0L, anchorPositionMs)
            val delta = max(0L, (elapsedSinceCaptureMs * playbackRate).toLong())
            val raw = max(0L, anchorPositionMs + delta)
            return if (durationMs != null && durationMs >= 0L) min(durationMs, raw) else raw
        }
    }
}
