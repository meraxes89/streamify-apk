package com.streamify.supabase

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Universal Supabase Client facade.
 * Manages authenticated user sessions, PostgREST requests, and verified server claims.
 */
object SupabaseClient {

    private val _currentUser = MutableStateFlow<UserProfile?>(null)
    val currentUser: StateFlow<UserProfile?> = _currentUser.asStateFlow()

    private val _accessToken = MutableStateFlow<String?>(null)
    val accessToken: StateFlow<String?> = _accessToken.asStateFlow()

    /**
     * SEC-09 invariant: Admin is a server-verified claim ONLY.
     * Derived strictly from server-verified `UserProfile.isAdmin`, never client-side heuristic.
     */
    val isAdmin: Boolean
        get() = _currentUser.value?.isAdmin == true

    /**
     * Updates user session after server authentication.
     */
    fun setSession(token: String?, profile: UserProfile?) {
        _accessToken.value = token
        _currentUser.value = profile
    }

    fun clearSession() {
        _accessToken.value = null
        _currentUser.value = null
    }

    fun getAuthHeaders(): Map<String, String> {
        val token = _accessToken.value
        return if (!token.isNullOrBlank()) {
            mapOf("Authorization" to "Bearer $token")
        } else {
            emptyMap()
        }
    }
}
