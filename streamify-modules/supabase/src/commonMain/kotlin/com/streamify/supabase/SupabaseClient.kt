package com.streamify.supabase

import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlin.time.Clock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Universal Supabase Client facade.
 * Manages authenticated user sessions, PostgREST requests, and verified server claims.
 */
object SupabaseClient {

    private val _currentUser = MutableStateFlow<UserProfile?>(SupabaseConfig.DEFAULT_USER)
    val currentUser: StateFlow<UserProfile?> = _currentUser.asStateFlow()

    private val _accessToken = MutableStateFlow<String?>(null)
    val accessToken: StateFlow<String?> = _accessToken.asStateFlow()

    private val _refreshToken = MutableStateFlow<String?>(null)
    val refreshToken: StateFlow<String?> = _refreshToken.asStateFlow()

    private val _expiresAtEpochMs = MutableStateFlow(0L)
    val expiresAtEpochMs: StateFlow<Long> = _expiresAtEpochMs.asStateFlow()

    val refreshCoordinator = AuthRefreshMutex()

    val httpClient: HttpClient by lazy {
        HttpClient {
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
     * True if the client holds an authenticated user session (not anonymous key).
     */
    fun hasValidUserSession(): Boolean {
        val token = _accessToken.value
        return !token.isNullOrBlank() && token != SupabaseConfig.ANON_KEY
    }

    /**
     * Updates user session after server authentication.
     */
    fun setSession(
        token: String?,
        profile: UserProfile?,
        refreshToken: String? = null,
        expiresAtEpochMs: Long = 0L,
    ) {
        _accessToken.value = token
        _currentUser.value = profile ?: SupabaseConfig.DEFAULT_USER
        _refreshToken.value = refreshToken
        _expiresAtEpochMs.value = expiresAtEpochMs
    }

    fun clearSession() {
        _accessToken.value = null
        _refreshToken.value = null
        _expiresAtEpochMs.value = 0L
        _currentUser.value = SupabaseConfig.DEFAULT_USER
        PlatformTokenStore.clear()
    }

    /**
     * Best-effort remote session revocation via Supabase `/auth/v1/logout`.
     */
    suspend fun signOutRemote(token: String?) {
        if (token.isNullOrBlank() || token == SupabaseConfig.ANON_KEY) return
        withContext(ioDispatcher) {
            try {
                httpClient.post("${SupabaseConfig.SUPABASE_URL}/auth/v1/logout") {
                    header("apikey", SupabaseConfig.ANON_KEY)
                    header("Authorization", "Bearer $token")
                }
            } catch (_: Exception) {
                // Best-effort revocation; local session is already cleared.
            }
        }
    }

    internal fun persistCurrentSession() {
        val token = _accessToken.value ?: return
        if (token.isBlank() || token == SupabaseConfig.ANON_KEY) return
        val user = _currentUser.value ?: return
        PlatformTokenStore.save(
            StoredAuthSession(
                user = user,
                accessToken = token,
                refreshToken = _refreshToken.value,
                expiresAtEpochMs = _expiresAtEpochMs.value,
            ),
        )
    }

    internal fun restorePersistedSession(): StoredAuthSession? {
        val stored = PlatformTokenStore.load() ?: return null
        if (stored.accessToken.isBlank() || stored.accessToken == SupabaseConfig.ANON_KEY) return null
        setSession(
            token = stored.accessToken,
            profile = stored.user,
            refreshToken = stored.refreshToken,
            expiresAtEpochMs = stored.expiresAtEpochMs,
        )
        return stored
    }

    /**
     * Ensures the current access token is fresh, proactively refreshing via [AuthRefreshMutex]
     * if [AuthRefreshMutex.isTokenExpired] indicates it is within the expiry buffer window.
     */
    suspend fun ensureValidAccessToken(): String {
        val current = _accessToken.value
        if (current.isNullOrBlank() || current == SupabaseConfig.ANON_KEY) {
            return SupabaseConfig.ANON_KEY
        }
        val expiresAt = _expiresAtEpochMs.value
        if (expiresAt > 0L && refreshCoordinator.isTokenExpired(expiresAt) && !_refreshToken.value.isNullOrBlank()) {
            refreshSession()
        }
        return getAuthToken()
    }

    private fun parseExpiresAtEpochMs(rootObj: kotlinx.serialization.json.JsonObject): Long {
        val expiresAtSec = rootObj["expires_at"]?.jsonPrimitive?.content?.toLongOrNull()
        val expiresInSec = rootObj["expires_in"]?.jsonPrimitive?.content?.toLongOrNull() ?: 3600L
        return if (expiresAtSec != null && expiresAtSec > 0L) {
            expiresAtSec * 1000L
        } else {
            Clock.System.now().toEpochMilliseconds() + (expiresInSec * 1000L)
        }
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

    /**
     * Signs in with a Google ID Token (issued by Google Credential Manager One-Tap)
     * via Supabase Auth's id_token grant.
     */
    suspend fun signInWithGoogleIdToken(idToken: String): Result<UserProfile> = withContext(ioDispatcher) {
        try {
            val url = "${SupabaseConfig.SUPABASE_URL}/auth/v1/token?grant_type=id_token"
            val payload = buildJsonObject {
                put("provider", "google")
                put("id_token", idToken)
            }.toString()
            val response = httpClient.post(url) {
                header("apikey", SupabaseConfig.ANON_KEY)
                contentType(ContentType.Application.Json)
                setBody(payload)
            }

            val bodyText = response.bodyAsText()
            if (response.status.isSuccess()) {
                val jsonParser = Json { ignoreUnknownKeys = true }
                val rootObj = jsonParser.parseToJsonElement(bodyText).jsonObject
                val token = rootObj["access_token"]?.jsonPrimitive?.content ?: ""
                val refreshTokenStr = rootObj["refresh_token"]?.jsonPrimitive?.content
                val userObj = rootObj["user"]?.jsonObject
                val userId = userObj?.get("id")?.jsonPrimitive?.content ?: ""
                val email = userObj?.get("email")?.jsonPrimitive?.content ?: ""
                val meta = userObj?.get("user_metadata")?.jsonObject
                val name = meta?.get("full_name")?.jsonPrimitive?.content
                    ?: meta?.get("name")?.jsonPrimitive?.content
                    ?: email.substringBefore("@")
                val avatar = meta?.get("avatar_url")?.jsonPrimitive?.content
                    ?: meta?.get("picture")?.jsonPrimitive?.content
                    ?: ""

                _accessToken.value = token
                if (!refreshTokenStr.isNullOrBlank()) {
                    _refreshToken.value = refreshTokenStr
                }
                _expiresAtEpochMs.value = parseExpiresAtEpochMs(rootObj)
                val isAdminUser = fetchServerIsAdmin(userId)

                val profile = UserProfile(
                    id = userId,
                    email = email,
                    displayName = name,
                    avatarUrl = avatar,
                    isAdmin = isAdminUser
                )

                _currentUser.value = profile
                persistCurrentSession()
                ensureProfile(profile)

                Result.success(profile)
            } else {
                Result.failure(Exception("Google Sign-In failed: $bodyText"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Authenticates a user using an access token obtained from Supabase OAuth redirects.
     * Fetches user profile from /auth/v1/user, verifies admin claims, and stores any accompanying refresh token.
     */
    suspend fun signInWithAccessToken(
        token: String,
        refreshToken: String? = null,
    ): Result<UserProfile> = withContext(ioDispatcher) {
        try {
            val url = "${SupabaseConfig.SUPABASE_URL}/auth/v1/user"
            val response = httpClient.get(url) {
                header("apikey", SupabaseConfig.ANON_KEY)
                header("Authorization", "Bearer $token")
            }

            val bodyText = response.bodyAsText()
            if (response.status.isSuccess()) {
                val jsonParser = Json { ignoreUnknownKeys = true }
                val userObj = jsonParser.parseToJsonElement(bodyText).jsonObject
                val userId = userObj["id"]?.jsonPrimitive?.content ?: ""
                val email = userObj["email"]?.jsonPrimitive?.content ?: ""
                val meta = userObj["user_metadata"]?.jsonObject
                val name = meta?.get("full_name")?.jsonPrimitive?.content
                    ?: meta?.get("name")?.jsonPrimitive?.content
                    ?: email.substringBefore("@")
                val avatar = meta?.get("avatar_url")?.jsonPrimitive?.content
                    ?: meta?.get("picture")?.jsonPrimitive?.content
                    ?: ""

                _accessToken.value = token
                val isAdminUser = fetchServerIsAdmin(userId)

                val profile = UserProfile(
                    id = userId,
                    email = email,
                    displayName = name,
                    avatarUrl = avatar,
                    isAdmin = isAdminUser,
                )

                setSession(token, profile, refreshToken?.takeIf { it.isNotBlank() })
                persistCurrentSession()
                ensureProfile(profile)

                Result.success(profile)
            } else {
                Result.failure(Exception("Token verification failed: $bodyText"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Refreshes the active user session using the stored refresh token.
     * Protected by [AuthRefreshMutex] to prevent Supabase Refresh Token Rotation (RTR) breaches.
     */
    suspend fun refreshSession(explicitRefreshToken: String? = null): Result<String> = withContext(ioDispatcher) {
        val tokenToUse = explicitRefreshToken ?: _refreshToken.value
        if (tokenToUse.isNullOrBlank()) {
            return@withContext Result.failure(Exception("No refresh token available"))
        }
        val callerScope = CoroutineScope(coroutineContext)
        val sessionResult = refreshCoordinator.executeSingleFlight(callerScope) {
            try {
                val url = "${SupabaseConfig.SUPABASE_URL}/auth/v1/token?grant_type=refresh_token"
                val payload = buildJsonObject {
                    put("refresh_token", tokenToUse)
                }.toString()
                val response = httpClient.post(url) {
                    header("apikey", SupabaseConfig.ANON_KEY)
                    contentType(ContentType.Application.Json)
                    setBody(payload)
                }
                val bodyText = response.bodyAsText()
                if (response.status.isSuccess()) {
                    val jsonParser = Json { ignoreUnknownKeys = true }
                    val rootObj = jsonParser.parseToJsonElement(bodyText).jsonObject
                    val newAccessToken = rootObj["access_token"]?.jsonPrimitive?.content ?: ""
                    val newRefreshToken = rootObj["refresh_token"]?.jsonPrimitive?.content ?: tokenToUse
                    val expiresAt = parseExpiresAtEpochMs(rootObj)

                    _accessToken.value = newAccessToken
                    _refreshToken.value = newRefreshToken
                    _expiresAtEpochMs.value = expiresAt
                    persistCurrentSession()
                    Result.success(TokenSession(newAccessToken, newRefreshToken, expiresAt))
                } else {
                    Result.failure(Exception("Token refresh failed: $bodyText"))
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
        sessionResult.map { it.accessToken }
    }

    /**
     * Exchanges an OAuth PKCE authorization code for an authenticated user session
     * via POST /auth/v1/token?grant_type=pkce (with fallback to grant_type=authorization_code).
     */
    suspend fun exchangeCodeForSession(
        code: String,
        codeVerifier: String,
        redirectUri: String? = null
    ): Result<UserProfile> = withContext(ioDispatcher) {
        try {
            val pkceUrl = "${SupabaseConfig.SUPABASE_URL}/auth/v1/token?grant_type=pkce"
            val pkcePayload = buildJsonObject {
                put("auth_code", code)
                put("code_verifier", codeVerifier)
            }.toString()

            var response = httpClient.post(pkceUrl) {
                header("apikey", SupabaseConfig.ANON_KEY)
                contentType(ContentType.Application.Json)
                setBody(pkcePayload)
            }

            // Fallback for GoTrue instances expecting grant_type=authorization_code
            if (!response.status.isSuccess() && !redirectUri.isNullOrBlank()) {
                val authCodeUrl = "${SupabaseConfig.SUPABASE_URL}/auth/v1/token?grant_type=authorization_code"
                val authCodePayload = buildJsonObject {
                    put("auth_code", code)
                    put("code_verifier", codeVerifier)
                    put("redirect_uri", redirectUri)
                }.toString()

                val fallbackResponse = httpClient.post(authCodeUrl) {
                    header("apikey", SupabaseConfig.ANON_KEY)
                    contentType(ContentType.Application.Json)
                    setBody(authCodePayload)
                }
                if (fallbackResponse.status.isSuccess()) {
                    response = fallbackResponse
                }
            }

            val bodyText = response.bodyAsText()
            if (response.status.isSuccess()) {
                val jsonParser = Json { ignoreUnknownKeys = true }
                val rootObj = jsonParser.parseToJsonElement(bodyText).jsonObject
                val token = rootObj["access_token"]?.jsonPrimitive?.content ?: ""
                val refreshTokenStr = rootObj["refresh_token"]?.jsonPrimitive?.content
                val userObj = rootObj["user"]?.jsonObject
                val userId = userObj?.get("id")?.jsonPrimitive?.content ?: ""
                val email = userObj?.get("email")?.jsonPrimitive?.content ?: ""
                val meta = userObj?.get("user_metadata")?.jsonObject
                val name = meta?.get("full_name")?.jsonPrimitive?.content
                    ?: meta?.get("name")?.jsonPrimitive?.content
                    ?: email.substringBefore("@")
                val avatar = meta?.get("avatar_url")?.jsonPrimitive?.content
                    ?: meta?.get("picture")?.jsonPrimitive?.content
                    ?: ""

                _accessToken.value = token
                if (!refreshTokenStr.isNullOrBlank()) {
                    _refreshToken.value = refreshTokenStr
                }
                _expiresAtEpochMs.value = parseExpiresAtEpochMs(rootObj)
                val isAdminUser = fetchServerIsAdmin(userId)

                val profile = UserProfile(
                    id = userId,
                    email = email,
                    displayName = name,
                    avatarUrl = avatar,
                    isAdmin = isAdminUser
                )

                _currentUser.value = profile
                persistCurrentSession()
                ensureProfile(profile)

                Result.success(profile)
            } else {
                Result.failure(Exception("PKCE code exchange failed: $bodyText"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * SEC-09/SEC-10: resolve the admin claim from the server via get_my_admin_status() RPC.
     */
    internal suspend fun fetchServerIsAdmin(userId: String): Boolean = withContext(ioDispatcher) {
        try {
            val url = "${SupabaseConfig.SUPABASE_URL}/rest/v1/rpc/get_my_admin_status"
            val resp = httpClient.post(url) {
                header("apikey", SupabaseConfig.ANON_KEY)
                header("Authorization", "Bearer ${getAuthToken()}")
                contentType(ContentType.Application.Json)
                setBody("{}")
            }
            if (!resp.status.isSuccess()) return@withContext false
            val text = resp.bodyAsText().trim()
            text.equals("true", ignoreCase = true)
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Ensures user profile exists in `profiles` table.
     */
    suspend fun ensureProfile(user: UserProfile) = withContext(ioDispatcher) {
        try {
            val url = "${SupabaseConfig.SUPABASE_URL}/rest/v1/profiles?on_conflict=id"
            val jsonPayload = """
                {
                    "id": "${user.id}",
                    "email": "${user.email}",
                    "display_name": "${user.displayName}",
                    "avatar_url": "${user.avatarUrl}"
                }
            """.trimIndent()

            httpClient.post(url) {
                header("apikey", SupabaseConfig.ANON_KEY)
                header("Authorization", "Bearer ${getAuthToken()}")
                header("Prefer", "resolution=merge-duplicates")
                contentType(ContentType.Application.Json)
                setBody(jsonPayload)
            }
        } catch (_: Exception) {}
    }
}
