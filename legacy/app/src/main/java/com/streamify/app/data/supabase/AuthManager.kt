package com.streamify.app.data.supabase
import android.content.Context
import android.content.SharedPreferences
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.streamify.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import com.streamify.app.util.SLog

sealed interface AuthState {
    object Loading : AuthState
    data class Authenticated(val user: UserProfile) : AuthState
    object Unauthenticated : AuthState
}

object AuthManager {

    private val _authState = MutableStateFlow<AuthState>(AuthState.Loading)
    val authState: StateFlow<AuthState> = _authState.asStateFlow()

    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        if (prefs == null) {
            prefs = context.getSharedPreferences("streamify_auth", Context.MODE_PRIVATE)
        }
        SupabaseClient.init(context)
        val user = SupabaseClient.currentUser.value

        if (user != null && user.id.isNotBlank()) {
            _authState.value = AuthState.Authenticated(user)
        } else {
            _authState.value = AuthState.Unauthenticated
        }
    }

    suspend fun signInWithGoogle(context: Context): Result<UserProfile> = withContext(Dispatchers.Main) {
        try {
            if (prefs == null) {
                prefs = context.getSharedPreferences("streamify_auth", Context.MODE_PRIVATE)
            }
            val credentialManager = CredentialManager.create(context)

            val googleIdOption = GetGoogleIdOption.Builder()
                .setFilterByAuthorizedAccounts(false)
                .setServerClientId(BuildConfig.GOOGLE_WEB_CLIENT_ID)
                .setAutoSelectEnabled(false)
                .build()

            val request = GetCredentialRequest.Builder()
                .addCredentialOption(googleIdOption)
                .build()

            val result = credentialManager.getCredential(context = context, request = request)
            val credential = result.credential

            val googleIdToken = GoogleIdTokenCredential.createFrom(credential.data).idToken
            if (googleIdToken.isBlank()) {
                return@withContext Result.failure(Exception("Google ID Token was empty."))
            }

            // Authenticate with Supabase
            val authResult = SupabaseClient.signInWithGoogleIdToken(googleIdToken)
            if (authResult.isSuccess) {
                val profile = authResult.getOrThrow()
                prefs?.edit()?.putBoolean("has_completed_onboarding", true)?.apply()
                _authState.value = AuthState.Authenticated(profile)

                // Hydrate new user's cloud library into clean local database
                kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
                    try {
                        SupabaseClient.syncCloudLikes(emptyList())
                        com.streamify.app.data.repository.TrackRepository.refresh()
                    } catch (e: Exception) {
                        SLog.st("AuthManager", "AuthManager.signInWithGoogle failed", e)
                    }
                }
            }
            authResult
        } catch (e: GetCredentialCancellationException) {
            return@withContext Result.failure(Exception("Sign-in cancelled."))
        } catch (e: Exception) {
            SLog.st("AuthManager", "AuthManager.signInWithGoogle failed", e)
            return@withContext Result.failure(e)
        }
    }

    fun signOut(context: Context) {
        if (prefs == null) {
            prefs = context.getSharedPreferences("streamify_auth", Context.MODE_PRIVATE)
        }
        prefs?.edit()?.clear()?.apply()
        SupabaseClient.signOut()
        _authState.value = AuthState.Unauthenticated

        // Clear local database and taste profile to prevent account contamination
        try {
            com.streamify.app.data.NativeBridge.nukeLocalDatabase()
            com.streamify.app.data.repository.TrackRepository.hardResetState()
            com.streamify.app.data.repository.PlaylistRepository.hardResetState()
            kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
                com.streamify.app.data.persistence.StorageManager.clearAllCache(context)
                com.streamify.app.data.repository.TrackRepository.refresh()
            }
        } catch (e: Exception) {
            SLog.st("AuthManager", "AuthManager.signOut failed", e)
        }
    }
}
