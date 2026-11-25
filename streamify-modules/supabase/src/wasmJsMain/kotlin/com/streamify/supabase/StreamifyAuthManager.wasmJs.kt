package com.streamify.supabase

import kotlinx.browser.sessionStorage
import kotlinx.browser.window
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

actual object StreamifyAuthManager {
    private val _authState = MutableStateFlow<AuthState>(AuthState.Unauthenticated)
    actual val authState: StateFlow<AuthState> = _authState.asStateFlow()

    actual fun init(context: Any?) {
        if (SupabaseClient.hasValidUserSession()) {
            val user = SupabaseClient.currentUser.value ?: SupabaseConfig.DEFAULT_USER
            _authState.value = AuthState.Authenticated(user)
        } else {
            _authState.value = AuthState.Unauthenticated
        }
    }

    actual suspend fun signInWithGoogle(context: Any?): Result<UserProfile> {
        return try {
            val verifier = PkceCrypto.generateCodeVerifier()
            val challenge = PkceCrypto.deriveCodeChallenge(verifier)
            val state = PkceCrypto.generateState()

            val origin = window.location.origin
            val pathname = window.location.pathname
            val redirectUri = "$origin$pathname"

            sessionStorage.setItem("pkce_code_verifier", verifier)
            sessionStorage.setItem("pkce_state", state)
            sessionStorage.setItem("pkce_redirect_uri", redirectUri)

            val authUrl = "${SupabaseConfig.SUPABASE_URL}/auth/v1/authorize?" +
                    "provider=google" +
                    "&redirect_to=${redirectUri}" +
                    "&code_challenge=${challenge}" +
                    "&code_challenge_method=S256" +
                    "&state=${state}" +
                    "&prompt=select_account"

            window.location.href = authUrl
            Result.failure(Exception("Redirecting to Google Sign-In..."))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    actual suspend fun handleAuthCallback(callbackUri: String, context: Any?): Result<UserProfile> {
        return try {
            val data = AuthCallbackParser.parse(callbackUri)
            if (data.isError) {
                return Result.failure(Exception("Authentication error: ${data.errorDescription ?: data.error}"))
            }

            val code = data.code
            if (!code.isNullOrBlank()) {
                val verifier = sessionStorage.getItem("pkce_code_verifier")
                    ?: return Result.failure(Exception("No pending PKCE code verifier in web session"))

                val expectedState = sessionStorage.getItem("pkce_state")
                if (expectedState != null && data.state != null && data.state != expectedState) {
                    return Result.failure(IllegalStateException("CSRF state mismatch"))
                }

                val redirectUri = sessionStorage.getItem("pkce_redirect_uri")
                    ?: "${window.location.origin}${window.location.pathname}"

                sessionStorage.removeItem("pkce_code_verifier")
                sessionStorage.removeItem("pkce_state")
                sessionStorage.removeItem("pkce_redirect_uri")

                val result = SupabaseClient.exchangeCodeForSession(code, verifier, redirectUri)
                result.onSuccess { profile ->
                    _authState.value = AuthState.Authenticated(profile)
                }
                return result
            }

            val accessToken = data.accessToken
            if (!accessToken.isNullOrBlank()) {
                val result = SupabaseClient.signInWithAccessToken(accessToken)
                result.onSuccess { profile ->
                    _authState.value = AuthState.Authenticated(profile)
                }
                return result
            }

            Result.failure(Exception("No authorization code or access token in callback URL"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    actual fun signInAsDefaultUser(context: Any?): Result<UserProfile> {
        val profile = SupabaseConfig.DEFAULT_USER
        SupabaseClient.setSession(SupabaseConfig.ANON_KEY, profile)
        _authState.value = AuthState.Authenticated(profile)
        return Result.success(profile)
    }

    actual fun signInAsGuest(context: Any?): Result<UserProfile> {
        val guest = UserProfile(
            id = "guest_wasm",
            email = "guest@streamify.app",
            displayName = "Guest User (Web)",
            avatarUrl = "",
            isAdmin = false,
        )
        SupabaseClient.setSession(null, guest)
        _authState.value = AuthState.Authenticated(guest)
        return Result.success(guest)
    }

    actual fun signOut(context: Any?) {
        _authState.value = AuthState.Unauthenticated
        SupabaseClient.clearSession()
    }
}
