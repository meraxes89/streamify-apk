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

    actual fun signOut(context: Any?) {
        _authState.value = AuthState.Unauthenticated
        SupabaseClient.clearSession()
    }
}
