package com.streamify.supabase

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.Desktop
import java.net.URI

actual object StreamifyAuthManager {
    private val _authState = MutableStateFlow<AuthState>(AuthState.Unauthenticated)
    actual val authState: StateFlow<AuthState> = _authState.asStateFlow()

    private val flowCoordinator = OAuthFlowCoordinator()
    actual val isAuthenticating: StateFlow<Boolean> = flowCoordinator.isAuthenticating
    actual val authError: StateFlow<String?> = flowCoordinator.authError

    actual fun submitAuthCallback(callbackUri: String, context: Any?) {
        flowCoordinator.submitCallback { handleAuthCallback(callbackUri, context) }
    }

    actual fun clearAuthError() = flowCoordinator.clearError()

    private var pendingCodeVerifier: String? = null
    private var pendingState: String? = null
    private var pendingRedirectUri: String? = null
    private var activeServer: LoopbackAuthServer? = null
    private var activeCompletionDeferred: CompletableDeferred<Result<UserProfile>>? = null

    actual fun init(context: Any?) {
        PlatformTokenStore.init(context)
        val restored = SupabaseClient.restorePersistedSession()
        if (restored != null) {
            _authState.value = AuthState.Authenticated(restored.user)
            val refreshToken = restored.refreshToken
            if (!refreshToken.isNullOrBlank()) {
                CoroutineScope(Dispatchers.IO).launch {
                    SupabaseClient.refreshSession(refreshToken)
                }
            }
        } else if (SupabaseClient.hasValidUserSession()) {
            val user = SupabaseClient.currentUser.value ?: SupabaseConfig.DEFAULT_USER
            _authState.value = AuthState.Authenticated(user)
        } else {
            _authState.value = AuthState.Unauthenticated
        }
    }

    actual suspend fun signInWithGoogle(context: Any?): Result<UserProfile> = withContext(Dispatchers.IO) {
        val server = LoopbackAuthServer()
        activeServer = server
        val completion = CompletableDeferred<Result<UserProfile>>()
        activeCompletionDeferred = completion

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

            // Concurrently listen on the loopback server while leaving the door open for
            // deep-link callbacks (e.g. macOS Apple Events routing to handleAuthCallback)
            val loopbackJob = launch {
                val callbackResult = server.awaitCallback(timeoutSeconds = 120)
                if (completion.isCompleted) return@launch

                val callbackData = callbackResult.getOrElse { err ->
                    completion.complete(Result.failure(err))
                    return@launch
                }

                if (callbackData.error != null) {
                    completion.complete(
                        Result.failure(Exception("Sign-in cancelled or failed: ${callbackData.errorDescription ?: callbackData.error}"))
                    )
                    return@launch
                }

                if (callbackData.state != state) {
                    completion.complete(Result.failure(SecurityException("CSRF security state mismatch")))
                    return@launch
                }

                val code = callbackData.code
                if (code == null) {
                    completion.complete(Result.failure(Exception("No authorization code received from Google")))
                    return@launch
                }

                val exchangeResult = SupabaseClient.exchangeCodeForSession(
                    code = code,
                    codeVerifier = verifier,
                    redirectUri = redirectUri
                )

                exchangeResult.onSuccess { profile ->
                    _authState.value = AuthState.Authenticated(profile)
                }
                completion.complete(exchangeResult)
            }

            val finalResult = completion.await()
            loopbackJob.cancel()
            finalResult
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            server.stop()
            activeServer = null
            activeCompletionDeferred = null
            pendingCodeVerifier = null
            pendingState = null
            pendingRedirectUri = null
        }
    }

    actual suspend fun handleAuthCallback(callbackUri: String, context: Any?): Result<UserProfile> = withContext(Dispatchers.IO) {
        try {
            val data = AuthCallbackParser.parse(callbackUri)
            if (data.isError) {
                val err = Result.failure<UserProfile>(
                    Exception("Authentication error: ${data.errorDescription ?: data.error}")
                )
                activeCompletionDeferred?.complete(err)
                activeServer?.stop()
                return@withContext err
            }

            // PKCE Code exchange path
            val code = data.code
            if (!code.isNullOrBlank()) {
                val verifier = pendingCodeVerifier
                    ?: return@withContext Result.failure(Exception("No pending PKCE code verifier in session"))

                val expectedState = pendingState
                if (expectedState.isNullOrBlank() || data.state != expectedState) {
                    val err = Result.failure<UserProfile>(SecurityException("Missing or mismatched CSRF state"))
                    activeCompletionDeferred?.complete(err)
                    activeServer?.stop()
                    return@withContext err
                }

                val redirectUri = pendingRedirectUri ?: "streamify://callback"
                val result = SupabaseClient.exchangeCodeForSession(code, verifier, redirectUri)
                result.onSuccess { profile ->
                    _authState.value = AuthState.Authenticated(profile)
                }
                // Unblock pending signInWithGoogle call and close the loopback server immediately
                activeCompletionDeferred?.complete(result)
                activeServer?.stop()
                return@withContext result
            }

            if (!data.accessToken.isNullOrBlank()) {
                val err = Result.failure<UserProfile>(SecurityException("Implicit access_token flow is disabled; PKCE code required"))
                activeCompletionDeferred?.complete(err)
                activeServer?.stop()
                return@withContext err
            }

            val noTokenErr = Result.failure<UserProfile>(Exception("No authorization code or access token found in callback URI"))
            activeCompletionDeferred?.complete(noTokenErr)
            activeServer?.stop()
            return@withContext noTokenErr
        } catch (e: Exception) {
            val err = Result.failure<UserProfile>(e)
            activeCompletionDeferred?.complete(err)
            activeServer?.stop()
            return@withContext err
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
        PlatformTokenStore.clear()
        SupabaseClient.setSession(null, guest)
        _authState.value = AuthState.Authenticated(guest)
        return Result.success(guest)
    }

    actual fun signOut(context: Any?) {
        val tokenToRevoke = SupabaseClient.accessToken.value
        _authState.value = AuthState.Unauthenticated
        SupabaseClient.clearSession()
        CoroutineScope(Dispatchers.IO).launch {
            SupabaseClient.signOutRemote(tokenToRevoke)
        }
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
