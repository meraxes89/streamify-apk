package com.streamify.supabase

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlin.time.Clock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AuthRefreshMutexTest {

    @Test
    fun executeSingleFlightOnlyExecutesLeaderWhenConcurrent() = runTest {
        val mutex = AuthRefreshMutex()
        var executionCount = 0

        val refreshAction: suspend () -> Result<TokenSession> = {
            delay(50) // Simulate network latency
            executionCount++
            Result.success(TokenSession("new_access_token", "new_refresh_token", 100_000L))
        }

        // Launch 5 concurrent refresh attempts
        val deferreds = (1..5).map {
            async {
                mutex.executeSingleFlight(this) {
                    refreshAction()
                }
            }
        }

        val results = deferreds.awaitAll()

        // Verify exactly ONE network refresh was executed
        assertEquals(1, executionCount)

        // Verify all 5 callers received the exact same successful token session
        for (res in results) {
            assertTrue(res.isSuccess)
            assertEquals("new_access_token", res.getOrNull()?.accessToken)
            assertEquals("new_refresh_token", res.getOrNull()?.refreshToken)
        }
    }

    @Test
    fun clockSkewTrackingCalculatesCorrectOffset() {
        val mutex = AuthRefreshMutex()
        val localNow = Clock.System.now().toEpochMilliseconds()
        val serverTimeTenMinutesAhead = localNow + 600_000L

        mutex.recordServerTime(serverTimeTenMinutesAhead)

        // Skew should be approximately +600,000ms
        val skew = mutex.serverClockSkewMs
        assertTrue(skew in 599_000L..601_000L, "Expected clock skew around +600_000, got $skew")
    }

    @Test
    fun isTokenExpiredRespectsBufferAndSkew() {
        val mutex = AuthRefreshMutex()
        val localNow = Clock.System.now().toEpochMilliseconds()

        // Token expires in 4 minutes (240s). Buffer is 5 minutes (300s).
        // Since 240s < 300s, it should be marked as expired (proactive refresh needed).
        val expiresAt = localNow + 240_000L
        assertTrue(mutex.isTokenExpired(expiresAt, bufferSeconds = 300))

        // Token expires in 10 minutes (600s). Buffer is 5 minutes (300s).
        // Should NOT be marked as expired.
        val distantExpiresAt = localNow + 600_000L
        assertFalse(mutex.isTokenExpired(distantExpiresAt, bufferSeconds = 300))
    }
}
