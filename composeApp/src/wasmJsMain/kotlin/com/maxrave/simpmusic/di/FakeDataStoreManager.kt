package com.maxrave.simpmusic.di

import com.maxrave.common.QUALITY
import com.maxrave.common.VIDEO_QUALITY
import com.maxrave.domain.data.model.discord.DiscordSession
import com.maxrave.domain.data.model.network.ProxyConfiguration
import com.maxrave.domain.data.player.EightDDirection
import com.maxrave.domain.data.player.ReverbPreset
import com.maxrave.domain.manager.DataStoreManager
import kotlinx.browser.localStorage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf

class FakeDataStoreManager : DataStoreManager {
    private val stringFlows = mutableMapOf<String, MutableStateFlow<String>>()
    private val intFlows = mutableMapOf<String, MutableStateFlow<Int>>()
    private val floatFlows = mutableMapOf<String, MutableStateFlow<Float>>()
    private val longFlows = mutableMapOf<String, MutableStateFlow<Long>>()
    private val dynamicStrings = mutableMapOf<String, MutableStateFlow<String?>>()

    private fun getStorage(key: String): String? = try {
        localStorage.getItem(key)
    } catch (_: Throwable) {
        null
    }

    private fun setStorage(key: String, value: String) {
        try {
            localStorage.setItem(key, value)
        } catch (_: Throwable) {}
    }

    private fun getOrInitString(key: String, default: String): MutableStateFlow<String> {
        return stringFlows.getOrPut(key) {
            val stored = getStorage(key)
            val initial = if (key == "quality" || key == "downloadQuality") {
                if (stored.isNullOrEmpty() || stored == "HIGH" || stored == "high" || stored.contains("Low")) {
                    default
                } else {
                    QUALITY.normalize(stored)
                }
            } else {
                stored ?: default
            }
            MutableStateFlow(initial)
        }
    }

    private fun setStoredString(key: String, value: String) {
        getOrInitString(key, value).value = value
        setStorage(key, value)
    }

    private fun getOrInitBool(key: String, default: Boolean): MutableStateFlow<String> {
        val defStr = if (default) DataStoreManager.TRUE else DataStoreManager.FALSE
        return getOrInitString(key, defStr)
    }

    private fun setStoredBool(key: String, value: Boolean) {
        setStoredString(key, if (value) DataStoreManager.TRUE else DataStoreManager.FALSE)
    }

    private fun getOrInitInt(key: String, default: Int): MutableStateFlow<Int> {
        return intFlows.getOrPut(key) {
            val stored = getStorage(key)?.toIntOrNull()
            MutableStateFlow(stored ?: default)
        }
    }

    private fun setStoredInt(key: String, value: Int) {
        getOrInitInt(key, value).value = value
        setStorage(key, value.toString())
    }

    private fun getOrInitFloat(key: String, default: Float): MutableStateFlow<Float> {
        return floatFlows.getOrPut(key) {
            val stored = getStorage(key)?.toFloatOrNull()
            MutableStateFlow(stored ?: default)
        }
    }

    private fun setStoredFloat(key: String, value: Float) {
        getOrInitFloat(key, value).value = value
        setStorage(key, value.toString())
    }

    private fun getOrInitLong(key: String, default: Long): MutableStateFlow<Long> {
        return longFlows.getOrPut(key) {
            val stored = getStorage(key)?.toLongOrNull()
            MutableStateFlow(stored ?: default)
        }
    }

    private fun setStoredLong(key: String, value: Long) {
        getOrInitLong(key, value).value = value
        setStorage(key, value.toString())
    }

    override val appVersion: Flow<String> get() = getOrInitString("appVersion", "2.6.0-web")
    override suspend fun setAppVersion(version: String) { setStoredString("appVersion", version) }

