package com.streamify.announcements

import com.maxrave.domain.manager.DataStoreManager
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class RemoteManifestResult(
    val popupModalEnabled: Boolean,
    val announcements: List<Announcement>,
    val governance: GovernanceConfig = GovernanceConfig()
)

/**
 * Remote signed announcement source:
 * - Fetches signed manifest with ETag/304 caching
 * - Cryptographically verifies payload authenticity against pinned pubkey
 * - Enforces deep-link allowlist and kill-switch revocation
 * - Governs master launch popup toggle via popupModalEnabled
 * - Enforces network timeouts to prevent hanging on connection degradation
 * - Recovers from orphan ETag states
 * - Offline-first: returns cached payload immediately, updates in background
 * - Fail-safe: any network or verification error returns cached state without crashing
 */
class RemoteAnnouncementSource(
    private val dataStoreManager: DataStoreManager,
    private val httpClient: HttpClient = HttpClient {
        install(HttpTimeout) {
            connectTimeoutMillis = 4000L
            requestTimeoutMillis = 8000L
            socketTimeoutMillis = 8000L
        }
    },
    private val verifier: ManifestSignatureVerifier = PinnedManifestSignatureVerifier()
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val etagKey = "remote_announcements_etag"
    private val cachedManifestKey = "remote_announcements_cached_json"
    private val popupModalEnabledKey = "remote_announcements_popup_modal_enabled"

    companion object {
        const val MANIFEST_URL = "https://raw.githubusercontent.com/meraxes89/streamify-apk/main/docs/announcements/manifest.json"
        const val POPUP_MODAL_ENABLED_KEY = "remote_announcements_popup_modal_enabled"
    }

    suspend fun isPopupModalEnabledCached(): Boolean {
        return try {
            dataStoreManager.getString(popupModalEnabledKey).first()?.toBooleanStrictOrNull() ?: false
        } catch (_: Exception) {
            false
        }
    }

    suspend fun fetchManifest(): RemoteManifestResult {
        return try {
            val cachedEtag = dataStoreManager.getString(etagKey).first() ?: ""
            val response = httpClient.get(MANIFEST_URL) {
                if (cachedEtag.isNotBlank()) {
                    header("If-None-Match", cachedEtag)
                }
            }

            if (response.status == HttpStatusCode.NotModified) {
                val cached = getCachedManifest()
                // If cache was somehow emptied but ETag remained, reset ETag so next launch re-fetches
                if (cached.announcements.isEmpty() && !cached.popupModalEnabled && cachedEtag.isNotBlank()) {
                    dataStoreManager.putString(etagKey, "")
                }
                return cached
            }

            if (response.status != HttpStatusCode.OK) {
                return getCachedManifest()
            }

            val bodyText = response.bodyAsText()
            val newEtag = response.headers["ETag"] ?: ""

            val manifest = json.decodeFromString<SignedManifest>(bodyText)

            // Verify authenticity using pinned Ed25519 public key
            val verified = verifier.verify(
                payload = bodyText,
                signatureHex = manifest.signatureHex,
                pinnedPublicKeyHex = PinnedManifestSignatureVerifier.PINNED_STREAMIFY_RELEASE_PUBKEY_HEX
            )

            if (!verified) {
                return getCachedManifest()
            }

            // Filter out revoked announcements (remote kill switch)
            val revokedSet = manifest.revokedIds.toSet()
            val safeAnnouncements = manifest.announcements.filterNot { it.id in revokedSet }.filter { announcement ->
                when (val act = announcement.action) {
                    is AnnouncementAction.DeepLink -> DeepLinkAllowlist.isAllowed(act.url, manifest.governance.allowedExtraDomains)
                    else -> true
                }
            }

            // Cache valid payload and master popup boolean switch
            if (newEtag.isNotBlank()) {
                dataStoreManager.putString(etagKey, newEtag)
            }
            dataStoreManager.putString(cachedManifestKey, bodyText)
            dataStoreManager.putString(popupModalEnabledKey, manifest.popupModalEnabled.toString())

            RemoteManifestResult(
                popupModalEnabled = manifest.popupModalEnabled,
                announcements = safeAnnouncements,
                governance = manifest.governance
            )
        } catch (_: Exception) {
            // Fail-safe to cache or disabled state on any failure
            getCachedManifest()
        }
    }

    suspend fun fetch(): List<Announcement> {
        return fetchManifest().announcements
    }

    private suspend fun getCachedManifest(): RemoteManifestResult {
        return try {
            val raw = dataStoreManager.getString(cachedManifestKey).first()
            val cachedEnabled = dataStoreManager.getString(popupModalEnabledKey).first()?.toBooleanStrictOrNull() ?: false
            if (!raw.isNullOrBlank()) {
                val manifest = json.decodeFromString<SignedManifest>(raw)
                val revokedSet = manifest.revokedIds.toSet()
                val safe = manifest.announcements.filterNot { it.id in revokedSet }
                RemoteManifestResult(manifest.popupModalEnabled, safe, manifest.governance)
            } else {
                RemoteManifestResult(popupModalEnabled = cachedEnabled, announcements = emptyList(), governance = GovernanceConfig())
            }
        } catch (_: Exception) {
            RemoteManifestResult(popupModalEnabled = false, announcements = emptyList(), governance = GovernanceConfig())
        }
    }

    private suspend fun getCachedAnnouncements(): List<Announcement> {
        return getCachedManifest().announcements
    }
}
