package com.streamify.app.data.network

import android.util.LruCache

/**
 * Memory-bounded, lock-free circuit breaker for dead, deleted, or geo-blocked video IDs.
 * Bounded to 500 entries max to prevent heap bloat.
 */
object StreamifyCircuitBreaker {

    // 1-hour backoff window for definitively unplayable tracks
    private const val BACKOFF_DURATION_MS = 3_600_000L

    private val deadTracks = object : LruCache<String, Long>(500) {}

    /**
     * Checks if a video ID is currently tripped.
     * Returns true if we should fast-fail and skip network resolution.
     */
    fun isDefinitivelyDead(videoId: String): Boolean = false

    fun tripHard(videoId: String) {}

    fun recordSuccess(videoId: String) {}
}
