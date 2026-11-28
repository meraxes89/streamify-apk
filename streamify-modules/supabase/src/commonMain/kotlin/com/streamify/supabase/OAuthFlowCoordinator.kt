package com.streamify.supabase

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Shared state coordinator for interactive OAuth flows across KMP platforms.
 *
 * Runs callback token exchange on an application-lifetime [CoroutineScope] protected by a
 * single-flight [Mutex] so clearing a deep-link intent in UI state never cancels an in-flight
 * PKCE exchange, and failed/cancelled callbacks always reset [isAuthenticating] and surface
 * [authError].
 */
internal class OAuthFlowCoordinator(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private val callbackMutex = Mutex()

    private val _isAuthenticating = MutableStateFlow(false)
    val isAuthenticating: StateFlow<Boolean> = _isAuthenticating.asStateFlow()

    private val _authError = MutableStateFlow<String?>(null)
    val authError: StateFlow<String?> = _authError.asStateFlow()

    fun onSignInStarted() {
        _isAuthenticating.value = true
        _authError.value = null
    }

    fun onBrowserFallbackLaunched() {
        _isAuthenticating.value = true
        _authError.value = null
    }

    fun onSignInCancelled() {
        _isAuthenticating.value = false
        _authError.value = null
    }

    fun onSignInSucceeded() {
        _isAuthenticating.value = false
        _authError.value = null
    }

    fun onSignInFailed(message: String?) {
        _isAuthenticating.value = false
        _authError.value = message?.takeIf { it.isNotBlank() } ?: "Sign-in failed"
    }

    fun clearError() {
        _authError.value = null
    }

    fun submitCallback(block: suspend () -> Result<UserProfile>): Job {
        _isAuthenticating.value = true
        _authError.value = null
        return scope.launch {
            callbackMutex.withLock {
                val result = runCatching { block() }.getOrElse { Result.failure(it) }
                if (result.isSuccess) {
                    onSignInSucceeded()
                } else {
                    onSignInFailed(result.exceptionOrNull()?.message)
                }
            }
        }
    }
}
