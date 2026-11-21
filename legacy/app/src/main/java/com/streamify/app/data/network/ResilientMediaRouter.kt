package com.streamify.app.data.network

import com.streamify.app.util.SLog as Log
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Resilient Circuit-Breaker Media Router
 * Prioritizes high-speed Pure Kotlin HTTP/2 paths (<60ms) and silently falls back
 * to Lazy Python (yt-dlp) if the primary path returns empty, times out, or fails.
 */
object ResilientMediaRouter {
    private const val TAG = "ResilientMediaRouter"

    suspend fun <T> fetchWithFallback(
        timeoutMs: Long = 2500L,
        primary: suspend () -> T?,
        fallback: (suspend () -> T?)? = null
    ): T? {
        // Step 1: Execute high-speed Pure Kotlin path with timeout protection
        try {
            val result = withTimeoutOrNull(timeoutMs) {
                primary()
            }
            if (result != null) {
                // If result is a collection, ensure it's not empty
                if (result !is Collection<*> || result.isNotEmpty()) {
                    return result
                }
            }
            Log.w(TAG, "Kotlin path returned empty/null or timed out. Routing to fallback.")
        } catch (e: Exception) {
            Log.e(TAG, "Kotlin primary path encountered error: ${e.message}. Routing to fallback.", e)
        }

        // Step 2: Execute Fallback if available
        if (fallback != null) {
            try {
                return fallback()
            } catch (e: Exception) {
                Log.e(TAG, "Fallback execution failed: ${e.message}", e)
            }
        }

        return null
    }
}
