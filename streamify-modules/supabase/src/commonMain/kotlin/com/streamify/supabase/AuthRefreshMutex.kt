package com.streamify.supabase

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock

/**
 * Encapsulates an active token pair and its expiration timestamp.
 */
data class TokenSession(
    val accessToken: String,
    val refreshToken: String?,
    val expiresAtEpochMs: Long
)

/**
 * Thread-safe single-flight coordinator for Supabase token refreshes.
 *
 * Prevents the Supabase Refresh Token Rotation (RTR) trap:
 * When multiple parallel network requests detect an expired access token simultaneously,
 * naive clients fire concurrent refresh calls with the same refresh token.
 * Because Supabase invalidates the old refresh token upon issuing a new one, the second
 * request triggers Supabase token reuse detection and revokes the entire token family.
 *
 * This coordinator ensures that exactly one refresh HTTP request is dispatched.
 * All other concurrent callers suspend non-blockingly and receive the exact same result.
 */
class AuthRefreshMutex {

    private val mutex = Mutex()
    private var inFlightDeferred: Deferred<Result<TokenSession>>? = null

    // Track clock skew in milliseconds: serverEpochMs - localEpochMs
    var serverClockSkewMs: Long = 0L
        private set

    /**
     * Updates the clock skew estimate based on a server-provided timestamp
     * (e.g. from an HTTP 'Date' response header).
     */
    fun recordServerTime(serverEpochMs: Long) {
        val localNow = Clock.System.now().toEpochMilliseconds()
        serverClockSkewMs = serverEpochMs - localNow
    }

    /**
     * Returns true if the token is expired or within the proactive buffer window (default 300s / 5m),
     * adjusted for estimated server clock skew.
     */
    fun isTokenExpired(expiresAtEpochMs: Long, bufferSeconds: Long = 300): Boolean {
        if (expiresAtEpochMs <= 0) return false
        val estimatedServerNow = Clock.System.now().toEpochMilliseconds() + serverClockSkewMs
        val bufferMs = bufferSeconds * 1000L
        return estimatedServerNow >= (expiresAtEpochMs - bufferMs)
    }

    /**
     * Executes the given refresh action in a single-flight manner.
     * If a refresh is already in-flight, this method suspends and returns the existing result
     * without making another network call.
     */
    suspend fun executeSingleFlight(
        scope: CoroutineScope,
        refreshAction: suspend () -> Result<TokenSession>
    ): Result<TokenSession> {
        val deferred = mutex.withLock {
            val existing = inFlightDeferred
            if (existing != null && existing.isActive) {
                existing
            } else {
                val newDeferred = scope.async {
                    try {
                        refreshAction()
                    } finally {
                        mutex.withLock {
                            inFlightDeferred = null
                        }
                    }
                }
                inFlightDeferred = newDeferred
                newDeferred
            }
        }
        return deferred.await()
    }
}
