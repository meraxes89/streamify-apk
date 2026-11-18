package com.streamify.supabase

import android.content.Context
import android.content.SharedPreferences
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

actual object StreamifyAuthManager {
    private val _authState = MutableStateFlow<AuthState>(AuthState.Loading)
    actual val authState: StateFlow<AuthState> = _authState.asStateFlow()

    private var prefs: SharedPreferences? = null

    actual fun init(context: Any?) {
        val ctx = context as? Context ?: return
        if (prefs == null) {
            prefs = ctx.getSharedPreferences("streamify_auth", Context.MODE_PRIVATE)
        }
        val savedUserId = prefs?.getString("user_id", null)
        val savedEmail = prefs?.getString("user_email", null)
        val savedName = prefs?.getString("display_name", null)
        val savedAvatar = prefs?.getString("avatar_url", null)
        val savedIsAdmin = prefs?.getBoolean("is_admin", false) ?: false
        val savedToken = prefs?.getString("access_token", null)

        if (!savedUserId.isNullOrBlank() && !savedEmail.isNullOrBlank()) {
            val user = UserProfile(
                id = savedUserId,
                email = savedEmail,
                displayName = savedName ?: savedEmail.substringBefore("@"),
                avatarUrl = savedAvatar ?: "",
                isAdmin = savedIsAdmin,
            )
            SupabaseClient.setSession(savedToken, user)
            _authState.value = AuthState.Authenticated(user)
        } else {
            _authState.value = AuthState.Unauthenticated
        }
    }

    actual suspend fun signInWithGoogle(context: Any?): Result<UserProfile> = withContext(Dispatchers.Main) {
        val ctx = context as? Context ?: return@withContext Result.failure(Exception("Context required for Google Sign-In"))
        try {
            if (prefs == null) {
                prefs = ctx.getSharedPreferences("streamify_auth", Context.MODE_PRIVATE)
            }
            val credentialManager = CredentialManager.create(ctx)

            val googleIdOption = GetGoogleIdOption.Builder()
                .setFilterByAuthorizedAccounts(false)
                .setServerClientId(SupabaseConfig.GOOGLE_WEB_CLIENT_ID)
                .setAutoSelectEnabled(false)
                .build()

            val request = GetCredentialRequest.Builder()
                .addCredentialOption(googleIdOption)
                .build()

            val result = credentialManager.getCredential(context = ctx, request = request)
            val credential = result.credential

            val googleIdToken = GoogleIdTokenCredential.createFrom(credential.data).idToken
            if (googleIdToken.isBlank()) {
                return@withContext Result.failure(Exception("Google ID Token was empty."))
            }

            // Authenticate with Supabase
            val authResult = SupabaseClient.signInWithGoogleIdToken(googleIdToken)
            if (authResult.isSuccess) {
                val profile = authResult.getOrThrow()
                prefs?.edit()?.apply {
                    putBoolean("has_completed_onboarding", true)
                    putString("user_id", profile.id)
                    putString("user_email", profile.email)
                    putString("display_name", profile.displayName)
                    putString("avatar_url", profile.avatarUrl)
                    putBoolean("is_admin", profile.isAdmin)
                    putString("access_token", SupabaseClient.getAuthToken())
                    apply()
                }
                _authState.value = AuthState.Authenticated(profile)
            }
            authResult
        } catch (e: GetCredentialCancellationException) {
            Result.failure(Exception("Sign-in cancelled."))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    actual fun signOut(context: Any?) {
        val ctx = context as? Context
        if (prefs == null && ctx != null) {
            prefs = ctx.getSharedPreferences("streamify_auth", Context.MODE_PRIVATE)
        }
        prefs?.edit()?.clear()?.apply()
        SupabaseClient.clearSession()
        _authState.value = AuthState.Unauthenticated
    }
}