    override val openAppTime: Flow<Int> get() = getOrInitInt("openAppTime", 1)
    override suspend fun openApp() {
        val current = getOrInitInt("openAppTime", 1).value
        setStoredInt("openAppTime", current + 1)
    }
    override suspend fun resetOpenAppTime() { setStoredInt("openAppTime", 0) }
    override suspend fun doneOpenAppTime() { setStoredInt("openAppTime", 1) }

    override val location: Flow<String> get() = getOrInitString("location", "IN")
    override suspend fun setLocation(location: String) { setStoredString("location", location) }

    override val quality: Flow<String> get() = getOrInitString("quality", QUALITY.items[2].toString())
    override suspend fun setQuality(quality: String) { setStoredString("quality", quality) }

    override val downloadQuality: Flow<String> get() = getOrInitString("downloadQuality", QUALITY.items[2].toString())
    override suspend fun setDownloadQuality(quality: String) { setStoredString("downloadQuality", quality) }

    override val videoDownloadQuality: Flow<String> get() = getOrInitString("videoDownloadQuality", VIDEO_QUALITY.items[0].toString())
    override suspend fun setVideoDownloadQuality(quality: String) { setStoredString("videoDownloadQuality", quality) }

    override val language: Flow<String> get() = getOrInitString("language", "en")

    override val moodAndGenresCache: Flow<String?> get() = getString("moodAndGenresCache")
    override suspend fun setMoodAndGenresCache(json: String) { putString("moodAndGenresCache", json) }

    override val moodArtworkCache: Flow<String?> get() = getString("moodArtworkCache")
    override suspend fun setMoodArtworkCache(json: String) { putString("moodArtworkCache", json) }

    override val tasteProfile: Flow<String?> get() = getString("tasteProfile")
    override suspend fun setTasteProfile(json: String?) { if (json != null) putString("tasteProfile", json) }

    override fun getString(key: String): Flow<String?> {
        return dynamicStrings.getOrPut(key) {
            MutableStateFlow(getStorage("ds_$key"))
        }
    }

    override suspend fun putString(key: String, value: String) {
        val flow = dynamicStrings.getOrPut(key) { MutableStateFlow(null) }
        flow.value = value
        setStorage("ds_$key", value)
    }

    override val loggedIn: Flow<String> get() = getOrInitBool("loggedIn", false)
    override val cookie: Flow<String> get() = getOrInitString("cookie", "")
    override val pageId: Flow<String> get() = getOrInitString("pageId", "")
    override val authUser: Flow<Int> get() = getOrInitInt("authUser", 1)
    override suspend fun setCookie(cookie: String, pageId: String?, authUser: Int) {
        setStoredString("cookie", cookie)
        if (pageId != null) setStoredString("pageId", pageId)
        setStoredInt("authUser", authUser)
        setStoredBool("loggedIn", cookie.isNotEmpty())
    }
    override suspend fun setLoggedIn(logged: Boolean) { setStoredBool("loggedIn", logged) }

    override val normalizeVolume: Flow<String> get() = getOrInitBool("normalizeVolume", false)
    override suspend fun setNormalizeVolume(normalize: Boolean) { setStoredBool("normalizeVolume", normalize) }

    override val skipSilent: Flow<String> get() = getOrInitBool("skipSilent", false)
    override suspend fun setSkipSilent(skip: Boolean) { setStoredBool("skipSilent", skip) }

    override val saveStateOfPlayback: Flow<String> get() = getOrInitBool("saveStateOfPlayback", true)
    override suspend fun setSaveStateOfPlayback(save: Boolean) { setStoredBool("saveStateOfPlayback", save) }

    override val shuffleKey: Flow<String> get() = getOrInitBool("shuffleKey", false)
    override val repeatKey: Flow<String> get() = getOrInitString("repeatKey", DataStoreManager.REPEAT_MODE_OFF)
    override suspend fun recoverShuffleAndRepeatKey(shuffle: Boolean, repeat: Int) {
        setStoredBool("shuffleKey", shuffle)
        val repStr = when (repeat) {
            1 -> DataStoreManager.REPEAT_ONE
            2 -> DataStoreManager.REPEAT_ALL
            else -> DataStoreManager.REPEAT_MODE_OFF
        }
        setStoredString("repeatKey", repStr)
    }

