package com.streamify.sync

import com.maxrave.domain.manager.DataStoreManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

enum class SettingType {
    STRING,
    BOOLEAN,
    INT,
}

/**
 * Typed specification for a single DataStore setting synced via [StreamifySyncEngine].
 * Centralizes allowlist, normalized serialization, and typed DataStore writes in one place.
 */
data class SyncedSettingSpec(
    val key: String,
    val type: SettingType,
    val read: (DataStoreManager) -> Flow<String>,
    val write: suspend (DataStoreManager, String) -> Unit,
)

private fun parseBoolSetting(raw: String): Boolean =
    raw.equals(DataStoreManager.TRUE, ignoreCase = true) || raw.equals("true", ignoreCase = true)

private fun stringSetting(
    key: String,
    read: (DataStoreManager) -> Flow<String>,
    write: suspend (DataStoreManager, String) -> Unit,
) = SyncedSettingSpec(
    key = key,
    type = SettingType.STRING,
    read = read,
    write = write,
)

private fun boolSetting(
    key: String,
    read: (DataStoreManager) -> Flow<String>,
    write: suspend (DataStoreManager, Boolean) -> Unit,
) = SyncedSettingSpec(
    key = key,
    type = SettingType.BOOLEAN,
    read = { dm -> read(dm).map { if (parseBoolSetting(it)) DataStoreManager.TRUE else DataStoreManager.FALSE } },
    write = { dm, raw -> write(dm, parseBoolSetting(raw)) },
)

private fun intSetting(
    key: String,
    read: (DataStoreManager) -> Flow<Int>,
    write: suspend (DataStoreManager, Int) -> Unit,
) = SyncedSettingSpec(
    key = key,
    type = SettingType.INT,
    read = { dm -> read(dm).map { it.toString() } },
    write = { dm, raw -> raw.toIntOrNull()?.let { write(dm, it) } },
)

/**
 * Canonical registry of all user-facing settings synced across devices.
 *
 * Intentionally excludes secrets (`cookie`, `spdc`, `discord_token`, `ai_api_key`),
 * device-local hardware/DSP (`equalizer_*`, `delay_*`, `reverb_*`, `eight_d_*`),
 * and per-device operational state (`proxy_*`, `max_song_cache_size`, `local_tracking_enabled`).
 */
internal object SettingsSyncRegistry {
    val specs: List<SyncedSettingSpec> = listOf(
        // Appearance & Themes
        stringSetting("theme_mode", { it.themeMode }, { dm, v -> dm.setThemeMode(v) }),
        stringSetting("theme_color_source", { it.themeColorSource }, { dm, v -> dm.setThemeColorSource(v) }),
        stringSetting("custom_theme_color", { it.customThemeColor }, { dm, v -> dm.setCustomThemeColor(v) }),
        stringSetting("now_playing_style", { it.nowPlayingStyle }, { dm, v -> dm.setNowPlayingStyle(v) }),
        boolSetting("enable_liquid_glass", { it.enableLiquidGlass }, { dm, v -> dm.setEnableLiquidGlass(v) }),
        stringSetting("chart_key", { it.chartKey }, { dm, v -> dm.setChartKey(v) }),

        // Audio & Playback
        stringSetting("quality", { it.quality }, { dm, v -> dm.setQuality(v) }),
        stringSetting("download_quality", { it.downloadQuality }, { dm, v -> dm.setDownloadQuality(v) }),
        boolSetting("normalize_volume", { it.normalizeVolume }, { dm, v -> dm.setNormalizeVolume(v) }),
        boolSetting("skip_silent", { it.skipSilent }, { dm, v -> dm.setSkipSilent(v) }),
        boolSetting("crossfade_enabled", { it.crossfadeEnabled }, { dm, v -> dm.setCrossfadeEnabled(v) }),
        intSetting("crossfade_duration", { it.crossfadeDuration }, { dm, v -> dm.setCrossfadeDuration(v) }),
        boolSetting("crossfade_dj_mode", { it.crossfadeDjMode }, { dm, v -> dm.setCrossfadeDjMode(v) }),
        boolSetting("crossfade_skip_album", { it.crossfadeSkipAlbum }, { dm, v -> dm.setCrossfadeSkipAlbum(v) }),
        boolSetting("endless_queue", { it.endlessQueue }, { dm, v -> dm.setEndlessQueue(v) }),
        boolSetting("radio_audio_only", { it.radioAudioOnly }, { dm, v -> dm.setRadioAudioOnly(v) }),

        // Content & Visuals
        boolSetting("explicit_content_enabled", { it.explicitContentEnabled }, { dm, v -> dm.setExplicitContentEnabled(v) }),
        stringSetting("location", { it.location }, { dm, v -> dm.setLocation(v) }),
        boolSetting("sponsor_block_enabled", { it.sponsorBlockEnabled }, { dm, v -> dm.setSponsorBlockEnabled(v) }),
        boolSetting("spotify_canvas", { it.spotifyCanvas }, { dm, v -> dm.setSpotifyCanvas(v) }),
        boolSetting("am_animated_artwork", { it.amAnimatedArtwork }, { dm, v -> dm.setAMAnimatedArtwork(v) }),
        stringSetting("preferred_audio_language", { it.preferredAudioLanguage }, { dm, v -> dm.setPreferredAudioLanguage(v) }),
        stringSetting("youtube_subtitle_language", { it.youtubeSubtitleLanguage }, { dm, v -> dm.setYoutubeSubtitleLanguage(v) }),

        // Lyrics & Translation
        stringSetting("lyrics_provider", { it.lyricsProvider }, { dm, v -> dm.setLyricsProvider(v) }),
        intSetting("lyrics_offset_ms", { it.lyricsOffsetMs }, { dm, v -> dm.setLyricsOffsetMs(v) }),
        stringSetting("translation_language", { it.translationLanguage }, { dm, v -> dm.setTranslationLanguage(v) }),
        boolSetting("use_translation_language", { it.enableTranslateLyric }, { dm, v -> dm.setEnableTranslateLyric(v) }),
        boolSetting("use_ai_translation", { it.useAITranslation }, { dm, v -> dm.setUseAITranslation(v) }),
        stringSetting("lyrics_style", { it.lyricsStyle }, { dm, v -> dm.setLyricsStyle(v) }),

        // Updates
        stringSetting("update_channel", { it.updateChannel }, { dm, v -> dm.setUpdateChannel(v) }),
    )

    val byKey: Map<String, SyncedSettingSpec> = specs.associateBy { it.key }
    val keys: Set<String> = byKey.keys
}
