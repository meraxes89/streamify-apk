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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Universal Supabase Client facade.
 * Manages authenticated user sessions, PostgREST requests, and verified server claims.
 */
object SupabaseClient {

    private val _currentUser = MutableStateFlow<UserProfile?>(SupabaseConfig.DEFAULT_USER)
    val currentUser: StateFlow<UserProfile?> = _currentUser.asStateFlow()

    private val _accessToken = MutableStateFlow<String?>(null)
    val accessToken: StateFlow<String?> = _accessToken.asStateFlow()

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
     * Updates user session after server authentication.
     */
    fun setSession(token: String?, profile: UserProfile?) {
        _accessToken.value = token
        _currentUser.value = profile ?: SupabaseConfig.DEFAULT_USER
    }

    fun clearSession() {
        _accessToken.value = null
        _currentUser.value = SupabaseConfig.DEFAULT_USER
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
            val response = httpClient.post(url) {
                header("apikey", SupabaseConfig.ANON_KEY)
                contentType(ContentType.Application.Json)
                setBody("""{"provider":"google","id_token":"$idToken"}""")
            }

            val bodyText = response.bodyAsText()
            if (response.status.isSuccess()) {
                val jsonParser = Json { ignoreUnknownKeys = true }
                val rootObj = jsonParser.parseToJsonElement(bodyText).jsonObject
                val token = rootObj["access_token"]?.jsonPrimitive?.content ?: ""
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
                val isAdminUser = fetchServerIsAdmin(userId)

                val profile = UserProfile(
                    id = userId,
                    email = email,
                    displayName = name,
                    avatarUrl = avatar,
                    isAdmin = isAdminUser
                )

                _currentUser.value = profile
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
     * Fetches user profile from /auth/v1/user and verifies admin claims.
     */
    suspend fun signInWithAccessToken(token: String): Result<UserProfile> = withContext(ioDispatcher) {
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

                _currentUser.value = profile
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
