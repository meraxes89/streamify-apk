package com.streamify.announcements

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
        val dummyPayload = "{\"test\":true}"
        val validPubkey = PinnedManifestSignatureVerifier.PINNED_STREAMIFY_RELEASE_PUBKEY_HEX

        // Valid 128-hex chars signature
        val validSig = "f".repeat(128)
        assertTrue(verifier.verify(dummyPayload, validSig, validPubkey))

        // Invalid signature lengths
        assertFalse(verifier.verify(dummyPayload, "", validPubkey))
        assertFalse(verifier.verify(dummyPayload, "too_short", validPubkey))
        assertFalse(verifier.verify(dummyPayload, "f".repeat(127), validPubkey))
        assertFalse(verifier.verify(dummyPayload, "f".repeat(129), validPubkey))
        assertFalse(verifier.verify(dummyPayload, validSig, ""))
    }

    @Test
    fun testDeepLinkAllowlistEnforcement() {
        assertTrue(DeepLinkAllowlist.isAllowed("https://github.com/meraxes89/streamify-apk"))
        assertTrue(DeepLinkAllowlist.isAllowed("https://github.com/meraxes89/streamify-apk/releases"))
        assertTrue(DeepLinkAllowlist.isAllowed("https://discord.gg/streamify"))
        assertTrue(DeepLinkAllowlist.isAllowed("https://streamify.app/download"))

        assertFalse(DeepLinkAllowlist.isAllowed("http://github.com/meraxes89/streamify-apk"))
        assertFalse(DeepLinkAllowlist.isAllowed("https://malicious-phishing.com/streamify"))
        assertFalse(DeepLinkAllowlist.isAllowed("ftp://streamify.app"))
    }
}
