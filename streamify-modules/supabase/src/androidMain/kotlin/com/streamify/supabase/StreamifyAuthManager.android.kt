package com.streamify.supabase

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

actual object StreamifyAuthManager {
    private val _authState = MutableStateFlow<AuthState>(AuthState.Loading)
    actual val authState: StateFlow<AuthState> = _authState.asStateFlow()

    private val flowCoordinator = OAuthFlowCoordinator()
    actual val isAuthenticating: StateFlow<Boolean> = flowCoordinator.isAuthenticating
    actual val authError: StateFlow<String?> = flowCoordinator.authError

    actual fun submitAuthCallback(callbackUri: String, context: Any?) {
        flowCoordinator.submitCallback { handleAuthCallback(callbackUri, context) }
    }

    actual fun clearAuthError() = flowCoordinator.clearError()

    private fun getPrefs(context: Context): SharedPreferences = PlatformTokenStore.getPrefs(context)

    private fun findActivity(context: Context?): Activity? {
        var current = context
        while (current is ContextWrapper) {
            if (current is Activity) return current
            current = current.baseContext
        }
        return null
    }

    actual fun init(context: Any?) {
        val ctx = context as? Context ?: return
        PlatformTokenStore.init(ctx)
        val restored = SupabaseClient.restorePersistedSession()
        if (restored != null) {
            _authState.value = AuthState.Authenticated(restored.user)
            val refreshToken = restored.refreshToken
            if (!refreshToken.isNullOrBlank()) {
                CoroutineScope(Dispatchers.IO).launch {
                    SupabaseClient.refreshSession(refreshToken)
                }
            }
        } else {
            _authState.value = AuthState.Unauthenticated
        }
    }

    actual suspend fun signInWithGoogle(context: Any?): Result<UserProfile> = withContext(Dispatchers.Main) {
        val ctx = context as? Context ?: return@withContext Result.failure(Exception("Context required for Google Sign-In"))
        flowCoordinator.onSignInStarted()
        try {
            PlatformTokenStore.init(ctx)
            val hostActivity = findActivity(ctx) ?: ctx
            val credentialManager = CredentialManager.create(hostActivity)

            val googleIdOption = GetGoogleIdOption.Builder()
                .setFilterByAuthorizedAccounts(false)
                .setServerClientId(SupabaseConfig.GOOGLE_WEB_CLIENT_ID)
                .setAutoSelectEnabled(false)
                .build()

            val request = GetCredentialRequest.Builder()
                .addCredentialOption(googleIdOption)
                .build()

            val result = credentialManager.getCredential(context = hostActivity, request = request)
            val googleCredential = GoogleIdTokenCredential.createFrom(result.credential.data)
            val googleIdToken = googleCredential.idToken

            if (googleIdToken.isBlank()) {
                return@withContext Result.failure(Exception("Google ID Token was empty"))
            }

            val authResult = SupabaseClient.signInWithGoogleIdToken(googleIdToken)
            authResult.onSuccess { profile ->
                _authState.value = AuthState.Authenticated(profile)
                flowCoordinator.onSignInSucceeded()
            }.onFailure { err ->
                flowCoordinator.onSignInFailed(err.message)
            }
            authResult
        } catch (e: GetCredentialCancellationException) {
            flowCoordinator.onSignInCancelled()
            Result.failure(Exception("Sign-in cancelled."))
        } catch (e: GetCredentialException) {
            // Credential Manager error (unregistered package/SHA-1, Vivo com.spotify.music, missing Play Services, etc.)
            android.util.Log.w("StreamifyAuth", "CredentialManager failed (${e.type}): ${e.errorMessage}", e)
            if (tryLaunchWebOAuth(ctx)) {
                flowCoordinator.onBrowserFallbackLaunched()
                Result.failure(Exception("Google Sign-In unavailable on device. Opening web sign-in..."))
            } else {
                val msg = "Google Sign-In unavailable (${e.type}) and no browser available for web sign-in."
                flowCoordinator.onSignInFailed(msg)
                Result.failure(Exception(msg))
            }
        } catch (e: Exception) {
            android.util.Log.w("StreamifyAuth", "Google Sign-In failed: ${e.message}", e)
            if (tryLaunchWebOAuth(ctx)) {
                flowCoordinator.onBrowserFallbackLaunched()
                Result.failure(Exception("Opening web sign-in..."))
            } else {
                flowCoordinator.onSignInFailed(e.message)
                Result.failure(e)
            }
        }
    }

    actual suspend fun handleAuthCallback(callbackUri: String, context: Any?): Result<UserProfile> = withContext(Dispatchers.Main) {
        val ctx = context as? Context
        val sp = ctx?.let { getPrefs(it) }
        try {
            val callbackData = AuthCallbackParser.parse(callbackUri)
            if (callbackData.isError) {
                return@withContext Result.failure(
                    Exception("Authentication error: ${callbackData.errorDescription ?: callbackData.error}")
                )
            }

            // PKCE Authorization Code Grant flow
            val code = callbackData.code
            if (!code.isNullOrBlank()) {
                val storedVerifierRaw = sp?.getString("pending_code_verifier", null)
                val storedVerifier = storedVerifierRaw?.let { raw ->
                    if (raw.startsWith("enc_v1:")) AndroidTokenCipher.decrypt(raw) else raw
                }
                val storedStateRaw = sp?.getString("pending_state", null)
                val storedState = storedStateRaw?.let { raw ->
                    if (raw.startsWith("enc_v1:")) AndroidTokenCipher.decrypt(raw) else raw
                }
                val storedTimestamp = sp?.getLong("pending_state_timestamp", 0L) ?: 0L

                val validation = OAuthPkceSession.validateCallback(
                    callbackData = callbackData,
                    storedState = storedState,
                    storedVerifier = storedVerifier,
                    storedTimestampMs = storedTimestamp
                )

                if (validation.isFailure) {
                    sp?.edit()
                        ?.remove("pending_code_verifier")
                        ?.remove("pending_state")
                        ?.remove("pending_state_timestamp")
                        ?.apply()
                    return@withContext Result.failure(
                        validation.exceptionOrNull() ?: SecurityException("Validation failed")
                    )
                }

                val verifiedVerifier = validation.getOrThrow()

                // Clean up ephemeral PKCE state upon validation
                sp?.edit()
                    ?.remove("pending_code_verifier")
                    ?.remove("pending_state")
                    ?.remove("pending_state_timestamp")
                    ?.apply()

                val exchangeResult = SupabaseClient.exchangeCodeForSession(
                    code = code,
                    codeVerifier = verifiedVerifier,
                    redirectUri = "streamify://callback"
                )

                exchangeResult.onSuccess { profile ->
                    _authState.value = AuthState.Authenticated(profile)
                }
                return@withContext exchangeResult
            }

            if (!callbackData.accessToken.isNullOrBlank()) {
                return@withContext Result.failure(SecurityException("Implicit access_token flow is disabled; PKCE code required"))
            }

            Result.failure(Exception("No authorization code or access token found in callback URL"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun tryLaunchWebOAuth(context: Context): Boolean {
        return try {
            val sp = getPrefs(context)
            val session = OAuthPkceSession.createSession()

            // Persist ephemeral PKCE session in sandbox private SharedPreferences across activity/process death
            sp.edit().apply {
                putString("pending_code_verifier", session.verifier)
                putString("pending_state", session.state)
                putLong("pending_state_timestamp", session.timestampMs)
                apply()
            }

            val authUrl = OAuthPkceSession.buildAuthorizeUrl("streamify://callback", session)
            val uri = Uri.parse(authUrl)

            // Secure web launch via CustomTabsIntent (avoids implicit intent leaks and shares session cookies)
            val customTabsIntent = CustomTabsIntent.Builder()
                .setShowTitle(true)
                .build()
            customTabsIntent.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            customTabsIntent.launchUrl(context, uri)
            true
        } catch (e: Exception) {
            android.util.Log.e("StreamifyAuth", "Failed to launch web OAuth", e)
            false
        }
    }

    actual fun signInAsDefaultUser(context: Any?): Result<UserProfile> {
        val ctx = context as? Context
        if (ctx != null) {
            PlatformTokenStore.init(ctx)
        }
        val profile = SupabaseConfig.DEFAULT_USER
        SupabaseClient.setSession(SupabaseConfig.ANON_KEY, profile)
        _authState.value = AuthState.Authenticated(profile)
        return Result.success(profile)
    }

    actual fun signInAsGuest(context: Any?): Result<UserProfile> {
        val ctx = context as? Context
        if (ctx != null) {
            PlatformTokenStore.init(ctx)
        }
        val profile = UserProfile(
            id = "guest_" + System.currentTimeMillis(),
            email = "guest@streamify.app",
            displayName = "Guest User",
            avatarUrl = "",
            isAdmin = false,
        )
        PlatformTokenStore.clear()
        SupabaseClient.setSession(null, profile)
        _authState.value = AuthState.Authenticated(profile)
        return Result.success(profile)
    }

    actual fun signOut(context: Any?) {
        val ctx = context as? Context
        if (ctx != null) {
            PlatformTokenStore.init(ctx)
        }
        val tokenToRevoke = SupabaseClient.accessToken.value
        SupabaseClient.clearSession()
        _authState.value = AuthState.Unauthenticated
        CoroutineScope(Dispatchers.IO).launch {
            SupabaseClient.signOutRemote(tokenToRevoke)
        }
    }
}
