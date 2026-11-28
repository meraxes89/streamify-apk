package com.streamify.sync.v5

import com.streamify.supabase.v5.PlaneCPresenceClient
import com.streamify.supabase.v5.RpcHttpTransport
import com.streamify.supabase.v5.RpcResult
import com.streamify.supabase.v5.RpcTransportResponse
import com.streamify.supabase.v5.SupabaseRpcExecutor
import com.streamify.sync.v5.planec.DeviceMetadata
import com.streamify.sync.v5.planec.DeviceSessionRegistrar
import com.streamify.sync.v5.planec.PlaybackLeaseSnapshot
import com.streamify.sync.v5.planec.PresenceLeaseClient
import com.streamify.sync.v5.planec.PresenceLeaseState
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlaneCV5Test {

    private fun fakeClient(
        handler: suspend (rpcName: String, bodyJson: String) -> RpcTransportResponse,
    ): Pair<SupabaseRpcExecutor, PlaneCPresenceClient> {
        val transport = object : RpcHttpTransport {
            override suspend fun postRpc(
                rpcName: String,
                bodyJson: String,
                accessToken: String,
            ): RpcTransportResponse = handler(rpcName, bodyJson)
        }
        val executor = SupabaseRpcExecutor(
            transport = transport,
            accessTokenProvider = { "token" },
            refreshSession = { true },
        )
        return executor to PlaneCPresenceClient(executor)
    }

    @Test
    fun deviceSessionRegistrarThrottlesForSixHoursAndRefreshesOnVersionChange() = runTest {
        var now = 1_000L
        var calls = 0
        val (_, client) = fakeClient { rpcName, _ ->
            assertEquals("record_device_session", rpcName)
            calls++
            RpcTransportResponse(200, """{"status":"OK","device_id":"dev-1","platform":"android"}""")
        }
        val registrar = DeviceSessionRegistrar(client) { now }
        val metaV1 = DeviceMetadata("dev-1", "Pixel", "android", appVersion = "5.0.0", versionCode = 500)

        assertNotNull(registrar.registerIfNeeded(metaV1))
        assertNull(registrar.registerIfNeeded(metaV1)) // Throttled within 6h
        assertEquals(1, calls)

        // Version bump triggers immediate re-registration
        val metaV2 = metaV1.copy(appVersion = "5.0.1", versionCode = 501)
        assertNotNull(registrar.registerIfNeeded(metaV2))
        assertEquals(2, calls)

        // 6 hours later triggers refresh
        now += DeviceSessionRegistrar.REFRESH_INTERVAL_MS
        assertNotNull(registrar.registerIfNeeded(metaV2))
        assertEquals(3, calls)
    }

    @Test
    fun presenceLeaseTransitionsAndDeadReckonsPosition() = runTest {
        val (_, client) = fakeClient { rpcName, _ ->
            when (rpcName) {
                "heartbeat_presence_v2" -> RpcTransportResponse(
                    200,
                    """{"status":"OK","state_seq":"1","expires_at":"2026-11-28T00:42:25Z"}""",
                )
                "stop_presence" -> RpcTransportResponse(
                    200,
                    """{"status":"OK","handoff_saved":true}""",
                )
                else -> error("Unexpected $rpcName")
            }
        }
        val lease = PresenceLeaseClient(client) { 5_000L }
        val snap = PlaybackLeaseSnapshot(
            deviceId = "dev-1",
            deviceName = "Pixel",
            platform = "android",
            isPlaying = true,
            trackId = "trk-1",
            positionMs = 10_000L,
            durationMs = 20_000L,
        )

        val hb = lease.sendPlaybackHeartbeat(snap)
        assertTrue(hb is RpcResult.Ok)
        assertEquals(PresenceLeaseState.PLAYING_LEASE_ACTIVE, lease.state)
        assertEquals(70, lease.recommendedTtlSec(isHiddenWebTab = true))
        assertEquals(45, lease.recommendedTtlSec(isHiddenWebTab = false))

        assertEquals(
            20_000L,
            PresenceLeaseClient.deadReckonPositionMs(
                anchorPositionMs = 15_000L,
                durationMs = 20_000L,
                playbackRate = 1.0f,
                isPlaying = true,
                elapsedSinceCaptureMs = 9_000L,
            ),
        )

        val stop = lease.stopLease(snap, reason = "paused", saveHandoff = true)
        assertTrue(stop is RpcResult.Ok)
        assertEquals(PresenceLeaseState.IDLE, lease.state)
    }
}
