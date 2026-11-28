package com.streamify.announcements

import com.maxrave.domain.manager.DataStoreManager
import com.streamify.supabase.SupabaseClient
import com.streamify.supabase.SupabaseConfig
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json

/**
 * Authenticated 1:1 & segment-targeted announcement source backed by Supabase
 * `public.get_targeted_announcements()` RPC (RLS-protected).
 *
 * Trust-boundary invariants:
 * - Only queries when the user holds a valid authenticated session (never with ANON_KEY alone).
 * - Respects [revokedIds] from the Ed25519-signed [RemoteAnnouncementSource] as global kill switch.
 * - Enforces [DeepLinkAllowlist] on every [AnnouncementAction.DeepLink] before returning.
 * - Fail-safe: any network/parse error returns cached user-scoped payloads or an empty list.
 */
class SupabaseAnnouncementSource(
    private val dataStoreManager: DataStoreManager? = null,
    private val sessionProvider: () -> Pair<String?, String?> = {
        SupabaseClient.accessToken.value to SupabaseClient.currentUser.value?.id
    },
    private val rpcFetcher: (suspend (accessToken: String) -> String?)? = null,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val cachedJsonKey = "supabase_announcements_cached_json"
    private val cachedUidKey = "supabase_announcements_cached_uid"

    private val httpClient: HttpClient by lazy {
        HttpClient {
            install(HttpTimeout) {
                connectTimeoutMillis = 4000L
                requestTimeoutMillis = 8000L
                socketTimeoutMillis = 8000L
            }
        }
    }

    suspend fun fetch(
        revokedIds: Set<String> = emptySet(),
        allowedExtraDomains: List<String> = emptyList(),
    ): List<Announcement> {
        val (accessToken, userId) = sessionProvider()
        if (accessToken.isNullOrBlank() || accessToken == SupabaseConfig.ANON_KEY || userId.isNullOrBlank()) {
            return emptyList()
        }

        return try {
            val raw = if (rpcFetcher != null) {
                rpcFetcher.invoke(accessToken)
            } else {
                val response = httpClient.post("${SupabaseConfig.SUPABASE_URL}/rest/v1/rpc/get_targeted_announcements") {
                    header("apikey", SupabaseConfig.ANON_KEY)
                    header("Authorization", "Bearer $accessToken")
                    contentType(ContentType.Application.Json)
                    setBody("{}")
                }
                if (response.status != HttpStatusCode.OK) {
                    return readCached(userId, revokedIds, allowedExtraDomains)
                }
                response.bodyAsText()
            }

            if (raw.isNullOrBlank()) {
                return readCached(userId, revokedIds, allowedExtraDomains)
            }

            val parsed = parseAndFilter(raw, revokedIds, allowedExtraDomains)
            dataStoreManager?.let { store ->
                store.putString(cachedUidKey, userId)
                store.putString(cachedJsonKey, raw)
            }
            parsed
        } catch (_: Exception) {
            readCached(userId, revokedIds, allowedExtraDomains)
        }
    }

    internal fun parseAndFilter(
        rawJson: String,
        revokedIds: Set<String> = emptySet(),
        allowedExtraDomains: List<String> = emptyList(),
    ): List<Announcement> {
        val decoded = json.decodeFromString<List<Announcement>>(rawJson)
        return decoded
            .filterNot { it.id in revokedIds }
            .filter { announcement ->
                when (val act = announcement.action) {
                    is AnnouncementAction.DeepLink -> DeepLinkAllowlist.isAllowed(act.url, allowedExtraDomains)
                    else -> true
                }
            }
    }

    private suspend fun readCached(
        userId: String,
        revokedIds: Set<String>,
        allowedExtraDomains: List<String>,
    ): List<Announcement> {
        val store = dataStoreManager ?: return emptyList()
        return try {
            val cachedUid = store.getString(cachedUidKey).first()
            if (cachedUid != userId) {
                store.putString(cachedJsonKey, "")
                return emptyList()
            }
            val raw = store.getString(cachedJsonKey).first()
            if (raw.isNullOrBlank()) emptyList() else parseAndFilter(raw, revokedIds, allowedExtraDomains)
        } catch (_: Exception) {
            emptyList()
        }
    }
}
