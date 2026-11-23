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

    @Test
    fun manifestDeserializesMultiplatformAbiKeys() {
        val payload = """
            {
              "version": "v1.0.3-beta",
              "versionCode": 66,
              "artifacts": {
                "android-arm64-v8a": {
                  "url": "https://github.com/meraxes89/streamify-apk/releases/download/v1.0.3-beta/Streamify-v1.0.3-beta-arm64-v8a-release.apk",
                  "sha256": "1111"
                },
                "android-armeabi-v7a": {
                  "url": "https://github.com/meraxes89/streamify-apk/releases/download/v1.0.3-beta/Streamify-v1.0.3-beta-armeabi-v7a-release.apk",
                  "sha256": "2222"
                },
                "windows-x64": {
                  "url": "https://github.com/meraxes89/streamify-apk/releases/download/v1.0.3-beta/Streamify-1.0.3.msi",
                  "sha256": "3333"
                },
                "linux-deb": {
                  "url": "https://github.com/meraxes89/streamify-apk/releases/download/v1.0.3-beta/streamify_1.0.3_amd64.deb",
                  "sha256": "4444"
                }
              }
            }
        """.trimIndent()

        val manifest = json.decodeFromString<UpdateManifest>(payload)

        assertEquals("1111", manifest.artifacts["android-arm64-v8a"]?.sha256)
        assertEquals("2222", manifest.artifacts["android-armeabi-v7a"]?.sha256)
        assertEquals("3333", manifest.artifacts["windows-x64"]?.sha256)
        assertEquals("4444", manifest.artifacts["linux-deb"]?.sha256)
    }

    @Test
    fun manifestDeserializesMinSupportedVersionAndBreakingFlag() {
        val payload = """
            {
              "version": "v1.0.4-beta",
              "versionCode": 104,
              "minSupportedVersionCode": 102,
              "isBreaking": true
            }
        """.trimIndent()

        val manifest = json.decodeFromString<UpdateManifest>(payload)

        assertEquals("v1.0.4-beta", manifest.version)
        assertEquals(104, manifest.versionCode)
        assertEquals(102, manifest.minSupportedVersionCode)
        assertTrue(manifest.isBreaking)
    }

    @Test
    fun manifestDeserializesVivoArtifacts() {
        val payload = """
            {
              "version": "v1.0.3-beta",
              "versionCode": 66,
              "artifacts": {
                "android-vivo-arm64": {
                  "url": "https://github.com/meraxes89/streamify-apk/releases/download/v1.0.3-beta/Streamify-vivo-v1.0.3-beta-vivoAndroidApp-release.apk",
                  "sha256": "vivo_arm64_sha"
                },
                "android-vivo": {
                  "url": "https://github.com/meraxes89/streamify-apk/releases/download/v1.0.3-beta/Streamify-vivo-v1.0.3-beta-vivoAndroidApp-release.apk",
                  "sha256": "vivo_arm64_sha"
                }
              }
            }
        """.trimIndent()

        val manifest = json.decodeFromString<UpdateManifest>(payload)

        assertEquals("vivo_arm64_sha", manifest.artifacts["android-vivo-arm64"]?.sha256)
        assertEquals("vivo_arm64_sha", manifest.artifacts["android-vivo"]?.sha256)
        assertTrue(manifest.artifacts["android-vivo"]?.url?.contains("Streamify-vivo") == true)
    }
}
