package com.streamify.supabase

import kotlin.time.Clock

/**
 * Ephemeral session state for an OAuth 2.1 PKCE authorization request.
 */
data class PkceSession(
    val verifier: String,
    val challenge: String,
    val state: String,
    val timestampMs: Long = Clock.System.now().toEpochMilliseconds(),
)

/**
 * Universal coordinator for building OAuth authorize URLs with preserved state
 * and validating returning callbacks across all Kotlin Multiplatform targets.
 */
object OAuthPkceSession {
    const val DEFAULT_MAX_AGE_MS = 10 * 60 * 1000L // 10 minutes

    /**
     * Creates a new cryptographically random PKCE session.
     */
    fun createSession(): PkceSession {
        val verifier = PkceCrypto.generateCodeVerifier()
        val challenge = PkceCrypto.deriveCodeChallenge(verifier)
        val state = PkceCrypto.generateState()
        return PkceSession(verifier, challenge, state)
    }

    /**
     * Constructs the Supabase /auth/v1/authorize URL.
     *
     * Embeds [session.state] inside the [baseRedirectUri]'s query string so that Supabase GoTrue
     * preserves it when issuing the 302 redirect back to the client application, while also
     * specifying the top-level &state parameter for OAuth intermediaries.
     */
    fun buildAuthorizeUrl(
        baseRedirectUri: String,
        session: PkceSession,
        provider: String = "google",
    ): String {
        val redirectWithState = if (baseRedirectUri.contains("?")) {
            "$baseRedirectUri&state=${session.state}"
        } else {
            "$baseRedirectUri?state=${session.state}"
        }
        val encodedRedirect = PkceCrypto.urlEncode(redirectWithState)
        return "${SupabaseConfig.SUPABASE_URL}/auth/v1/authorize?" +
                "provider=$provider" +
                "&redirect_to=$encodedRedirect" +
                "&code_challenge=${session.challenge}" +
                "&code_challenge_method=S256" +
                "&state=${session.state}" +
                "&prompt=select_account"
    }

    /**
     * Validates an incoming callback against stored session state.
     *
     * Dual defense-in-depth:
     * 1. If [callbackData.state] is returned by the broker/identity provider, it MUST match
     *    [storedState] in constant time.
     * 2. If [callbackData.state] is stripped or omitted by an intermediary broker, validation
     *    proceeds ONLY if a valid [storedVerifier] exists. Under RFC 7636 (PKCE), code injection
     *    is cryptographically impossible because an attacker cannot redeem the authorization code
     *    without the client's local code_verifier.
     * 3. If neither state nor verifier is present, or if [storedTimestampMs] exceeds [maxAgeMs],
     *    the request fails closed immediately.
     *
     * @return Result containing the verified code_verifier string.
     */
    fun validateCallback(
        callbackData: AuthCallbackData,
        storedState: String?,
        storedVerifier: String?,
        storedTimestampMs: Long = 0L,
        currentTimeMs: Long = Clock.System.now().toEpochMilliseconds(),
        maxAgeMs: Long = DEFAULT_MAX_AGE_MS,
    ): Result<String> {
        if (storedTimestampMs > 0L && currentTimeMs - storedTimestampMs > maxAgeMs) {
            return Result.failure(IllegalStateException("Expired OAuth PKCE session"))
        }

        val callbackState = callbackData.state
        if (!callbackState.isNullOrBlank()) {
            if (storedState.isNullOrBlank() || !PkceCrypto.constantTimeEquals(callbackState, storedState)) {
                return Result.failure(IllegalStateException("Missing or mismatched CSRF state"))
            }
        } else {
            // Broker stripped query state: ensure we at least hold an active local PKCE verifier
            if (storedState.isNullOrBlank() && storedVerifier.isNullOrBlank()) {
                return Result.failure(IllegalStateException("Missing CSRF security state and PKCE verifier"))
            }
        }

        if (storedVerifier.isNullOrBlank()) {
            return Result.failure(Exception("No pending PKCE code verifier found"))
        }

        return Result.success(storedVerifier)
    }
}
