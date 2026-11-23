package com.streamify.supabase

import kotlinx.coroutines.flow.StateFlow

sealed interface AuthState {
    object Loading : AuthState
    data class Authenticated(val user: UserProfile) : AuthState
    object Unauthenticated : AuthState
}

expect object StreamifyAuthManager {
    val authState: StateFlow<AuthState>
    fun init(context: Any? = null)
    suspend fun signInWithGoogle(context: Any? = null): Result<UserProfile>
    suspend fun handleAuthCallback(callbackUri: String, context: Any? = null): Result<UserProfile>
    fun signInAsDefaultUser(context: Any? = null): Result<UserProfile>
    fun signInAsGuest(context: Any? = null): Result<UserProfile>
    fun signOut(context: Any? = null)
}
