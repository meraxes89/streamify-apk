package com.streamify.supabase

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.awt.Desktop
import java.net.URI

actual object StreamifyAuthManager {
    private val _authState = MutableStateFlow<AuthState>(AuthState.Unauthenticated)
    actual val authState: StateFlow<AuthState> = _authState.asStateFlow()

    private var pendingCodeVerifier: String? = null
    private var pendingState: String? = null
    private var pendingRedirectUri: String? = null

    actual fun init(context: Any?) {
        // Initialize session: if SupabaseClient already has valid session, reflect it
        if (SupabaseClient.hasValidUserSession()) {
            val user = SupabaseClient.currentUser.value ?: SupabaseConfig.DEFAULT_USER
            _authState.value = AuthState.Authenticated(user)
        } else {
            _authState.value = AuthState.Unauthenticated
        }
    }

    actual suspend fun signInWithGoogle(context: Any?): Result<UserProfile> = withContext(Dispatchers.IO) {
        val server = LoopbackAuthServer()
        try {
            val verifier = PkceCrypto.generateCodeVerifier()
            val challenge = PkceCrypto.deriveCodeChallenge(verifier)
            val state = PkceCrypto.generateState()

            val port = server.start()
            val redirectUri = "http://127.0.0.1:$port/callback"

            pendingCodeVerifier = verifier
            pendingState = state
            pendingRedirectUri = redirectUri

            val authUrl = "${SupabaseConfig.SUPABASE_URL}/auth/v1/authorize?" +
                    "provider=google" +
                    "&redirect_to=${redirectUri}" +
                    "&code_challenge=${challenge}" +
                    "&code_challenge_method=S256" +
                    "&state=${state}" +
                    "&prompt=select_account"

            launchSystemBrowser(authUrl)

            val callbackResult = server.awaitCallback(timeoutSeconds = 120)
            val callbackData = callbackResult.getOrThrow()

            if (callbackData.error != null) {
                return@withContext Result.failure(
                    Exception("Sign-in cancelled or failed: ${callbackData.errorDescription ?: callbackData.error}")
                )
            }

            if (callbackData.state != state) {
                return@withContext Result.failure(SecurityException("CSRF security state mismatch"))
            }

            val code = callbackData.code
                ?: return@withContext Result.failure(Exception("No authorization code received from Google"))

            val exchangeResult = SupabaseClient.exchangeCodeForSession(
                code = code,
                codeVerifier = verifier,
                redirectUri = redirectUri
            )

            exchangeResult.onSuccess { profile ->
                _authState.value = AuthState.Authenticated(profile)
            }
            exchangeResult
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            server.stop()
            pendingCodeVerifier = null
            pendingState = null
            pendingRedirectUri = null
        }
    }

    actual suspend fun handleAuthCallback(callbackUri: String, context: Any?): Result<UserProfile> = withContext(Dispatchers.IO) {
        try {
            val data = AuthCallbackParser.parse(callbackUri)
            if (data.isError) {
                return@withContext Result.failure(
                    Exception("Authentication error: ${data.errorDescription ?: data.error}")
                )
            }

            // PKCE Code exchange path
            val code = data.code
            if (!code.isNullOrBlank()) {
                val verifier = pendingCodeVerifier
                    ?: return@withContext Result.failure(Exception("No pending PKCE code verifier in session"))

                val expectedState = pendingState
                if (expectedState != null && data.state != null && data.state != expectedState) {
                    return@withContext Result.failure(SecurityException("CSRF state mismatch"))
                }

                val redirectUri = pendingRedirectUri ?: "streamify://callback"
                val result = SupabaseClient.exchangeCodeForSession(code, verifier, redirectUri)
                result.onSuccess { profile ->
                    _authState.value = AuthState.Authenticated(profile)
                }
                return@withContext result
            }

            // Legacy implicit token fallback
            val accessToken = data.accessToken
            if (!accessToken.isNullOrBlank()) {
                val result = SupabaseClient.signInWithAccessToken(accessToken)
                result.onSuccess { profile ->
                    _authState.value = AuthState.Authenticated(profile)
                }
                return@withContext result
            }

            Result.failure(Exception("No authorization code or access token found in callback URI"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    actual fun signInAsDefaultUser(context: Any?): Result<UserProfile> {
        val profile = SupabaseConfig.DEFAULT_USER
        SupabaseClient.setSession(SupabaseConfig.ANON_KEY, profile)
        _authState.value = AuthState.Authenticated(profile)
        return Result.success(profile)
    }

    actual fun signInAsGuest(context: Any?): Result<UserProfile> {
        val guest = UserProfile(
            id = "guest_desktop",
            email = "guest@streamify.app",
            displayName = "Guest User (Desktop)",
            avatarUrl = "",
            isAdmin = false,
        )
        SupabaseClient.setSession(null, guest)
        _authState.value = AuthState.Authenticated(guest)
        return Result.success(guest)
    }

    actual fun signOut(context: Any?) {
        _authState.value = AuthState.Unauthenticated
        SupabaseClient.clearSession()
    }

    private fun launchSystemBrowser(url: String) {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI(url))
                return
            }
        } catch (_: Exception) {}

        // Fallback to platform-native execution if Desktop.browse fails or is headless
        val os = System.getProperty("os.name", "").lowercase()
        try {
            when {
                os.contains("mac") -> ProcessBuilder("open", url).start()
                os.contains("win") -> ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", url).start()
                else -> ProcessBuilder("xdg-open", url).start()
            }
        } catch (_: Exception) {}
    }
}