    override val saveRecentSongAndQueue: Flow<String> get() = getOrInitBool("saveRecentSongAndQueue", true)
    override suspend fun setSaveRecentSongAndQueue(save: Boolean) { setStoredBool("saveRecentSongAndQueue", save) }

    override val recentMediaId: Flow<String> get() = getOrInitString("recentMediaId", "")
    override val recentPosition: Flow<String> get() = getOrInitString("recentPosition", "0")
    override suspend fun saveRecentSong(mediaId: String, position: Long) {
        setStoredString("recentMediaId", mediaId)
        setStoredString("recentPosition", position.toString())
    }

    override val playlistFromSaved: Flow<String> get() = getOrInitString("playlistFromSaved", "")
    override suspend fun setPlaylistFromSaved(playlist: String) { setStoredString("playlistFromSaved", playlist) }

    override val sendBackToGoogle: Flow<String> get() = getOrInitBool("sendBackToGoogle", false)
    override suspend fun setSendBackToGoogle(send: Boolean) { setStoredBool("sendBackToGoogle", send) }

    override val sponsorBlockEnabled: Flow<String> get() = getOrInitBool("sponsorBlockEnabled", false)
    override suspend fun setSponsorBlockEnabled(enabled: Boolean) { setStoredBool("sponsorBlockEnabled", enabled) }

    override suspend fun getSponsorBlockCategories(): ArrayList<String> = ArrayList()
    override suspend fun setSponsorBlockCategories(categories: ArrayList<String>) {}

    override val enableTranslateLyric: Flow<String> get() = getOrInitBool("enableTranslateLyric", false)
    override suspend fun setEnableTranslateLyric(enable: Boolean) { setStoredBool("enableTranslateLyric", enable) }

    override val lyricsProvider: Flow<String> get() = getOrInitString("lyricsProvider", DataStoreManager.LRCLIB)
    override suspend fun setLyricsProvider(provider: String) { setStoredString("lyricsProvider", provider) }

    override val translationLanguage: Flow<String> get() = getOrInitString("translationLanguage", "en")
    override suspend fun setTranslationLanguage(language: String) { setStoredString("translationLanguage", language) }

    override val maxSongCacheSize: Flow<Int> get() = getOrInitInt("maxSongCacheSize", 1024)
    override suspend fun setMaxSongCacheSize(size: Int) { setStoredInt("maxSongCacheSize", size) }

    override val watchVideoInsteadOfPlayingAudio: Flow<String> get() = getOrInitBool("watchVideoInsteadOfPlayingAudio", false)
    override suspend fun setWatchVideoInsteadOfPlayingAudio(watch: Boolean) { setStoredBool("watchVideoInsteadOfPlayingAudio", watch) }

    override val radioAudioOnly: Flow<String> get() = getOrInitBool("radioAudioOnly", true)
    override suspend fun setRadioAudioOnly(audioOnly: Boolean) { setStoredBool("radioAudioOnly", audioOnly) }

    override val playerVolume: Flow<Float> get() = getOrInitFloat("playerVolume", 1.0f)
    override suspend fun setPlayerVolume(volume: Float) { setStoredFloat("playerVolume", volume) }

    override val videoQuality: Flow<String> get() = getOrInitString("videoQuality", VIDEO_QUALITY.items[0].toString())
    override suspend fun setVideoQuality(quality: String) { setStoredString("videoQuality", quality) }

    override val spdc: Flow<String> get() = getOrInitString("spdc", "")
    override suspend fun setSpdc(spdc: String) { setStoredString("spdc", spdc) }

    override val equalizerEnabled: Flow<String> get() = getOrInitBool("equalizerEnabled", false)
    override suspend fun setEqualizerEnabled(enabled: Boolean) { setStoredBool("equalizerEnabled", enabled) }

