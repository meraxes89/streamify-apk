package com.streamify.supabase

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlatformTokenStoreTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @AfterTest
    fun tearDown() {
        SupabaseClient.clearSession()
    }

    @Test
    fun storedAuthSessionRoundTripsThroughJson() {
        val original = StoredAuthSession(
            user = UserProfile(
                id = "user-123",
                email = "tester@streamify.app",
                displayName = "Tester",
                avatarUrl = "https://example.com/a.png",
                isAdmin = false,
            ),
            accessToken = "jwt.access.token",
            refreshToken = "rt_rotated_456",
            expiresAtEpochMs = 1_800_000_000_000L,
        )

        val encoded = json.encodeToString(StoredAuthSession.serializer(), original)
        val decoded = json.decodeFromString<StoredAuthSession>(encoded)
        assertEquals(original, decoded)
    }

    @Test
    fun setSessionUpdatesTokenAndExpiryAndClearResets() {
        val user = UserProfile(id = "u1", email = "u1@example.com", displayName = "U1", isAdmin = false)
        SupabaseClient.setSession(
            token = "valid_jwt_token",
            profile = user,
            refreshToken = "rt_1",
            expiresAtEpochMs = 1_900_000_000_000L,
        )

        assertTrue(SupabaseClient.hasValidUserSession())
        assertEquals("valid_jwt_token", SupabaseClient.accessToken.value)
        assertEquals("rt_1", SupabaseClient.refreshToken.value)
        assertEquals(1_900_000_000_000L, SupabaseClient.expiresAtEpochMs.value)

        SupabaseClient.clearSession()
        assertFalse(SupabaseClient.hasValidUserSession())
        assertEquals(null, SupabaseClient.accessToken.value)
        assertEquals(null, SupabaseClient.refreshToken.value)
        assertEquals(0L, SupabaseClient.expiresAtEpochMs.value)
    }

    @Test
    fun ensureValidAccessTokenReturnsAnonKeyWhenUnauthenticated() = runBlocking {
        SupabaseClient.clearSession()
        assertEquals(SupabaseConfig.ANON_KEY, SupabaseClient.ensureValidAccessToken())
    }
}
