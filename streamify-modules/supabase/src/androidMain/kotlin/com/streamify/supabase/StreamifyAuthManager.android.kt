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
import androidx.credentials.exceptions.NoCredentialException
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

    private var prefs: SharedPreferences? = null

    private fun getPrefs(context: Context): SharedPreferences {
        return prefs ?: context.getSharedPreferences("streamify_auth", Context.MODE_PRIVATE).also {
            prefs = it
        }
    }

    private fun findActivity(context: Context?): Activity? {
        var current = context
        while (current is ContextWrapper) {
            if (current is Activity) return current
            current = current.baseContext
        }
        return null
    }

    private fun saveProfile(profile: UserProfile, token: String? = null, refreshToken: String? = null) {
        prefs?.edit()?.apply {
            putBoolean("has_completed_onboarding", true)
            putString("user_id", profile.id)
            putString("user_email", profile.email)
            putString("display_name", profile.displayName)
            putString("avatar_url", profile.avatarUrl)
            putBoolean("is_admin", profile.isAdmin)
            if (token != null) {
                putString("access_token", token)
            }
            if (refreshToken != null) {
                putString("refresh_token", refreshToken)
            }
            apply()
        }
    }

    actual fun init(context: Any?) {
        val ctx = context as? Context ?: return
        val sp = getPrefs(ctx)
        val savedUserId = sp.getString("user_id", null)
        val savedEmail = sp.getString("user_email", null)
        val savedName = sp.getString("display_name", null)
        val savedAvatar = sp.getString("avatar_url", null)
        val savedIsAdmin = sp.getBoolean("is_admin", false)
        val savedToken = sp.getString("access_token", null)
        val savedRefreshToken = sp.getString("refresh_token", null)

        if (!savedUserId.isNullOrBlank() && !savedEmail.isNullOrBlank()) {
            val user = UserProfile(
                id = savedUserId,
                email = savedEmail,
                displayName = savedName ?: savedEmail.substringBefore("@"),
                avatarUrl = savedAvatar ?: "",
                isAdmin = savedIsAdmin,
            )
            SupabaseClient.setSession(savedToken, user, savedRefreshToken)
            _authState.value = AuthState.Authenticated(user)

            if (!savedRefreshToken.isNullOrBlank()) {
                CoroutineScope(Dispatchers.IO).launch {
                    val refreshed = SupabaseClient.refreshSession(savedRefreshToken)
                    refreshed.onSuccess { newToken ->
                        saveProfile(user, newToken, SupabaseClient.refreshToken.value)
                    }
                }
            }
        } else {
            _authState.value = AuthState.Unauthenticated
        }
    }

    actual suspend fun signInWithGoogle(context: Any?): Result<UserProfile> = withContext(Dispatchers.Main) {
        val ctx = context as? Context ?: return@withContext Result.failure(Exception("Context required for Google Sign-In"))
        try {
            getPrefs(ctx)
            val hostActivity = findActivity(ctx) ?: ctx
            val credentialManager = CredentialManager.create(hostActivity)

            // Exact legacy One-Tap request structure from legacy/app AuthManager.kt
            val googleIdOption = GetGoogleIdOption.Builder()
                .setFilterByAuthorizedAccounts(false)
                .setServerClientId(SupabaseConfig.GOOGLE_WEB_CLIENT_ID)
                .setAutoSelectEnabled(false)
                .build()

            val request = GetCredentialRequest.Builder()
                .addCredentialOption(googleIdOption)
                .build()

            val result = credentialManager.getCredential(context = hostActivity, request = request)
            val credential = result.credential

            val googleCredential = GoogleIdTokenCredential.createFrom(credential.data)
            val googleIdToken = googleCredential.idToken
            val email = googleCredential.id
            val name = googleCredential.displayName ?: email.substringBefore("@")
            val avatar = googleCredential.profilePictureUri?.toString().orEmpty()

            // Authenticate with Supabase backend
            val authResult = if (googleIdToken.isNotBlank()) {
                SupabaseClient.signInWithGoogleIdToken(googleIdToken)
            } else {
                Result.failure(Exception("Google ID Token was empty"))
            }

            val profile = authResult.getOrElse {
                UserProfile(
                    id = email,
                    email = email,
                    displayName = name,
                    avatarUrl = avatar,
                    isAdmin = email.equals(SupabaseConfig.DEFAULT_USER.email, ignoreCase = true),
                )
            }

            saveProfile(profile, SupabaseClient.getAuthToken(), SupabaseClient.refreshToken.value)
            _authState.value = AuthState.Authenticated(profile)
            Result.success(profile)
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
        val sp = ctx?.let { getPrefs(it) } ?: prefs
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
                val storedVerifier = sp?.getString("pending_code_verifier", null)
                val storedState = sp?.getString("pending_state", null)

                if (storedState != null && callbackData.state != null && storedState != callbackData.state) {
                    return@withContext Result.failure(SecurityException("CSRF state mismatch"))
                }

                if (storedVerifier.isNullOrBlank()) {
                    return@withContext Result.failure(Exception("No pending PKCE code verifier found"))
                }

                val exchangeResult = SupabaseClient.exchangeCodeForSession(
                    code = code,
                    codeVerifier = storedVerifier,
                    redirectUri = "streamify://callback"
                )

                // Clean up ephemeral PKCE state
                sp?.edit()
                    ?.remove("pending_code_verifier")
                    ?.remove("pending_state")
                    ?.apply()

                if (exchangeResult.isSuccess) {
                    val profile = exchangeResult.getOrThrow()
                    saveProfile(profile, SupabaseClient.getAuthToken(), SupabaseClient.refreshToken.value)
                    _authState.value = AuthState.Authenticated(profile)
                    return@withContext Result.success(profile)
                } else {
                    return@withContext exchangeResult
                }
            }

            // Fallback for legacy access token in hash fragment
            val accessToken = callbackData.accessToken
            if (!accessToken.isNullOrBlank()) {
                val result = SupabaseClient.signInWithAccessToken(accessToken)
                if (result.isSuccess) {
                    val profile = result.getOrThrow()
                    saveProfile(profile, accessToken, callbackData.refreshToken)
                    _authState.value = AuthState.Authenticated(profile)
                    return@withContext Result.success(profile)
                } else {
                    return@withContext result
                }
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

            // Persist PKCE challenge and state in SharedPreferences across potential activity/process death
            sp.edit()
                .putString("pending_code_verifier", verifier)
                .putString("pending_state", state)
                .apply()

            val authUrl = "${SupabaseConfig.SUPABASE_URL}/auth/v1/authorize?" +
                    "provider=google" +
                    "&redirect_to=streamify://callback" +
                    "&code_challenge=$challenge" +
                    "&code_challenge_method=S256" +
                    "&state=$state" +
                    "&prompt=select_account"

            val uri = Uri.parse(authUrl)

            // Primary: Chrome Custom Tabs (shares active browser session cookies)
            try {
                val customTabsIntent = CustomTabsIntent.Builder()
                    .setShowTitle(true)
                    .build()
                customTabsIntent.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                customTabsIntent.launchUrl(context, uri)
                return
            } catch (_: Exception) {
                // Custom Tabs unavailable on device, fall back to standard external browser intent
            }

            val fallbackIntent = Intent(Intent.ACTION_VIEW, uri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(fallbackIntent)
        } catch (_: Exception) {
        }
    }

    actual fun signInAsDefaultUser(context: Any?): Result<UserProfile> {
        val ctx = context as? Context
        if (ctx != null) {
            getPrefs(ctx)
        }
        val profile = SupabaseConfig.DEFAULT_USER
        saveProfile(profile, SupabaseConfig.ANON_KEY)
        SupabaseClient.setSession(SupabaseConfig.ANON_KEY, profile)
        _authState.value = AuthState.Authenticated(profile)
        return Result.success(profile)
    }

    actual fun signInAsGuest(context: Any?): Result<UserProfile> {
        val ctx = context as? Context
        if (ctx != null) {
            getPrefs(ctx)
        }
        val profile = UserProfile(
            id = "guest_" + System.currentTimeMillis(),
            email = "guest@streamify.app",
            displayName = "Guest User",
            avatarUrl = "",
            isAdmin = false,
        )
        saveProfile(profile, null)
        SupabaseClient.setSession(null, profile)
        _authState.value = AuthState.Authenticated(profile)
        return Result.success(profile)
    }

    actual fun signOut(context: Any?) {
        val ctx = context as? Context
        if (ctx != null) {
            getPrefs(ctx)
        }
        prefs?.edit()?.clear()?.apply()
        SupabaseClient.clearSession()
        _authState.value = AuthState.Unauthenticated
    }
}
