package com.maxrave.simpmusic.data.update

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class UpdateManifestTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun manifestDeserializesCorrectlyWithAllFields() {
        val payload = """
            {
              "schemaVersion": 1,
              "version": "v1.0.3-beta",
              "versionCode": 66,
              "channel": "beta",
              "publishedAt": "2026-11-23T03:19:13Z",
              "changelog": "## Key Highlights\n- Elite audio\n- Quick tunnels",
              "artifacts": {
                "android": {
                  "url": "https://github.com/meraxes89/streamify-apk/releases/download/v1.0.3-beta/Streamify-v1.0.3-beta.apk",
                  "sha256": "abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890"
                },
                "windows": {
                  "url": "https://github.com/meraxes89/streamify-apk/releases/download/v1.0.3-beta/SimpMusic-1.0.3.msi",
                  "sha256": "1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef"
                }
              },
              "unknownFutureField": "futureProof"
            }
        """.trimIndent()

        val manifest = json.decodeFromString<UpdateManifest>(payload)

        assertEquals(1, manifest.schemaVersion)
        assertEquals("v1.0.3-beta", manifest.version)
        assertEquals(66, manifest.versionCode)
        assertEquals("beta", manifest.channel)
        assertEquals("2026-11-23T03:19:13Z", manifest.publishedAt)
        assertTrue(manifest.changelog.contains("Key Highlights"))
        assertEquals(2, manifest.artifacts.size)

        val androidArtifact = manifest.artifacts["android"]
        assertNotNull(androidArtifact)
        assertTrue(androidArtifact.url.endsWith(".apk"))
        assertEquals("abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890", androidArtifact.sha256)
    }

    @Test
    fun manifestDeserializesSafelyWhenOptionalFieldsAreMissing() {
        val minimalPayload = """
            {
              "version": "v1.0.3-beta",
              "versionCode": 66
            }
        """.trimIndent()

        val manifest = json.decodeFromString<UpdateManifest>(minimalPayload)

        assertEquals(1, manifest.schemaVersion)
        assertEquals("v1.0.3-beta", manifest.version)
        assertEquals(66, manifest.versionCode)
        assertEquals("beta", manifest.channel)
        assertEquals("", manifest.changelog)
        assertTrue(manifest.artifacts.isEmpty())
    }
}
