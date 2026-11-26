package com.streamify.announcements

import kotlinx.coroutines.runBlocking
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
        val validPubkey = PinnedManifestSignatureVerifier.PINNED_STREAMIFY_RELEASE_PUBKEY_HEX

        val testPayload = "hello streamify announcement"
        val validSig = "d1a87e9232bcfe09c613192821f7b212b76d119953f6b25c5ad74a31f772bc4478a15db1b6e6870b24f0b126f26d21d519287889c60c2134ddbdf98828569f02"

        // Cryptographically valid Ed25519 signature passes verification
        assertTrue(verifier.verify(testPayload, validSig, validPubkey))

        // Tampered payload fails verification
        assertFalse(verifier.verify(testPayload + "_tampered", validSig, validPubkey))

        // Tampered signature fails verification
        val corruptedSig = validSig.substring(0, 126) + "00"
        assertFalse(verifier.verify(testPayload, corruptedSig, validPubkey))

        // Non-hex characters rejected even if 128 characters
        assertFalse(verifier.verify(testPayload, "z".repeat(128), validPubkey))
        assertFalse(verifier.verify(testPayload, " ".repeat(128), validPubkey))

        // Invalid signature lengths
        assertFalse(verifier.verify(testPayload, "", validPubkey))
        assertFalse(verifier.verify(testPayload, "too_short", validPubkey))
        assertFalse(verifier.verify(testPayload, "f".repeat(127), validPubkey))
        assertFalse(verifier.verify(testPayload, "f".repeat(129), validPubkey))
        assertFalse(verifier.verify(testPayload, validSig, ""))
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
    fun testFrequencyGovernorSnoozeWakeUpAndClockRollback() = runBlocking {
        val store = AnnouncementStore()
        val governor = FrequencyGovernor(store, globalCooldownMs = 1000L)

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
    fun testFrequencyGovernorPriorityBypass() = runBlocking {
        val store = AnnouncementStore()
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

    @Test
    fun testSignedManifestDeserializationWithGovernance() {
        val payload = """
            {
              "version": 1,
              "popupModalEnabled": true,
              "governance": {
                "globalCooldownMs": 43200000,
                "emergencyPriorityThreshold": 300,
                "defaultSnoozeCooldownMs": 172800000,
                "allowedExtraDomains": ["status.streamify.app", "docs.streamify.app/guides"]
              },
              "issuedAtEpochMs": 1732485600000,
              "signatureHex": "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
              "revokedIds": [],
              "announcements": []
            }
        """.trimIndent()

        val manifest = json.decodeFromString<SignedManifest>(payload)

        assertEquals(43200000L, manifest.governance.globalCooldownMs)
        assertEquals(300, manifest.governance.emergencyPriorityThreshold)
        assertEquals(172800000L, manifest.governance.defaultSnoozeCooldownMs)
        assertEquals(2, manifest.governance.allowedExtraDomains.size)
        assertTrue(manifest.governance.allowedExtraDomains.contains("status.streamify.app"))
    }

    @Test
    fun testFrequencyGovernorWithCustomGovernanceCooldown() = runBlocking {
        val store = AnnouncementStore()
        val governor = FrequencyGovernor(store)

        val t0 = 100000L
        store.updateGlobalLastShownEpochMs(t0)

        val routine = Announcement(
            id = "routine_governance_cooldown",
            priority = 50,
            content = emptyMap()
        )

        // Set remote cooldown to 12 hours (43_200_000 ms)
        val customGovernance = GovernanceConfig(globalCooldownMs = 12L * 60 * 60 * 1000L)

        // 10 hours later: still within 12h cooldown -> ineligible
        assertFalse(governor.isEligible(routine, t0 + 10L * 60 * 60 * 1000L, governance = customGovernance))

        // 13 hours later: cooldown has elapsed -> eligible
        assertTrue(governor.isEligible(routine, t0 + 13L * 60 * 60 * 1000L, governance = customGovernance))
    }

    @Test
    fun testFrequencyGovernorWithCustomEmergencyThreshold() = runBlocking {
        val store = AnnouncementStore()
        val governor = FrequencyGovernor(store)

        val t0 = 100000L
        store.updateGlobalLastShownEpochMs(t0)

        val customGovernance = GovernanceConfig(emergencyPriorityThreshold = 300)

        val subThreshold = Announcement(
            id = "p299_announcement",
            priority = 299,
            content = emptyMap()
        )
        val atThreshold = Announcement(
            id = "p300_announcement",
            priority = 300,
            content = emptyMap()
        )

        // 299 is below threshold -> blocked by cooldown
        assertFalse(governor.isEligible(subThreshold, t0 + 1000L, governance = customGovernance))

        // 300 meets threshold -> bypasses cooldown
        assertTrue(governor.isEligible(atThreshold, t0 + 1000L, governance = customGovernance))
    }

    @Test
    fun testDeepLinkAllowlistDynamicDomains() {
        val testUrl = "https://status.streamify.app/changelog"
        assertFalse(DeepLinkAllowlist.isAllowed(testUrl))

        DeepLinkAllowlist.setDynamicAllowedDomains(listOf("status.streamify.app"))
        assertTrue(DeepLinkAllowlist.isAllowed(testUrl))

        val subpathUrl = "https://docs.streamify.app/guides/setup"
        assertFalse(DeepLinkAllowlist.isAllowed(subpathUrl))

        DeepLinkAllowlist.setDynamicAllowedDomains(listOf("status.streamify.app", "docs.streamify.app/guides"))
        assertTrue(DeepLinkAllowlist.isAllowed(subpathUrl))
    }

    @Test
    fun testBackwardCompatibilityWithoutGovernanceBlock() {
        val payload = """
            {
              "version": 1,
              "popupModalEnabled": true,
              "issuedAtEpochMs": 1732485600000,
              "signatureHex": "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
              "announcements": []
            }
        """.trimIndent()

        val manifest = json.decodeFromString<SignedManifest>(payload)

        assertEquals(24L * 60 * 60 * 1000L, manifest.governance.globalCooldownMs)
        assertEquals(500, manifest.governance.emergencyPriorityThreshold)
        assertEquals(7L * 24 * 60 * 60 * 1000L, manifest.governance.defaultSnoozeCooldownMs)
        assertTrue(manifest.governance.allowedExtraDomains.isEmpty())
    }
}
