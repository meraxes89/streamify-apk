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

    private object AndroidTokenCipher {
        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val KEY_ALIAS = "streamify_auth_token_key_v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val ENC_PREFIX = "enc_v1:"
        private const val GCM_TAG_BITS = 128
        private const val GCM_IV_BYTES = 12

        private fun getOrCreateKey(): java.security.Key? {
            return try {
                val keyStore = java.security.KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
                keyStore.getKey(KEY_ALIAS, null) ?: run {
                    val keyGen = javax.crypto.KeyGenerator.getInstance(
                        android.security.keystore.KeyProperties.KEY_ALGORITHM_AES,
                        KEYSTORE_PROVIDER
                    )
                    val spec = android.security.keystore.KeyGenParameterSpec.Builder(
                        KEY_ALIAS,
                        android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or
                            android.security.keystore.KeyProperties.PURPOSE_DECRYPT
                    )
                        .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .build()
                    keyGen.init(spec)
                    keyGen.generateKey()
                }
            } catch (_: Exception) {
                null
            }
        }

        fun encrypt(plaintext: String): String? {
            val key = getOrCreateKey() ?: return null
            return try {
                val cipher = javax.crypto.Cipher.getInstance(TRANSFORMATION)
                cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, key)
                val iv = cipher.iv
                val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
                val combined = ByteArray(iv.size + ciphertext.size)
                System.arraycopy(iv, 0, combined, 0, iv.size)
                System.arraycopy(ciphertext, 0, combined, iv.size, ciphertext.size)
                ENC_PREFIX + android.util.Base64.encodeToString(combined, android.util.Base64.NO_WRAP)
            } catch (_: Exception) {
                null
            }
        }

        fun decrypt(stored: String?): String? {
            if (stored.isNullOrBlank()) return null
            if (!stored.startsWith(ENC_PREFIX)) {
                return stored
            }
            val key = getOrCreateKey() ?: return null
            return try {
                val combined = android.util.Base64.decode(stored.removePrefix(ENC_PREFIX), android.util.Base64.NO_WRAP)
                if (combined.size <= GCM_IV_BYTES) return null
                val iv = combined.copyOfRange(0, GCM_IV_BYTES)
                val ciphertext = combined.copyOfRange(GCM_IV_BYTES, combined.size)
                val cipher = javax.crypto.Cipher.getInstance(TRANSFORMATION)
                cipher.init(
                    javax.crypto.Cipher.DECRYPT_MODE,
                    key,
                    javax.crypto.spec.GCMParameterSpec(GCM_TAG_BITS, iv)
                )
                String(cipher.doFinal(ciphertext), Charsets.UTF_8)
            } catch (_: Exception) {
                null
            }
        }
    }

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
                AndroidTokenCipher.encrypt(token)?.let { putString("access_token", it) }
            }
            if (refreshToken != null) {
                AndroidTokenCipher.encrypt(refreshToken)?.let { putString("refresh_token", it) }
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
        val savedToken = AndroidTokenCipher.decrypt(sp.getString("access_token", null))
        val savedRefreshToken = AndroidTokenCipher.decrypt(sp.getString("refresh_token", null))

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
                val storedVerifier = AndroidTokenCipher.decrypt(sp?.getString("pending_code_verifier", null))
                val storedState = AndroidTokenCipher.decrypt(sp?.getString("pending_state", null))

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