    override val equalizerBands: Flow<String> get() = getOrInitString("equalizerBands", "")
    override suspend fun setEqualizerBands(bandsDb: List<Float>) {}

    override val equalizerPreamp: Flow<Float> get() = getOrInitFloat("equalizerPreamp", 0.0f)
    override suspend fun setEqualizerPreamp(preampDb: Float) { setStoredFloat("equalizerPreamp", preampDb) }

    override val equalizerType: Flow<String> get() = getOrInitString("equalizerType", "")
    override suspend fun setEqualizerType(type: String) { setStoredString("equalizerType", type) }

    override val equalizerAutoEqProfile: Flow<String> get() = getOrInitString("equalizerAutoEqProfile", "")
    override suspend fun setEqualizerAutoEqProfile(label: String, bandsDb: List<Float>) { setStoredString("equalizerAutoEqProfile", label) }

    override val delayEnabled: Flow<String> get() = getOrInitBool("delayEnabled", false)
    override suspend fun setDelayEnabled(enabled: Boolean) { setStoredBool("delayEnabled", enabled) }

    override val delayTimeMs: Flow<Int> get() = getOrInitInt("delayTimeMs", 200)
    override suspend fun setDelayTimeMs(timeMs: Int) { setStoredInt("delayTimeMs", timeMs) }

    override val delayFeedback: Flow<Float> get() = getOrInitFloat("delayFeedback", 0.5f)
    override suspend fun setDelayFeedback(feedback: Float) { setStoredFloat("delayFeedback", feedback) }

    override val delayMix: Flow<Float> get() = getOrInitFloat("delayMix", 0.5f)
    override suspend fun setDelayMix(mix: Float) { setStoredFloat("delayMix", mix) }

    override val reverbEnabled: Flow<String> get() = getOrInitBool("reverbEnabled", false)
    override suspend fun setReverbEnabled(enabled: Boolean) { setStoredBool("reverbEnabled", enabled) }

    override val reverbPreset: Flow<String> get() = getOrInitString("reverbPreset", "")
    override suspend fun setReverbPreset(preset: ReverbPreset) { setStoredString("reverbPreset", preset.name) }

    override val reverbMix: Flow<Float> get() = getOrInitFloat("reverbMix", 0.5f)
    override suspend fun setReverbMix(mix: Float) { setStoredFloat("reverbMix", mix) }

    override val eightDEnabled: Flow<String> get() = getOrInitBool("eightDEnabled", false)
    override suspend fun setEightDEnabled(enabled: Boolean) { setStoredBool("eightDEnabled", enabled) }

    override val eightDDirection: Flow<String> get() = getOrInitString("eightDDirection", "")
    override suspend fun setEightDDirection(direction: EightDDirection) { setStoredString("eightDDirection", direction.name) }

    override val eightDPeriodSeconds: Flow<Float> get() = getOrInitFloat("eightDPeriodSeconds", 10.0f)
    override suspend fun setEightDPeriodSeconds(seconds: Float) { setStoredFloat("eightDPeriodSeconds", seconds) }

    override val syncFollowToYouTube: Flow<String> get() = getOrInitBool("syncFollowToYouTube", false)
    override suspend fun setSyncFollowToYouTube(enabled: Boolean) { setStoredBool("syncFollowToYouTube", enabled) }

    override val spotifyLyrics: Flow<String> get() = getOrInitBool("spotifyLyrics", false)
    override suspend fun setSpotifyLyrics(spotifyLyrics: Boolean) { setStoredBool("spotifyLyrics", spotifyLyrics) }

    override val spotifyCanvas: Flow<String> get() = getOrInitBool("spotifyCanvas", false)
    override suspend fun setSpotifyCanvas(spotifyCanvas: Boolean) { setStoredBool("spotifyCanvas", spotifyCanvas) }

