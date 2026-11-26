package com.streamify.supabase

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OAuthPkceSessionTest {

    @Test
    fun createSessionGeneratesDistinctAndCompliantTokens() {
        val session1 = OAuthPkceSession.createSession()
        val session2 = OAuthPkceSession.createSession()

        assertEquals(64, session1.verifier.length)
        assertEquals(32, session1.state.length)
        assertTrue(session1.verifier != session2.verifier)
        assertTrue(session1.state != session2.state)
        assertTrue(session1.timestampMs > 0L)
    }

    @Test
    fun buildAuthorizeUrlEmbedsStateInRedirectToAndTopLevelQuery() {
        val session = PkceSession(
            verifier = "verifier_12345678901234567890123456789012345678901234",
            challenge = "challenge_xyz",
            state = "state_csrf_token_abc"
        )
        val url = OAuthPkceSession.buildAuthorizeUrl("streamify://callback", session)

        assertTrue(url.startsWith("${SupabaseConfig.SUPABASE_URL}/auth/v1/authorize?"))
        assertTrue(url.contains("provider=google"))
        // redirect_to must contain state encoded: "streamify://callback?state=state_csrf_token_abc" -> "streamify%3A%2F%2Fcallback%3Fstate%3Dstate_csrf_token_abc"
        assertTrue(url.contains("redirect_to=streamify%3A%2F%2Fcallback%3Fstate%3Dstate_csrf_token_abc"))
        assertTrue(url.contains("code_challenge=challenge_xyz"))
        assertTrue(url.contains("code_challenge_method=S256"))
        assertTrue(url.contains("state=state_csrf_token_abc"))
        assertTrue(url.contains("prompt=select_account"))
    }

    @Test
    fun buildAuthorizeUrlAppendsAmpersandWhenRedirectUriAlreadyHasQuery() {
        val session = PkceSession(
            verifier = "v",
            challenge = "c",
            state = "s"
        )
        val url = OAuthPkceSession.buildAuthorizeUrl("http://localhost:8080/callback?source=desktop", session)
        assertTrue(url.contains("redirect_to=http%3A%2F%2Flocalhost%3A8080%2Fcallback%3Fsource%3Ddesktop%26state%3Ds"))
    }

    @Test
    fun constantTimeEqualsBehavesCorrectly() {
        assertTrue(PkceCrypto.constantTimeEquals("abc123XYZ", "abc123XYZ"))
        assertFalse(PkceCrypto.constantTimeEquals("abc123XYZ", "abc123XYz"))
        assertFalse(PkceCrypto.constantTimeEquals("abc123XYZ", "abc123XY"))
        assertFalse(PkceCrypto.constantTimeEquals("", "a"))
        assertTrue(PkceCrypto.constantTimeEquals("", ""))
    }

    @Test
    fun urlEncodeEncodesReservedCharacters() {
        assertEquals("abc_123-.~", PkceCrypto.urlEncode("abc_123-.~"))
        assertEquals("streamify%3A%2F%2Fcallback%3Fstate%3Dxyz%26foo%3Dbar", PkceCrypto.urlEncode("streamify://callback?state=xyz&foo=bar"))
    }

    @Test
    fun validateCallbackSucceedsWhenStateMatchesExactly() {
        val callback = AuthCallbackData(code = "auth_code_1", state = "csrf_token_valid")
        val result = OAuthPkceSession.validateCallback(
            callbackData = callback,
            storedState = "csrf_token_valid",
            storedVerifier = "local_verifier_token",
            storedTimestampMs = 1000L,
            currentTimeMs = 2000L
        )

        assertTrue(result.isSuccess)
        assertEquals("local_verifier_token", result.getOrNull())
    }

    @Test
    fun validateCallbackRejectsStateMismatch() {
        val callback = AuthCallbackData(code = "auth_code_1", state = "attacker_injected_state")
        val result = OAuthPkceSession.validateCallback(
            callbackData = callback,
            storedState = "victim_original_state",
            storedVerifier = "local_verifier_token",
            storedTimestampMs = 1000L,
            currentTimeMs = 2000L
        )

        assertTrue(result.isFailure)
        val exception = result.exceptionOrNull()
        assertTrue(exception is SecurityException)
        assertEquals("Missing or mismatched CSRF state", exception.message)
    }

    @Test
    fun validateCallbackSucceedsWithPkceVerifierWhenBrokerStripsState() {
        // Supabase / GoTrue may strip state and return only ?code=...
        val callback = AuthCallbackData(code = "auth_code_from_supabase", state = null)
        val result = OAuthPkceSession.validateCallback(
            callbackData = callback,
            storedState = "csrf_token_not_returned_by_broker",
            storedVerifier = "local_pkce_verifier_secret",
            storedTimestampMs = 1000L,
            currentTimeMs = 2000L
        )

        assertTrue(result.isSuccess)
        assertEquals("local_pkce_verifier_secret", result.getOrNull())
    }

    @Test
    fun validateCallbackFailsWhenBothStateAndVerifierAreMissing() {
        val callback = AuthCallbackData(code = "unsolicited_code", state = null)
        val result = OAuthPkceSession.validateCallback(
            callbackData = callback,
            storedState = null,
            storedVerifier = null,
            storedTimestampMs = 0L
        )

        assertTrue(result.isFailure)
        val exception = result.exceptionOrNull()
        assertTrue(exception is SecurityException)
        assertEquals("Missing CSRF security state and PKCE verifier", exception.message)
    }

    @Test
    fun validateCallbackRejectsExpiredSession() {
        val callback = AuthCallbackData(code = "code_1", state = "state_1")
        val start = 1_000_000L
        val expiredTime = start + (11 * 60 * 1000L) // 11 minutes later (max age is 10 min)

        val result = OAuthPkceSession.validateCallback(
            callbackData = callback,
            storedState = "state_1",
            storedVerifier = "verifier_1",
            storedTimestampMs = start,
            currentTimeMs = expiredTime
        )

        assertTrue(result.isFailure)
        val exception = result.exceptionOrNull()
        assertTrue(exception is SecurityException)
        assertEquals("Expired OAuth PKCE session", exception.message)
    }
}
