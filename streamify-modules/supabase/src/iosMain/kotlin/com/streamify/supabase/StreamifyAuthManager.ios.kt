package com.streamify.supabase

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import platform.Foundation.NSURL
import platform.UIKit.UIApplication

actual object StreamifyAuthManager {
    private val _authState = MutableStateFlow<AuthState>(AuthState.Unauthenticated)
    actual val authState: StateFlow<AuthState> = _authState.asStateFlow()

    private var pendingCodeVerifier: String? = null
    private var pendingState: String? = null

    actual fun init(context: Any?) {
        PlatformTokenStore.init(context)
        val restored = SupabaseClient.restorePersistedSession()
        if (restored != null) {
            _authState.value = AuthState.Authenticated(restored.user)
            val refreshToken = restored.refreshToken
            if (!refreshToken.isNullOrBlank()) {
                CoroutineScope(Dispatchers.Default).launch {
                    SupabaseClient.refreshSession(refreshToken)
                }
            }
        } else if (SupabaseClient.hasValidUserSession()) {
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

            pendingCodeVerifier = verifier
            pendingState = state

            val authUrl = "${SupabaseConfig.SUPABASE_URL}/auth/v1/authorize?" +
                    "provider=google" +
                    "&redirect_to=streamify://callback" +
                    "&code_challenge=${challenge}" +
                    "&code_challenge_method=S256" +
                    "&state=${state}" +
                    "&prompt=select_account"

            val nsUrl = NSURL.URLWithString(authUrl)
                ?: return Result.failure(IllegalStateException("Invalid OAuth URL"))
            UIApplication.sharedApplication.openURL(nsUrl)
            Result.failure(Exception("Continuing sign-in in browser..."))
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
                val verifier = pendingCodeVerifier
                    ?: return Result.failure(Exception("No pending PKCE code verifier in session"))

                val expectedState = pendingState
                if (expectedState != null && data.state != null && data.state != expectedState) {
                    return Result.failure(SecurityException("CSRF state mismatch"))
                }

                pendingCodeVerifier = null
                pendingState = null

                val result = SupabaseClient.exchangeCodeForSession(code, verifier, "streamify://callback")
                result.onSuccess { profile ->
                    _authState.value = AuthState.Authenticated(profile)
                }
                return result
            }

            val accessToken = data.accessToken
            if (!accessToken.isNullOrBlank()) {
                val result = SupabaseClient.signInWithAccessToken(accessToken, data.refreshToken)
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
            id = "guest_ios",
            email = "guest@streamify.app",
            displayName = "Guest User (iOS)",
            avatarUrl = "",
            isAdmin = false,
        )
        PlatformTokenStore.clear()
        SupabaseClient.setSession(null, guest)
        _authState.value = AuthState.Authenticated(guest)
        return Result.success(guest)
    }

    actual fun signOut(context: Any?) {
        _authState.value = AuthState.Unauthenticated
        SupabaseClient.clearSession()
    }
}