    override val amAnimatedArtwork: Flow<String> get() = getOrInitBool("amAnimatedArtwork", false)
    override suspend fun setAMAnimatedArtwork(enabled: Boolean) { setStoredBool("amAnimatedArtwork", enabled) }

    override val spotifyClientToken: Flow<String> get() = getOrInitString("spotifyClientToken", "")
    override suspend fun setSpotifyClientToken(token: String) { setStoredString("spotifyClientToken", token) }

    override val spotifyClientTokenExpires: Flow<Long> get() = getOrInitLong("spotifyClientTokenExpires", 0L)
    override suspend fun setSpotifyClientTokenExpires(expires: Long) { setStoredLong("spotifyClientTokenExpires", expires) }

    override val tidalClientId: Flow<String> get() = getOrInitString("tidalClientId", "")
    override suspend fun setTidalClientId(value: String) { setStoredString("tidalClientId", value) }

    override val tidalClientSecret: Flow<String> get() = getOrInitString("tidalClientSecret", "")
    override suspend fun setTidalClientSecret(value: String) { setStoredString("tidalClientSecret", value) }

    override val spotifyPersonalToken: Flow<String> get() = getOrInitString("spotifyPersonalToken", "")
    override suspend fun setSpotifyPersonalToken(token: String) { setStoredString("spotifyPersonalToken", token) }

    override val spotifyPersonalTokenExpires: Flow<Long> get() = getOrInitLong("spotifyPersonalTokenExpires", 0L)
    override suspend fun setSpotifyPersonalTokenExpires(expires: Long) { setStoredLong("spotifyPersonalTokenExpires", expires) }

    override val homeLimit: Flow<Int> get() = getOrInitInt("homeLimit", 20)
    override suspend fun setHomeLimit(limit: Int) { setStoredInt("homeLimit", limit) }

    override val chartKey: Flow<String> get() = getOrInitString("chartKey", "IN")
    override suspend fun setChartKey(key: String) { setStoredString("chartKey", key) }

    override val usingProxy: Flow<String> get() = getOrInitBool("usingProxy", false)
    override suspend fun setUsingProxy(usingProxy: Boolean) { setStoredBool("usingProxy", usingProxy) }

    override val proxyType: Flow<DataStoreManager.ProxyType> get() = flowOf(DataStoreManager.ProxyType.PROXY_TYPE_HTTP)
    override suspend fun setProxyType(proxyType: DataStoreManager.ProxyType) {}

    override val proxyHost: Flow<String> get() = getOrInitString("proxyHost", "")
    override suspend fun setProxyHost(proxyHost: String) { setStoredString("proxyHost", proxyHost) }

    override val proxyPort: Flow<Int> get() = getOrInitInt("proxyPort", 8080)
    override suspend fun setProxyPort(proxyPort: Int) { setStoredInt("proxyPort", proxyPort) }

    override val proxyUsername: Flow<String> get() = getOrInitString("proxyUsername", "")
    override suspend fun setProxyUsername(proxyUsername: String) { setStoredString("proxyUsername", proxyUsername) }

    override val proxyPassword: Flow<String> get() = getOrInitString("proxyPassword", "")
    override suspend fun setProxyPassword(proxyPassword: String) { setStoredString("proxyPassword", proxyPassword) }

    override fun getJVMProxy(): ProxyConfiguration? = null

    override val endlessQueue: Flow<String> get() = getOrInitBool("endlessQueue", true)
    override suspend fun setEndlessQueue(endlessQueue: Boolean) { setStoredBool("endlessQueue", endlessQueue) }

    override val keepYouTubePlaylistOffline: Flow<String> get() = getOrInitBool("keepYouTubePlaylistOffline", false)
    override suspend fun setKeepYouTubePlaylistOffline(keep: Boolean) { setStoredBool("keepYouTubePlaylistOffline", keep) }

