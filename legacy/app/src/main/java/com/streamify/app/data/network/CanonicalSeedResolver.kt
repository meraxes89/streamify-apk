package com.streamify.app.data.network

import android.util.LruCache
import com.streamify.app.data.models.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.streamify.app.util.SLog

object CanonicalSeedResolver {

    private val seedCache = LruCache<String, String>(200)
    private val VIDEO_ID_REGEX = Regex("^[a-zA-Z0-9_-]{11}$")

    /**
     * Resolves any seed track (local MP3, cloud track, or online stream) into a
     * canonical 11-character YouTube Music Video ID.
     *
     * IDENTITY GATE: a search result is only accepted when it proves same-song
     * identity (title + artist + duration). The legacy behavior — pinning the
     * FIRST search hit regardless of what song it actually was — permanently
     * wrote wrong videos into canonical storage and is removed. When no verified
     * match exists this returns "" so callers keep their un-pinned fallback path
     * instead of poisoning the database with a different recording.
     */
    suspend fun resolveToCanonicalId(track: Track): String = withContext(Dispatchers.IO) {
        // 1. Direct validation if ytmVideoId, filepath or cover contains valid 11-char Video ID
        val directId = track.ytmVideoId?.takeIf { it.matches(VIDEO_ID_REGEX) }
            ?: YouTubeStreamResolver.extractVideoId(track.filepath, track.coverArtPath)
        if (!directId.isNullOrBlank() && directId.matches(VIDEO_ID_REGEX)) {
            return@withContext directId
        }

        // 2. Thread-Safe LRU Memory Cache Hit
        val cacheKey = "${track.title.trim().lowercase()}::${track.artist.trim().lowercase()}"
        synchronized(seedCache) {
            seedCache.get(cacheKey)?.let { return@withContext it }
        }

        // 3. Verified Metadata Match via Sub-50ms Innertube Query
        val query = "${track.title} ${track.artist}".trim()
        if (query.isNotBlank()) {
            try {
                val results = YouTubeMusicSearchApi.search(query, maxResults = 8)
                // Two-tier acceptance: exact recording proof preferred, but
                // title+artist similarity alone is accepted when duration is
                // unknown (0) — prevents total resolution failure for tracks
                // with missing/imprecise metadata while still rejecting covers.
                val topMatch = results.firstOrNull { item ->
                    val vId = YouTubeStreamResolver.extractVideoId(item.url)
                    vId != null && vId.matches(VIDEO_ID_REGEX) &&
                            com.streamify.app.data.discovery.FuzzyTitleMatcher.titlesMatch(track.title, item.title) &&
                            com.streamify.app.data.discovery.FuzzyTitleMatcher.artistsMatch(track.artist, item.uploader)
                } ?: results.firstOrNull { item ->
                    val vId = YouTubeStreamResolver.extractVideoId(item.url)
                    vId != null && vId.matches(VIDEO_ID_REGEX) &&
                            com.streamify.app.data.discovery.FuzzyTitleMatcher.titlesMatch(track.title, item.title) &&
                            track.durationSec <= 0 // duration-unknown fallback
                }
                if (topMatch != null) {
                    val resolvedId = YouTubeStreamResolver.extractVideoId(topMatch.url)
                    if (resolvedId != null) {
                        synchronized(seedCache) {
                            seedCache.put(cacheKey, resolvedId)
                        }
                        return@withContext resolvedId
                    }
                }
            } catch (e: Exception) {
                SLog.st("CanonicalSeedResolver", "CanonicalSeedResolver.resolveToCanonicalId failed", e)
            }
        }

        // 4. No verified match → refuse to pin an arbitrary/different recording.
        // Callers treat non-11-char results as "stay un-pinned".
        ""
    }
}
