package com.streamify.supabase

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

actual object StreamifyAuthManager {
    private val _authState = MutableStateFlow<AuthState>(AuthState.Authenticated(SupabaseConfig.DEFAULT_USER))
    actual val authState: StateFlow<AuthState> = _authState.asStateFlow()

    actual fun init(context: Any?) {
        _authState.value = AuthState.Authenticated(SupabaseConfig.DEFAULT_USER)
    }

    actual suspend fun signInWithGoogle(context: Any?): Result<UserProfile> {
        _authState.value = AuthState.Authenticated(SupabaseConfig.DEFAULT_USER)
        return Result.success(SupabaseConfig.DEFAULT_USER)
    }

    actual suspend fun handleAuthCallback(callbackUri: String, context: Any?): Result<UserProfile> {
        _authState.value = AuthState.Authenticated(SupabaseConfig.DEFAULT_USER)
        return Result.success(SupabaseConfig.DEFAULT_USER)
    }

    actual fun signInAsDefaultUser(context: Any?): Result<UserProfile> {
        _authState.value = AuthState.Authenticated(SupabaseConfig.DEFAULT_USER)
        return Result.success(SupabaseConfig.DEFAULT_USER)
    }

    actual fun signInAsGuest(context: Any?): Result<UserProfile> {
        val guest = UserProfile(
            id = "guest_desktop",
            email = "guest@streamify.app",
            displayName = "Guest User",
            avatarUrl = "",
            isAdmin = false,
        )
        _authState.value = AuthState.Authenticated(guest)
        return Result.success(guest)
    }

    actual fun signOut(context: Any?) {
        _authState.value = AuthState.Unauthenticated
        SupabaseClient.clearSession()
    }
}
