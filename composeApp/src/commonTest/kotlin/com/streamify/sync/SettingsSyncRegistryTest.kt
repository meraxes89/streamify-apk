package com.streamify.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SettingsSyncRegistryTest {

    @Test
    fun registryKeysAreUniqueAndExcludeSensitiveOrLocalKeys() {
        val rawKeys = SettingsSyncRegistry.specs.map { it.key }
        assertEquals(rawKeys.size, SettingsSyncRegistry.keys.size, "Duplicate key in SettingsSyncRegistry")

        val forbidden = listOf(
            "cookie",
            "spdc",
            "discord_token",
            "ai_api_key",
            "equalizer_enabled",
            "equalizer_bands",
            "proxy_host",
            "local_tracking_enabled",
        )
        for (key in forbidden) {
            assertFalse(key in SettingsSyncRegistry.keys, "Sensitive/local key '$key' must not be synced")
        }
    }

    @Test
    fun registryContainsCoreTypedSettings() {
        assertEquals(SettingType.STRING, SettingsSyncRegistry.byKey["theme_mode"]?.type)
        assertEquals(SettingType.BOOLEAN, SettingsSyncRegistry.byKey["normalize_volume"]?.type)
        assertEquals(SettingType.INT, SettingsSyncRegistry.byKey["crossfade_duration"]?.type)
        assertEquals(SettingType.INT, SettingsSyncRegistry.byKey["lyrics_offset_ms"]?.type)
        assertTrue("quality" in SettingsSyncRegistry.keys)
        assertTrue("download_quality" in SettingsSyncRegistry.keys)
    }
}
