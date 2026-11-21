package com.streamify.app.data.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object ArtworkResolutionPipeline {

    suspend fun resolveBestArtwork(
        videoId: String,
        title: String,
        artist: String,
        currentCoverUrl: String?
    ): String = withContext(Dispatchers.IO) {
        val raw = currentCoverUrl?.trim() ?: ""

        // Tier 1: Already a pristine 1:1 Google CDN / Apple Music URL
        if (raw.contains("googleusercontent.com") || raw.contains("ggpht.com") || raw.contains("mzstatic.com")) {
            return@withContext YouTubeStreamResolver.sanitizeCoverUrl(raw, videoId) ?: raw
        }

        // Tier 2: Apple iTunes Search API (1400x1400 True Square Album Master)
        if (title.isNotBlank()) {
            val itunesCover = runCatching {
                iTunesSearchApi.fetchHdCoverArt(title, artist)
            }.getOrNull()
            if (!itunesCover.isNullOrBlank()) {
                return@withContext itunesCover
            }
        }

        // Tier 3: YouTube maxresdefault.jpg (1280x720 clean 16:9 un-letterboxed frame)
        return@withContext YouTubeStreamResolver.sanitizeCoverUrl(raw, videoId) ?: raw
    }
}