    override val combineLocalAndYouTubeLiked: Flow<String> get() = getOrInitBool("combineLocalAndYouTubeLiked", false)
    override suspend fun setCombineLocalAndYouTubeLiked(combine: Boolean) { setStoredBool("combineLocalAndYouTubeLiked", combine) }

    override val shouldShowLogInRequiredAlert: Flow<String> get() = getOrInitBool("shouldShowLogInRequiredAlert", false)
    override suspend fun setShouldShowLogInRequiredAlert(shouldShow: Boolean) { setStoredBool("shouldShowLogInRequiredAlert", shouldShow) }

    override val autoCheckForUpdates: Flow<String> get() = getOrInitBool("autoCheckForUpdates", false)
    override suspend fun setAutoCheckForUpdates(autoCheck: Boolean) { setStoredBool("autoCheckForUpdates", autoCheck) }

    override val updateChannel: Flow<String> get() = getOrInitString("updateChannel", DataStoreManager.GITHUB)
    override suspend fun setUpdateChannel(channel: String) { setStoredString("updateChannel", channel) }

    override val playbackSpeed: Flow<Float> get() = getOrInitFloat("playbackSpeed", 1.0f)
    override fun setPlaybackSpeed(speed: Float) { setStoredFloat("playbackSpeed", speed) }

    override val pitch: Flow<Int> get() = getOrInitInt("pitch", 0)
    override fun setPitch(pitch: Int) { setStoredInt("pitch", pitch) }

    override val dataSyncId: Flow<String> get() = getOrInitString("dataSyncId", "")
    override suspend fun setDataSyncId(dataSyncId: String) { setStoredString("dataSyncId", dataSyncId) }

    override val visitorData: Flow<String> get() = getOrInitString("visitorData", "")
    override suspend fun setVisitorData(visitorData: String) { setStoredString("visitorData", visitorData) }

    override val aiProvider: Flow<String> get() = getOrInitString("aiProvider", "")
    override suspend fun setAIProvider(provider: String) { setStoredString("aiProvider", provider) }

    override val aiApiKey: Flow<String> get() = getOrInitString("aiApiKey", "")
    override suspend fun setAIApiKey(apiKey: String) { setStoredString("aiApiKey", apiKey) }

    override val useAITranslation: Flow<String> get() = getOrInitBool("useAITranslation", false)
    override suspend fun setUseAITranslation(use: Boolean) { setStoredBool("useAITranslation", use) }

    override val customModelId: Flow<String> get() = getOrInitString("customModelId", "")
    override suspend fun setCustomModelId(modelId: String) { setStoredString("customModelId", modelId) }

    override val customOpenAIBaseUrl: Flow<String> get() = getOrInitString("customOpenAIBaseUrl", "")
    override suspend fun setCustomOpenAIBaseUrl(baseUrl: String) { setStoredString("customOpenAIBaseUrl", baseUrl) }

    override val customOpenAIHeaders: Flow<String> get() = getOrInitString("customOpenAIHeaders", "")
    override suspend fun setCustomOpenAIHeaders(headers: String) { setStoredString("customOpenAIHeaders", headers) }

    override val localPlaylistFilter: Flow<String> get() = getOrInitString("localPlaylistFilter", "")
    override suspend fun setLocalPlaylistFilter(filter: String) { setStoredString("localPlaylistFilter", filter) }

    override val killServiceOnExit: Flow<String> get() = getOrInitBool("killServiceOnExit", false)
    override suspend fun setKillServiceOnExit(kill: Boolean) { setStoredBool("killServiceOnExit", kill) }

    override val crossfadeEnabled: Flow<String> get() = getOrInitBool("crossfadeEnabled", false)
    override suspend fun setCrossfadeEnabled(enabled: Boolean) { setStoredBool("crossfadeEnabled", enabled) }

    override val crossfadeDuration: Flow<Int> get() = getOrInitInt("crossfadeDuration", 5)
    override suspend fun setCrossfadeDuration(duration: Int) { setStoredInt("crossfadeDuration", duration) }

