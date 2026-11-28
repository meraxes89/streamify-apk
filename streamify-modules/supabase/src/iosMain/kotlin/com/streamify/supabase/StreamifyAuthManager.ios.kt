package com.streamify.supabase

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import platform.Foundation.NSURL
import platform.Foundation.NSUserDefaults
import platform.UIKit.UIApplication

actual object StreamifyAuthManager {
    private const val KEY_PENDING_VERIFIER = "streamify_pkce_verifier"
    private const val KEY_PENDING_STATE = "streamify_pkce_state"
    private const val KEY_PENDING_TIMESTAMP = "streamify_pkce_timestamp"

    private val _authState = MutableStateFlow<AuthState>(AuthState.Unauthenticated)
    actual val authState: StateFlow<AuthState> = _authState.asStateFlow()

    private val flowCoordinator = OAuthFlowCoordinator()
    actual val isAuthenticating: StateFlow<Boolean> = flowCoordinator.isAuthenticating
    actual val authError: StateFlow<String?> = flowCoordinator.authError

    actual fun submitAuthCallback(callbackUri: String, context: Any?) {
        flowCoordinator.submitCallback { handleAuthCallback(callbackUri, context) }
    }

    actual fun clearAuthError() = flowCoordinator.clearError()

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
            val session = OAuthPkceSession.createSession()
            val defaults = NSUserDefaults.standardUserDefaults
            defaults.setObject(session.verifier, forKey = KEY_PENDING_VERIFIER)
            defaults.setObject(session.state, forKey = KEY_PENDING_STATE)
            defaults.setDouble(session.timestampMs.toDouble(), forKey = KEY_PENDING_TIMESTAMP)

            val authUrl = OAuthPkceSession.buildAuthorizeUrl("streamify://callback", session)
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
                val defaults = NSUserDefaults.standardUserDefaults
                val storedVerifier = defaults.stringForKey(KEY_PENDING_VERIFIER)
                val storedState = defaults.stringForKey(KEY_PENDING_STATE)
                val storedTimestamp = defaults.doubleForKey(KEY_PENDING_TIMESTAMP).toLong()

                val validation = OAuthPkceSession.validateCallback(
                    callbackData = data,
                    storedState = storedState,
                    storedVerifier = storedVerifier,
                    storedTimestampMs = storedTimestamp
                )

                if (validation.isFailure) {
                    defaults.removeObjectForKey(KEY_PENDING_VERIFIER)
                    defaults.removeObjectForKey(KEY_PENDING_STATE)
                    defaults.removeObjectForKey(KEY_PENDING_TIMESTAMP)
                    return Result.failure(validation.exceptionOrNull() ?: SecurityException("Validation failed"))
                }

                val verifiedVerifier = validation.getOrThrow()
                defaults.removeObjectForKey(KEY_PENDING_VERIFIER)
                defaults.removeObjectForKey(KEY_PENDING_STATE)
                defaults.removeObjectForKey(KEY_PENDING_TIMESTAMP)

                val result = SupabaseClient.exchangeCodeForSession(code, verifiedVerifier, "streamify://callback")
                result.onSuccess { profile ->
                    _authState.value = AuthState.Authenticated(profile)
                }
                return result
            }

            if (!data.accessToken.isNullOrBlank()) {
                return Result.failure(SecurityException("Implicit access_token flow is disabled; PKCE code required"))
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
        val tokenToRevoke = SupabaseClient.accessToken.value
        _authState.value = AuthState.Unauthenticated
        SupabaseClient.clearSession()
        CoroutineScope(Dispatchers.Default).launch {
            SupabaseClient.signOutRemote(tokenToRevoke)
        }
    }
}
