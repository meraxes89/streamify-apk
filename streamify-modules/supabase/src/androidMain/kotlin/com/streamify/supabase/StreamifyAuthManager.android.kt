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
            }
            authResult
        } catch (e: GetCredentialCancellationException) {
            Result.failure(Exception("Sign-in cancelled."))
        } catch (e: GetCredentialException) {
            // Credential Manager error (unregistered package on Vivo, missing Play Services, etc.)
            tryLaunchWebOAuth(ctx)
            Result.failure(Exception("Google Sign-In unavailable on device. Opening web sign-in..."))
        } catch (e: Exception) {
            tryLaunchWebOAuth(ctx)
            Result.failure(e)
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
                val storedVerifier = AndroidTokenCipher.decrypt(sp?.getString("pending_code_verifier", null))
                val storedState = AndroidTokenCipher.decrypt(sp?.getString("pending_state", null))

                // Clean up ephemeral PKCE state immediately on callback receipt
                sp?.edit()
                    ?.remove("pending_code_verifier")
                    ?.remove("pending_state")
                    ?.apply()

                if (storedState.isNullOrBlank() || callbackData.state != storedState) {
                    return@withContext Result.failure(SecurityException("Missing or mismatched CSRF state"))
                }

                if (storedVerifier.isNullOrBlank()) {
                    return@withContext Result.failure(Exception("No pending PKCE code verifier found"))
                }

                val exchangeResult = SupabaseClient.exchangeCodeForSession(
                    code = code,
                    codeVerifier = storedVerifier,
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

    fun tryLaunchWebOAuth(context: Context) {
        try {
            val sp = getPrefs(context)
            val verifier = PkceCrypto.generateCodeVerifier()
            val challenge = PkceCrypto.deriveCodeChallenge(verifier)
            val state = PkceCrypto.generateState()

            // Persist encrypted PKCE challenge and state across potential activity/process death
            sp.edit().apply {
                AndroidTokenCipher.encrypt(verifier)?.let { putString("pending_code_verifier", it) }
                AndroidTokenCipher.encrypt(state)?.let { putString("pending_state", it) }
                apply()
            }

            val authUrl = "${SupabaseConfig.SUPABASE_URL}/auth/v1/authorize?" +
                    "provider=google" +
                    "&redirect_to=streamify://callback" +
                    "&code_challenge=$challenge" +
                    "&code_challenge_method=S256" +
                    "&state=$state" +
                    "&prompt=select_account"

            val uri = Uri.parse(authUrl)

            // Secure web launch via CustomTabsIntent (avoids implicit intent leaks and shares session cookies)
            val customTabsIntent = CustomTabsIntent.Builder()
                .setShowTitle(true)
                .build()
            customTabsIntent.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            customTabsIntent.launchUrl(context, uri)
        } catch (_: Exception) {
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