    override val crossfadeDjMode: Flow<String> get() = getOrInitBool("crossfadeDjMode", false)
    override suspend fun setCrossfadeDjMode(enabled: Boolean) { setStoredBool("crossfadeDjMode", enabled) }

    override val crossfadeSkipAlbum: Flow<String> get() = getOrInitBool("crossfadeSkipAlbum", false)
    override suspend fun setCrossfadeSkipAlbum(enabled: Boolean) { setStoredBool("crossfadeSkipAlbum", enabled) }

    override val autoDownloadLikedSongs: Flow<String> get() = getOrInitBool("autoDownloadLikedSongs", false)
    override suspend fun setAutoDownloadLikedSongs(enabled: Boolean) { setStoredBool("autoDownloadLikedSongs", enabled) }

    override val smartDownloadsEnabled: Flow<String> get() = getOrInitBool("smartDownloadsEnabled", false)
    override suspend fun setSmartDownloadsEnabled(enabled: Boolean) { setStoredBool("smartDownloadsEnabled", enabled) }

    override val smartDownloadsCount: Flow<Int> get() = getOrInitInt("smartDownloadsCount", 20)
    override suspend fun setSmartDownloadsCount(count: Int) { setStoredInt("smartDownloadsCount", count) }

    override val smartDownloadsLastRun: Flow<Long> get() = getOrInitLong("smartDownloadsLastRun", 0L)
    override suspend fun setSmartDownloadsLastRun(time: Long) { setStoredLong("smartDownloadsLastRun", time) }

    override val youtubeSubtitleLanguage: Flow<String> get() = getOrInitString("youtubeSubtitleLanguage", "en")
    override suspend fun setYoutubeSubtitleLanguage(language: String) { setStoredString("youtubeSubtitleLanguage", language) }

    override val preferredAudioLanguage: Flow<String> get() = getOrInitString("preferredAudioLanguage", "en")
    override suspend fun setPreferredAudioLanguage(language: String) { setStoredString("preferredAudioLanguage", language) }

    override val helpBuildLyricsDatabase: Flow<String> get() = getOrInitBool("helpBuildLyricsDatabase", false)
    override suspend fun setHelpBuildLyricsDatabase(help: Boolean) { setStoredBool("helpBuildLyricsDatabase", help) }

    override val contributorName: Flow<String> get() = getOrInitString("contributorName", "")
    override val contributorEmail: Flow<String> get() = getOrInitString("contributorEmail", "")
    override suspend fun setContributorLyricsDatabase(contributor: Pair<String, String>?) {
        if (contributor != null) {
            setStoredString("contributorName", contributor.first)
            setStoredString("contributorEmail", contributor.second)
        }
    }

    override val backupDownloaded: Flow<String> get() = getOrInitBool("backupDownloaded", false)
    override suspend fun setBackupDownloaded(backupDownloaded: Boolean) { setStoredBool("backupDownloaded", backupDownloaded) }

    override val enableLiquidGlass: Flow<String> get() = getOrInitBool("enableLiquidGlass", false)
    override suspend fun setEnableLiquidGlass(enable: Boolean) { setStoredBool("enableLiquidGlass", enable) }

    override val themeMode: Flow<String> get() = getOrInitString("themeMode", DataStoreManager.THEME_MODE_SYSTEM)
    override suspend fun setThemeMode(mode: String) { setStoredString("themeMode", mode) }

    override val themeColorSource: Flow<String> get() = getOrInitString("themeColorSource", DataStoreManager.THEME_COLOR_DEFAULT)
    override suspend fun setThemeColorSource(source: String) { setStoredString("themeColorSource", source) }

    override val customThemeColor: Flow<String> get() = getOrInitString("customThemeColor", DataStoreManager.DEFAULT_THEME_COLOR_HEX)
    override suspend fun setCustomThemeColor(argbHex: String) { setStoredString("customThemeColor", argbHex) }

