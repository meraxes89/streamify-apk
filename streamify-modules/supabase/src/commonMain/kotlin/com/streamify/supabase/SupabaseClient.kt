package com.streamify.supabase

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json

/**
 * Universal Supabase Client facade.
 * Manages authenticated user sessions, PostgREST requests, and verified server claims.
 */
object SupabaseClient {

    private val _currentUser = MutableStateFlow<UserProfile?>(SupabaseConfig.DEFAULT_USER)
    val currentUser: StateFlow<UserProfile?> = _currentUser.asStateFlow()

    private val _accessToken = MutableStateFlow<String?>(null)
    val accessToken: StateFlow<String?> = _accessToken.asStateFlow()

    val httpClient: HttpClient by lazy {
        HttpClient(CIO) {
            install(ContentNegotiation) {
                json(Json {
                    ignoreUnknownKeys = true
                    encodeDefaults = true
                    isLenient = true
                    coerceInputValues = true
                })
            }
        }
    }

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
        _currentUser.value = profile ?: SupabaseConfig.DEFAULT_USER
    }

    fun clearSession() {
        _accessToken.value = null
        _currentUser.value = SupabaseConfig.DEFAULT_USER
    }

    fun getAuthToken(): String {
        return _accessToken.value ?: SupabaseConfig.ANON_KEY
    }

    fun getAuthHeaders(): Map<String, String> {
        val token = getAuthToken()
        return mapOf(
            "apikey" to SupabaseConfig.ANON_KEY,
            "Authorization" to "Bearer $token"
        )
    }
}
