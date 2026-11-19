package com.maxrave.simpmusic.utils

import com.maxrave.common.QUALITY
import com.maxrave.common.VIDEO_QUALITY
import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.logger.Logger
import kotlinx.coroutines.flow.first

/**
 * Initializes and enforces Streamify default preferences:
 * 1. Light Mode is default.
 * 2. Analytics page is default landing destination and local tracking is enabled.
 * 3. All sound and video qualities are maxed out by default (256kbps Opus/AAC, 1080p video).
 */
object StreamifyDefaultsInitializer {

    private const val TAG = "StreamifyDefaults"

    suspend fun initialize(dataStoreManager: DataStoreManager) {
        try {
            // 1. Light Mode default
            val currentTheme = dataStoreManager.themeMode.first()
            if (currentTheme == DataStoreManager.THEME_MODE_DARK) {
                Logger.d("Streamify: Setting default theme mode to LIGHT", TAG)
                dataStoreManager.setThemeMode(DataStoreManager.THEME_MODE_LIGHT)
            }

            // 2. Enable local tracking so Analytics page and Wrapped function out-of-the-box
            val isTracking = dataStoreManager.localTrackingEnabled.first()
            if (isTracking != DataStoreManager.TRUE) {
                Logger.d("Streamify: Enabling local tracking for Analytics default", TAG)
                dataStoreManager.setLocalTrackingEnabled(true)
            }

            // 3. Max out all sound qualities by default
            val currentQuality = dataStoreManager.quality.first()
            if (currentQuality == QUALITY.items[0].toString() || currentQuality.isEmpty()) {
                Logger.d("Streamify: Maxing out audio streaming quality to High Opus/AAC", TAG)
                dataStoreManager.setQuality(QUALITY.items[2].toString())
            }

            val currentDownloadQuality = dataStoreManager.downloadQuality.first()
            if (currentDownloadQuality == QUALITY.items[0].toString() || currentDownloadQuality.isEmpty()) {
                Logger.d("Streamify: Maxing out download audio quality to High Opus/AAC", TAG)
                dataStoreManager.setDownloadQuality(QUALITY.items[2].toString())
            }

            val currentVideoQuality = dataStoreManager.videoQuality.first()
            if (currentVideoQuality != VIDEO_QUALITY.items[0].toString()) {
                Logger.d("Streamify: Maxing out video quality to 1080p", TAG)
                dataStoreManager.setVideoQuality(VIDEO_QUALITY.items[0].toString())
            }

            val currentVideoDownloadQuality = dataStoreManager.videoDownloadQuality.first()
            if (currentVideoDownloadQuality != VIDEO_QUALITY.items[0].toString()) {
                Logger.d("Streamify: Maxing out video download quality to 1080p", TAG)
                dataStoreManager.setVideoDownloadQuality(VIDEO_QUALITY.items[0].toString())
            }

            // 4. Apple Music lyrics style default
            val currentLyricsStyle = dataStoreManager.lyricsStyle.first()
            if (currentLyricsStyle == DataStoreManager.LYRICS_STYLE_CLASSIC || currentLyricsStyle.isEmpty()) {
                Logger.d("Streamify: Setting default lyrics style to APPLE_MUSIC", TAG)
                dataStoreManager.setLyricsStyle(DataStoreManager.LYRICS_STYLE_APPLE_MUSIC)
            }

            // 5. Apple Music Animated Artwork default enabled
            val currentAnimatedArtwork = dataStoreManager.amAnimatedArtwork.first()
            if (currentAnimatedArtwork != DataStoreManager.TRUE) {
                Logger.d("Streamify: Enabling Apple Music animated artwork by default", TAG)
                dataStoreManager.setAMAnimatedArtwork(true)
            }
        } catch (e: Exception) {
            Logger.e("Streamify: Failed to initialize defaults: ${e.message}", TAG)
        }
    }
}
