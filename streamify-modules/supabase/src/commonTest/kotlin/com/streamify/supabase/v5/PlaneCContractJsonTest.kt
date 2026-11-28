package com.streamify.supabase.v5

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaneCContractJsonTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun decodesHeartbeatPresenceV2Response() {
        val raw = """
            {
              "status": "OK",
              "server_time_ms": "1795740100250",
              "state_seq": "14",
              "expires_at": "2026-11-28T00:42:25.250Z",
              "other_active_devices": [
                {
                  "device_id": "desktop_44bc90",
                  "device_name": "MacBook Pro",
                  "platform": "desktop",
                  "is_playing": false,
                  "expires_at": "2026-11-28T00:42:10.000Z"
                }
              ]
            }
        """.trimIndent()
        val parsed = json.decodeFromString(HeartbeatPresenceV2Response.serializer(), raw)
        assertEquals("OK", parsed.status)
        assertEquals(1795740100250L, parsed.serverTimeMs)
        assertEquals(14L, parsed.stateSeq)
        assertEquals("desktop_44bc90", parsed.otherActiveDevices.single().deviceId)
    }

    @Test
    fun decodesGetLivePresenceAndHandoff() {
        val raw = """
            {
              "status": "OK",
              "server_time_ms": "1795740130000",
              "live_devices": [
                {
                  "device_id": "android_7f9a2c11",
                  "device_name": "Pixel 9 Pro",
                  "platform": "android",
                  "is_playing": true,
                  "track_id": "trk_987",
                  "anchor_position_ms": 64200,
                  "effective_position_ms": 93950,
                  "duration_ms": 214000,
                  "playback_rate": 1.0
                }
              ],
              "handoff": {
                "device_id": "android_7f9a2c11",
                "device_name": "Pixel 9 Pro",
                "platform": "android",
                "track_id": "trk_987",
                "position_ms": 89400,
                "handoff_epoch": "15"
              }
            }
        """.trimIndent()
        val parsed = json.decodeFromString(GetLivePresenceResponse.serializer(), raw)
        assertTrue(parsed.liveDevices.single().isPlaying)
        assertEquals(15L, parsed.handoff?.handoffEpoch)
        assertEquals(89400L, parsed.handoff?.positionMs)
    }

    @Test
    fun decodesGetMyDevicesAndRevokeResponse() {
        val rawDevices = """
            {
              "status": "OK",
              "server_time_ms": "1795740130000",
              "devices": [
                {
                  "device_id": "android_1",
                  "device_name": "Pixel",
                  "platform": "android",
                  "app_version": "5.0.0",
                  "version_code": 50000,
                  "status": "playing"
                }
              ]
            }
        """.trimIndent()
        val devices = json.decodeFromString(GetMyDevicesResponse.serializer(), rawDevices)
        assertEquals("playing", devices.devices.single().status)

        val rawRevoke = """
            {
              "status": "OK",
              "revoked_device_id": "desktop_1",
              "revoked_at": "2026-11-28T00:45:00Z",
              "presence_terminated": false
            }
        """.trimIndent()
        val revoke = json.decodeFromString(RevokeDeviceSessionResponse.serializer(), rawRevoke)
        assertEquals("desktop_1", revoke.revokedDeviceId)
        assertFalse(revoke.presenceTerminated)
    }
}
