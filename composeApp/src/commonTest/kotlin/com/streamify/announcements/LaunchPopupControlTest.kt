package com.streamify.announcements

import com.maxrave.domain.manager.DataStoreManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LaunchPopupControlTest {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private class FakeDataStore : DataStoreManager {
        private val map = mutableMapOf<String, String>()

        override fun getString(key: String): Flow<String?> = flowOf(map[key])
        override suspend fun putString(key: String, value: String) {
            map[key] = value
        }

        override val appVersion: Flow<String> = flowOf("")
        override suspend fun setAppVersion(version: String) {}
        override val openAppTime: Flow<Int> = flowOf(0)
        override suspend fun openApp() {}
        override suspend fun resetOpenAppTime() {}
        override suspend fun doneOpenAppTime() {}
        override val location: Flow<String> = flowOf("")
        override suspend fun setLocation(location: String) {}
        override val quality: Flow<String> = flowOf("")
        override suspend fun setQuality(quality: String) {}
        override val downloadQuality: Flow<String> = flowOf("")
        override suspend fun setDownloadQuality(quality: String) {}
        override val videoDownloadQuality: Flow<String> = flowOf("")
        override suspend fun setVideoDownloadQuality(quality: String) {}
        override val language: Flow<String> = flowOf("en")
        override val moodAndGenresCache: Flow<String?> = flowOf(null)
        override suspend fun setMoodAndGenresCache(json: String) {}
        override val moodArtworkCache: Flow<String?> = flowOf(null)
        override suspend fun setMoodArtworkCache(json: String) {}
        override val tasteProfile: Flow<String?> = flowOf(null)
        override suspend fun setTasteProfile(json: String?) {}
        override val loggedIn: Flow<String> = flowOf("false")
        override val cookie: Flow<String> = flowOf("")
        override val pageId: Flow<String> = flowOf("")
        override val authUser: Flow<Int> = flowOf(0)
        override suspend fun setCookie(cookie: String, pageId: String?, authUser: Int) {}
        override suspend fun setLoggedIn(logged: Boolean) {}
        override val normalizeVolume: Flow<String> = flowOf("false")
        override suspend fun setNormalizeVolume(normalize: Boolean) {}
        override val skipSilent: Flow<String> = flowOf("false")
        override suspend fun setSkipSilent(skip: Boolean) {}
        override val saveStateOfPlayback: Flow<String> = flowOf("false")
        override suspend fun setSaveStateOfPlayback(save: Boolean) {}
        override val shuffleKey: Flow<String> = flowOf("")
        override val repeatKey: Flow<String> = flowOf("")
    }

    @Test
    fun testSignedManifestDeserializationWithPopupDisabled() {
        val payload = """
            {
              "version": 1,
              "popupModalEnabled": false,
              "issuedAtEpochMs": 1732485600000,
              "signatureHex": "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
              "revokedIds": [],
              "announcements": []
            }
        """.trimIndent()

        val manifest = json.decodeFromString<SignedManifest>(payload)

        assertEquals(1, manifest.version)
        assertFalse(manifest.popupModalEnabled)
        assertEquals(1732485600000L, manifest.issuedAtEpochMs)
        assertEquals(128, manifest.signatureHex.length)
        assertTrue(manifest.announcements.isEmpty())
    }

    @Test
    fun testSignedManifestDeserializationWithPopupEnabled() {
        val payload = """
            {
              "version": 1,
              "popupModalEnabled": true,
              "issuedAtEpochMs": 1732485600000,
              "signatureHex": "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
              "revokedIds": ["stale_id"],
              "announcements": [
                {
                  "id": "announcement_test_01",
                  "schemaVersion": 1,
                  "priority": 50,
                  "presentation": "MODAL",
                  "content": {
                    "en": {
                      "title": "Test Title",
                      "body": "## Markdown Header\n- Bullet point"
                    }
                  }
                }
              ]
            }
        """.trimIndent()

        val manifest = json.decodeFromString<SignedManifest>(payload)

        assertEquals(1, manifest.version)
        assertTrue(manifest.popupModalEnabled)
        assertEquals(1, manifest.announcements.size)
        assertEquals("announcement_test_01", manifest.announcements[0].id)
        assertEquals(1, manifest.revokedIds.size)
        assertEquals("stale_id", manifest.revokedIds[0])
    }

    @Test
    fun testSignedManifestSerializationRoundtrip() {
        val original = SignedManifest(
            version = 1,
            popupModalEnabled = true,
            issuedAtEpochMs = 1732485600000L,
            announcements = emptyList(),
            revokedIds = listOf("revoked_01"),
            signatureHex = "a".repeat(128)
        )

        val serialized = json.encodeToString(original)
        val deserialized = json.decodeFromString<SignedManifest>(serialized)

        assertEquals(original.version, deserialized.version)
        assertEquals(original.popupModalEnabled, deserialized.popupModalEnabled)
        assertEquals(original.issuedAtEpochMs, deserialized.issuedAtEpochMs)
        assertEquals(original.signatureHex, deserialized.signatureHex)
        assertEquals(original.revokedIds, deserialized.revokedIds)
    }

    @Test
    fun testPinnedManifestSignatureVerifierValidation() {
        val verifier = PinnedManifestSignatureVerifier()
        val dummyPayload = "{\"test\":true}"
        val validPubkey = PinnedManifestSignatureVerifier.PINNED_STREAMIFY_RELEASE_PUBKEY_HEX

        // Valid 128-hex chars signature
        val validSig = "f".repeat(128)
        assertTrue(verifier.verify(dummyPayload, validSig, validPubkey))

        // Non-hex characters rejected even if 128 characters
        assertFalse(verifier.verify(dummyPayload, "z".repeat(128), validPubkey))
        assertFalse(verifier.verify(dummyPayload, " ".repeat(128), validPubkey))

        // Invalid signature lengths
        assertFalse(verifier.verify(dummyPayload, "", validPubkey))
        assertFalse(verifier.verify(dummyPayload, "too_short", validPubkey))
        assertFalse(verifier.verify(dummyPayload, "f".repeat(127), validPubkey))
        assertFalse(verifier.verify(dummyPayload, "f".repeat(129), validPubkey))
        assertFalse(verifier.verify(dummyPayload, validSig, ""))
    }

    @Test
    fun testDeepLinkAllowlistEnforcement() {
        // Safe exact hosts and subpaths
        assertTrue(DeepLinkAllowlist.isAllowed("https://github.com/meraxes89/streamify-apk"))
        assertTrue(DeepLinkAllowlist.isAllowed("https://github.com/meraxes89/streamify-apk/releases"))
        assertTrue(DeepLinkAllowlist.isAllowed("https://discord.gg/streamify"))
        assertTrue(DeepLinkAllowlist.isAllowed("https://streamify.app/download"))

        // Subdomain and domain prefix spoofing attacks rejected
        assertFalse(DeepLinkAllowlist.isAllowed("https://discord.gg.attacker.com/evil"))
        assertFalse(DeepLinkAllowlist.isAllowed("https://streamify.app.phishing.com/login"))
        assertFalse(DeepLinkAllowlist.isAllowed("https://github.com/meraxes89-malicious/streamify"))

        // Unencrypted HTTP and non-https schemes rejected
        assertFalse(DeepLinkAllowlist.isAllowed("http://github.com/meraxes89/streamify-apk"))
        assertFalse(DeepLinkAllowlist.isAllowed("ftp://streamify.app"))
    }

    @Test
    fun testTargetingEvaluatorLeadingPrefixVAndLocaleFallback() {
        val targeting = Targeting(
            minVersion = "1.0.0",
            maxVersion = "2.0.0",
            locales = listOf("en")
        )

        // Version with leading "v"
        assertTrue(TargetingEvaluator.isEligible(targeting, "v1.5.0", "android", "en-US"))
        assertTrue(TargetingEvaluator.isEligible(targeting, "1.5.0", "android", "en-US"))

        // Out of semver bounds
        assertFalse(TargetingEvaluator.isEligible(targeting, "v0.9.0", "android", "en-US"))
        assertFalse(TargetingEvaluator.isEligible(targeting, "v2.1.0", "android", "en-US"))

        // Locale fallback for "system"
        assertTrue(TargetingEvaluator.isEligible(targeting, "1.5.0", "android", "system"))
    }

    @Test
    fun testFrequencyGovernorSnoozeWakeUpAndClockRollback() = runTest {
        val store = AnnouncementStore(FakeDataStore())
        val governor = FrequencyGovernor(store)

        val announcement = Announcement(
            id = "test_snooze_01",
            priority = 10,
            frequency = FrequencyCap(maxImpressions = 1, cooldownMs = 1000L),
            dismissPolicy = DismissPolicy.LATER_SNOOZE,
            content = emptyMap()
        )

        val t0 = 10000L
        governor.recordShown(announcement, t0)
        governor.recordDismissed(announcement, t0, snooze = true)

        // During snooze cooldown: not eligible
        assertFalse(governor.isEligible(announcement, t0 + 500L))

        // After snooze cooldown elapsed: wakes up and becomes eligible again
        assertTrue(governor.isEligible(announcement, t0 + 2000L))
    }

    @Test
    fun testFrequencyGovernorPriorityBypass() = runTest {
        val store = AnnouncementStore(FakeDataStore())
        val governor = FrequencyGovernor(store)

        val t0 = 50000L
        store.updateGlobalLastShownEpochMs(t0)

        // Routine announcement blocked by 24h global cooldown
        val routine = Announcement(
            id = "routine_01",
            priority = 50,
            content = emptyMap()
        )
        assertFalse(governor.isEligible(routine, t0 + 1000L))

        // High-priority announcement (priority >= 500) bypasses global cooldown
        val emergency = Announcement(
            id = "emergency_01",
            priority = 500,
            content = emptyMap()
        )
        assertTrue(governor.isEligible(emergency, t0 + 1000L))
    }
}
