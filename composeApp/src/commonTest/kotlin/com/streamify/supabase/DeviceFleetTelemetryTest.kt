package com.streamify.supabase

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DeviceFleetTelemetryTest {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    @Test
    fun testCloudDeviceSessionSerializationAndDeserialization() {
        val session = CloudDeviceSession(
            userId = "11111111-2222-3333-4444-555555555555",
            deviceId = "dev_phone_pixel_8",
            platform = "android",
            appVersion = "1.0.3-beta",
            versionCode = 67,
            gitSha = "34d99c61",
            buildFlavor = "standard",
            deviceModel = "Google Pixel 8",
            osVersion = "Android 14 (API 34)",
            architecture = "arm64-v8a",
            firstSeenAt = "2026-11-24T12:00:00Z",
            lastSeen = "2026-11-24T14:30:00Z",
            lastActiveAt = "2026-11-24T14:30:00Z",
            isRevoked = false
        )

        val serialized = json.encodeToString(session)
        val decoded = json.decodeFromString<CloudDeviceSession>(serialized)

        assertEquals(session.userId, decoded.userId)
        assertEquals(session.deviceId, decoded.deviceId)
        assertEquals(session.platform, decoded.platform)
        assertEquals(session.appVersion, decoded.appVersion)
        assertEquals(session.versionCode, decoded.versionCode)
        assertEquals(session.gitSha, decoded.gitSha)
        assertEquals(session.buildFlavor, decoded.buildFlavor)
        assertEquals(session.deviceModel, decoded.deviceModel)
        assertEquals(session.osVersion, decoded.osVersion)
        assertEquals(session.architecture, decoded.architecture)
        assertEquals(session.isRevoked, decoded.isRevoked)
    }

    @Test
    fun testDeviceRegistrationResultDecoding() {
        val payloadOk = """{"status": "ok", "is_revoked": false}"""
        val result = json.decodeFromString<DeviceRegistrationResult>(payloadOk)
        assertEquals("ok", result.status)
        assertFalse(result.isRevoked)

        val payloadRevoked = """{"status": "ok", "is_revoked": true}"""
        val resultRevoked = json.decodeFromString<DeviceRegistrationResult>(payloadRevoked)
        assertEquals("ok", resultRevoked.status)
        assertTrue(resultRevoked.isRevoked)
    }

    @Test
    fun testDeviceRevocationResultDecoding() {
        val payload = """{"status": "ok", "device_id": "dev_phone_pixel_8", "revoked": true}"""
        val result = json.decodeFromString<DeviceRevocationResult>(payload)
        assertEquals("ok", result.status)
        assertEquals("dev_phone_pixel_8", result.deviceId)
        assertTrue(result.revoked)
    }

    @Test
    fun testPlatformDeviceInfoResolution() {
        val info = resolvePlatformDeviceInfo()
        assertNotNull(info)
        assertTrue(info.platform.isNotBlank(), "Platform must not be blank")
        assertTrue(info.architecture.isNotBlank(), "Architecture must not be blank")
        assertTrue(info.buildFlavor.isNotBlank(), "Build flavor must not be blank")
    }

    @Test
    fun testTelemetryThrottleEvaluation() {
        val telemetryIntervalMs = 3_600_000L

        // Condition 1: Cold start (never reported) -> must report
        val coldStartReportedMs = 0L
        val nowMs = 5_000_000L
        val isKeepAliveDueCold = (nowMs - coldStartReportedMs) > telemetryIntervalMs
        assertTrue(isKeepAliveDueCold)

        // Condition 2: Recent report with same version -> must NOT report
        val lastReportedMs = nowMs - 60_000L // 1 minute ago
        val currentVersionCode = 67
        val lastVersionCode = 67
        val currentGitSha = "34d99c61"
        val lastGitSha = "34d99c61"

        val isUpgradeOrRespin = (currentVersionCode != lastVersionCode && currentVersionCode > 0) ||
            (currentGitSha.isNotBlank() && currentGitSha != lastGitSha)
        val isKeepAliveDue = (nowMs - lastReportedMs) > telemetryIntervalMs

        assertFalse(isUpgradeOrRespin)
        assertFalse(isKeepAliveDue)

        // Condition 3: VersionCode bumped -> must report immediately
        val upgradedVersionCode = 68
        val isUpgradeTriggered = (upgradedVersionCode != lastVersionCode && upgradedVersionCode > 0) ||
            (currentGitSha.isNotBlank() && currentGitSha != lastGitSha)
        assertTrue(isUpgradeTriggered)

        // Condition 4: GitSha changed (e.g. nightlies / re-spins) -> must report immediately
        val respunGitSha = "a1b2c3d4"
        val isRespinTriggered = (currentVersionCode != lastVersionCode && currentVersionCode > 0) ||
            (respunGitSha.isNotBlank() && respunGitSha != lastGitSha)
        assertTrue(isRespinTriggered)
    }
}
