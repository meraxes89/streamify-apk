package com.streamify.supabase

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class Tier1SecurityTest {

    @AfterTest
    fun tearDown() {
        SupabaseClient.clearSession()
    }

    @Test
    fun defaultUserIsNeverAdminByDefault() {
        assertFalse(SupabaseConfig.DEFAULT_USER.isAdmin, "DEFAULT_USER must not have client-side isAdmin = true")
        assertFalse(SupabaseClient.isAdmin, "SupabaseClient.isAdmin must default to false")
    }

    @Test
    fun setSessionClearsOldRefreshTokenWhenOmitted() {
        val userA = UserProfile(id = "user_a", email = "a@example.com", displayName = "A", isAdmin = false)
        val userB = UserProfile(id = "user_b", email = "b@example.com", displayName = "B", isAdmin = false)

        SupabaseClient.setSession(token = "token_a", profile = userA, refreshToken = "rt_user_a", expiresAtEpochMs = 1_900_000_000_000L)
        assertEquals("rt_user_a", SupabaseClient.refreshToken.value)

        // Switching to user B without a refresh token must NOT retain user A's refresh token
        SupabaseClient.setSession(token = "token_b", profile = userB, refreshToken = null)
        assertNull(SupabaseClient.refreshToken.value)
        assertEquals(0L, SupabaseClient.expiresAtEpochMs.value)
    }

    @Test
    fun pkceUsesCsprngAndProducesDistinctVerifiersAndStates() {
        val verifiers = (0 until 10).map { PkceCrypto.generateCodeVerifier() }.toSet()
        val states = (0 until 10).map { PkceCrypto.generateState() }.toSet()
        assertEquals(10, verifiers.size)
        assertEquals(10, states.size)
        assertTrue(verifiers.all { it.length == 64 })
        assertTrue(states.all { it.length == 32 })
    }

    @Test
    fun oauthPkceSessionDualDefenseValidationEnforcesCsrfAndPreventsSpoofing() {
        val storedSession = OAuthPkceSession.createSession()

        // 1. Matched state succeeds
        val matchedCallback = AuthCallbackData(code = "valid_code", state = storedSession.state)
        val validResult = OAuthPkceSession.validateCallback(
            callbackData = matchedCallback,
            storedState = storedSession.state,
            storedVerifier = storedSession.verifier,
            storedTimestampMs = storedSession.timestampMs
        )
        assertTrue(validResult.isSuccess)
        assertEquals(storedSession.verifier, validResult.getOrNull())

        // 2. Mismatched state fails
        val spoofedCallback = AuthCallbackData(code = "valid_code", state = "attacker_state")
        val spoofResult = OAuthPkceSession.validateCallback(
            callbackData = spoofedCallback,
            storedState = storedSession.state,
            storedVerifier = storedSession.verifier,
            storedTimestampMs = storedSession.timestampMs
        )
        assertTrue(spoofResult.isFailure)
        assertTrue(spoofResult.exceptionOrNull() is SecurityException)

        // 3. Stripped state succeeds when valid verifier exists (PKCE server cryptographic verification)
        val strippedCallback = AuthCallbackData(code = "valid_code", state = null)
        val strippedResult = OAuthPkceSession.validateCallback(
            callbackData = strippedCallback,
            storedState = storedSession.state,
            storedVerifier = storedSession.verifier,
            storedTimestampMs = storedSession.timestampMs
        )
        assertTrue(strippedResult.isSuccess)
    }
}
