package com.maxrave.simpmusic.data.update

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PlatformBinaryResolverTest {

    @Test
    fun resolvesPlatformBinaryForCurrentHost() {
        val artifacts = mapOf(
            "linux-deb" to UpdateArtifact("https://example.com/streamify.deb", "sha_deb"),
            "windows-x64" to UpdateArtifact("https://example.com/Streamify.msi", "sha_msi"),
            "macos-universal" to UpdateArtifact("https://example.com/Streamify.dmg", "sha_dmg"),
        )

        val resolved = resolveDeviceBinary("v1.0.3-beta", artifacts)

        assertNotNull(resolved.downloadUrl)
        assertTrue(resolved.downloadUrl.isNotBlank())
        assertFalse(resolved.buttonLabel.isBlank())
        assertFalse(resolved.architectureName.isBlank())
    }

    @Test
    fun fallsBackToPredictiveUrlWhenArtifactsEmpty() {
        val resolved = resolveDeviceBinary("v1.0.3-beta", emptyMap())

        assertNotNull(resolved.downloadUrl)
        assertTrue(resolved.downloadUrl.contains("v1.0.3-beta"))
    }
}
