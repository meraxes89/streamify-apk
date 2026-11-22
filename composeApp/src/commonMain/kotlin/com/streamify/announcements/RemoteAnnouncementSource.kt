package com.streamify.announcements

import com.maxrave.domain.manager.DataStoreManager
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json

/**
 * Remote signed announcement source:
 * - Fetches signed manifest with ETag/304 caching
 * - Cryptographically verifies payload authenticity against pinned pubkey
 * - Enforces deep-link allowlist and kill-switch revocation
 * - Offline-first: returns cached payload immediately, updates in background
 * - Fail-safe: any network or verification error returns emptyList() without crashing
 */
class RemoteAnnouncementSource(
    private val dataStoreManager: DataStoreManager,
    private val httpClient: HttpClient = HttpClient(),
    private val verifier: ManifestSignatureVerifier = PinnedManifestSignatureVerifier()
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val etagKey = "remote_announcements_etag"
    private val cachedManifestKey = "remote_announcements_cached_json"

    companion object {
        const val MANIFEST_URL = "https://raw.githubusercontent.com/meraxes89/streamify-apk/main/docs/announcements/manifest.json"
    }

    suspend fun fetch(): List<Announcement> {
        return try {
            val cachedEtag = dataStoreManager.getString(etagKey).first() ?: ""
            val response = httpClient.get(MANIFEST_URL) {
                if (cachedEtag.isNotBlank()) {
                    header("If-None-Match", cachedEtag)
                }
            }

            if (response.status == HttpStatusCode.NotModified) {
                return getCachedAnnouncements()
            }

            if (response.status != HttpStatusCode.OK) {
                return getCachedAnnouncements()
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
                return getCachedAnnouncements()
            }

            // Filter out revoked announcements (remote kill switch)
            val revokedSet = manifest.revokedIds.toSet()
            val safeAnnouncements = manifest.announcements.filterNot { it.id in revokedSet }.filter { announcement ->
                when (val act = announcement.action) {
                    is AnnouncementAction.DeepLink -> DeepLinkAllowlist.isAllowed(act.url)
                    else -> true
                }
            }

            // Cache valid payload
            if (newEtag.isNotBlank()) {
                dataStoreManager.putString(etagKey, newEtag)
            }
            dataStoreManager.putString(cachedManifestKey, bodyText)

            safeAnnouncements
        } catch (_: Exception) {
            // Fail-safe to cache or emptyList() on any failure
            getCachedAnnouncements()
        }
    }

    private suspend fun getCachedAnnouncements(): List<Announcement> {
        return try {
            val raw = dataStoreManager.getString(cachedManifestKey).first()
            if (!raw.isNullOrBlank()) {
                val manifest = json.decodeFromString<SignedManifest>(raw)
                val revokedSet = manifest.revokedIds.toSet()
                manifest.announcements.filterNot { it.id in revokedSet }
            } else {
                emptyList()
            }
        } catch (_: Exception) {
            emptyList()
        }
    }
}