    override val nowPlayingStyle: Flow<String> get() = getOrInitString("nowPlayingStyle", DataStoreManager.NOW_PLAYING_STYLE_SPOTIFY)
    override suspend fun setNowPlayingStyle(style: String) { setStoredString("nowPlayingStyle", style) }

    override val lyricsStyle: Flow<String> get() = getOrInitString("lyricsStyle", "")
    override suspend fun setLyricsStyle(style: String) { setStoredString("lyricsStyle", style) }

    override val romanizationLanguages: Flow<String> get() = getOrInitString("romanizationLanguages", "")
    override suspend fun setRomanizationLanguages(languages: String) { setStoredString("romanizationLanguages", languages) }

    override val lyricsOffsetMs: Flow<Int> get() = getOrInitInt("lyricsOffsetMs", 0)
    override suspend fun setLyricsOffsetMs(offsetMs: Int) { setStoredInt("lyricsOffsetMs", offsetMs) }

    override val explicitContentEnabled: Flow<String> get() = getOrInitBool("explicitContentEnabled", true)
    override suspend fun setExplicitContentEnabled(enabled: Boolean) { setStoredBool("explicitContentEnabled", enabled) }

    override val discordToken: Flow<String> get() = getOrInitString("discordToken", "")
    override suspend fun setDiscordToken(token: String) { setStoredString("discordToken", token) }

    override val discordSession: Flow<DiscordSession?> get() = flowOf(null)
    override suspend fun setDiscordSession(session: DiscordSession?) {}

    override val richPresenceEnabled: Flow<String> get() = getOrInitBool("richPresenceEnabled", false)
    override suspend fun setRichPresenceEnabled(enabled: Boolean) { setStoredBool("richPresenceEnabled", enabled) }

    override val lastfmSessionKey: Flow<String> get() = getOrInitString("lastfmSessionKey", "")
    override val lastfmUsername: Flow<String> get() = getOrInitString("lastfmUsername", "")
    override suspend fun setLastfmSession(sessionKey: String, username: String) {
        setStoredString("lastfmSessionKey", sessionKey)
        setStoredString("lastfmUsername", username)
    }

    override val lastfmScrobbleEnabled: Flow<String> get() = getOrInitBool("lastfmScrobbleEnabled", false)
    override suspend fun setLastfmScrobbleEnabled(enabled: Boolean) { setStoredBool("lastfmScrobbleEnabled", enabled) }

    override val localTrackingEnabled: Flow<String> get() = getOrInitBool("localTrackingEnabled", true)
    override suspend fun setLocalTrackingEnabled(enabled: Boolean) { setStoredBool("localTrackingEnabled", enabled) }

    override val blogNotificationEnabled: Flow<String> get() = getOrInitBool("blogNotificationEnabled", false)
    override suspend fun setBlogNotificationEnabled(enabled: Boolean) { setStoredBool("blogNotificationEnabled", enabled) }

    override val autoBackupEnabled: Flow<String> get() = getOrInitBool("autoBackupEnabled", false)
    override suspend fun setAutoBackupEnabled(enabled: Boolean) { setStoredBool("autoBackupEnabled", enabled) }

    override val autoBackupFrequency: Flow<String> get() = getOrInitString("autoBackupFrequency", "")
    override suspend fun setAutoBackupFrequency(frequency: String) { setStoredString("autoBackupFrequency", frequency) }

    override val autoBackupMaxFiles: Flow<Int> get() = getOrInitInt("autoBackupMaxFiles", 5)
    override suspend fun setAutoBackupMaxFiles(max: Int) { setStoredInt("autoBackupMaxFiles", max) }

    override val autoBackupLastTime: Flow<Long> get() = getOrInitLong("autoBackupLastTime", 0L)
    override suspend fun setAutoBackupLastTime(time: Long) { setStoredLong("autoBackupLastTime", time) }
}
