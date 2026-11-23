package com.maxrave.simpmusic.data.update

import com.maxrave.simpmusic.utils.VersionManager
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.head
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Pure suspend 3-tier update resolution ladder (comm2.md).
 *
 * Tier 1: Unmetered Fastly CDN manifest (releases/latest/download/version.json?cb=<epoch/300_000>).
 * Tier 2: Self-healing tag probe via HTTP 302 Location on HEAD releases/latest/download/SHA256SUMS.
 * Tier 3: Circuit-broken REST API fallback with If-None-Match ETag revalidation.
 */
class UpdateResolver(
    private val cdnClient: HttpClient = HttpClient(CIO) {
        followRedirects = true
        expectSuccess = false
        install(HttpTimeout) {
            connectTimeoutMillis = 5_000
            requestTimeoutMillis = 10_000
        }
    },
    private val probeClient: HttpClient = HttpClient(CIO) {
        followRedirects = false
        expectSuccess = false
        install(HttpTimeout) {
            connectTimeoutMillis = 5_000
            requestTimeoutMillis = 10_000
        }
    },
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val repoOwner: String = "meraxes89",
    private val repoName: String = "streamify-apk",
) {
    private val baseUrl = "https://github.com/$repoOwner/$repoName"
    private val apiLatestUrl = "https://api.github.com/repos/$repoOwner/$repoName/releases?per_page=1"

    // Circuit breaker state for Tier 3 (REST API last resort)
    private var t3DisabledUntilMs: Long = 0L
    private var storedEtag: String? = null

    suspend fun resolve(installedCode: Int): UpdateResult {
        // T1: Unmetered CDN manifest (99.9% happy path)
        // Cache-busting 5-min floor (?cb=<epoch/300_000>) avoids stale CDN edge redirects
        runCatching {
            val epochBucket = System.currentTimeMillis() / 300_000L
            val response = cdnClient.get("$baseUrl/releases/latest/download/version.json?cb=$epochBucket") {
                header(HttpHeaders.UserAgent, "Streamify-App")
            }
            if (response.status == HttpStatusCode.OK) {
                val manifest = json.decodeFromString<UpdateManifest>(response.bodyAsText())
                return compareAndReturn(manifest, installedCode)
            }
        }

        // T2: Self-healing tag probe (repair tier)
        // HEAD releases/latest/download/SHA256SUMS with followRedirects=false
        runCatching {
            val headRes = probeClient.head("$baseUrl/releases/latest/download/SHA256SUMS") {
                header(HttpHeaders.UserAgent, "Streamify-App")
            }
            val location = headRes.headers[HttpHeaders.Location]
            if (!location.isNullOrBlank()) {
                val tag = Regex("/releases/download/([^/]+)/").find(location)?.groupValues?.get(1)
                if (!tag.isNullOrBlank()) {
                    // Try pinned manifest for this authoritative tag
                    val pinnedRes = cdnClient.get("$baseUrl/releases/download/$tag/version.json") {
                        header(HttpHeaders.UserAgent, "Streamify-App")
                    }
                    if (pinnedRes.status == HttpStatusCode.OK) {
                        val manifest = json.decodeFromString<UpdateManifest>(pinnedRes.bodyAsText())
                        return compareAndReturn(manifest, installedCode)
                    }
                    // Legacy release with no version.json: provide tag directly
                    if (VersionManager.isBehind(tag)) {
                        return UpdateResult.TagOnly(tag)
                    } else {
                        return UpdateResult.UpToDate
                    }
                }
            }
        }

        // T3: Circuit-broken REST API (last resort fallback)
        val now = System.currentTimeMillis()
        if (now < t3DisabledUntilMs) {
            return UpdateResult.Error("API fallback circuit-broken")
        }

        return runCatching {
            val req = cdnClient.get(apiLatestUrl) {
                header(HttpHeaders.UserAgent, "Streamify-App")
                header(HttpHeaders.Accept, "application/vnd.github.v3+json")
                if (!storedEtag.isNullOrBlank()) {
                    header(HttpHeaders.IfNoneMatch, storedEtag!!)
                }
            }
            if (req.status == HttpStatusCode.NotModified) {
                return@runCatching UpdateResult.UpToDate
            }
            if (req.status == HttpStatusCode.Forbidden || req.status.value == 429) {
                // Trip circuit breaker for 1 hour on rate limit
                t3DisabledUntilMs = now + 3_600_000L
                return@runCatching UpdateResult.Error("API rate limit exceeded. Circuit broken for 1 hour.")
            }
            if (req.status == HttpStatusCode.OK) {
                storedEtag = req.headers[HttpHeaders.ETag]
                val bodyText = req.bodyAsText()
                val jsonElement = json.parseToJsonElement(bodyText)
                val obj = (jsonElement as? JsonArray)?.firstOrNull()?.jsonObject
                    ?: (jsonElement as? JsonObject)
                    ?: return@runCatching UpdateResult.Error("Unexpected API response structure")

                val tagName = obj["tag_name"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content ?: ""
                val publishedAt = obj["published_at"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content
                val body = obj["body"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content ?: ""

                if (tagName.isNotBlank()) {
                    // Self-healing: if the release carries a pinned version.json manifest, prefer it
                    runCatching {
                        val pinnedRes = cdnClient.get("$baseUrl/releases/download/$tagName/version.json") {
                            header(HttpHeaders.UserAgent, "Streamify-App")
                        }
                        if (pinnedRes.status == HttpStatusCode.OK) {
                            val pinnedManifest = json.decodeFromString<UpdateManifest>(pinnedRes.bodyAsText())
                            return@runCatching compareAndReturn(pinnedManifest, installedCode)
                        }
                    }
                }

                val artifactsMap = mutableMapOf<String, UpdateArtifact>()
                val assets = obj["assets"]?.takeUnless { it is JsonNull }?.let {
                    runCatching { it.jsonArray }.getOrNull()
                }.orEmpty()

                for (elem in assets) {
                    val aObj = elem.jsonObject
                    val name = aObj["name"]?.jsonPrimitive?.content.orEmpty()
                    val downloadUrl = aObj["browser_download_url"]?.jsonPrimitive?.content.orEmpty()
                    if (downloadUrl.isBlank()) continue

                    val lowerName = name.lowercase()
                    if (lowerName.endsWith(".apk")) {
                        if ("vivo" in lowerName) {
                            when {
                                "arm64" in lowerName -> artifactsMap["android-vivo-arm64"] = UpdateArtifact(url = downloadUrl)
                                "armeabi" in lowerName -> artifactsMap["android-vivo-armeabi-v7a"] = UpdateArtifact(url = downloadUrl)
                                else -> {
                                    artifactsMap["android-vivo-universal"] = UpdateArtifact(url = downloadUrl)
                                    artifactsMap["android-vivo"] = UpdateArtifact(url = downloadUrl)
                                }
                            }
                            continue
                        }
                        when {
                            "arm64" in lowerName -> artifactsMap["android-arm64-v8a"] = UpdateArtifact(url = downloadUrl)
                            "armeabi" in lowerName -> artifactsMap["android-armeabi-v7a"] = UpdateArtifact(url = downloadUrl)
                            "x86_64" in lowerName -> artifactsMap["android-x86_64"] = UpdateArtifact(url = downloadUrl)
                            "universal" in lowerName || (lowerName.startsWith("streamify-v") && !listOf("arm64", "armeabi", "x86_64").any { it in lowerName }) -> {
                                artifactsMap["android-universal"] = UpdateArtifact(url = downloadUrl)
                                artifactsMap["android"] = UpdateArtifact(url = downloadUrl)
                            }
                        }
                    } else if (lowerName.endsWith(".msi") || lowerName.endsWith(".exe")) {
                        artifactsMap["windows-x64"] = UpdateArtifact(url = downloadUrl)
                        artifactsMap["windows"] = UpdateArtifact(url = downloadUrl)
                    } else if (lowerName.endsWith(".dmg") || lowerName.endsWith(".pkg")) {
                        artifactsMap["macos-universal"] = UpdateArtifact(url = downloadUrl)
                        artifactsMap["macos"] = UpdateArtifact(url = downloadUrl)
                    } else if (lowerName.endsWith(".deb")) {
                        artifactsMap["linux-deb"] = UpdateArtifact(url = downloadUrl)
                        artifactsMap["linux"] = UpdateArtifact(url = downloadUrl)
                    } else if (lowerName.endsWith(".rpm")) {
                        artifactsMap["linux-rpm"] = UpdateArtifact(url = downloadUrl)
                    } else if (lowerName.endsWith(".zip") && "wasm" in lowerName) {
                        artifactsMap["wasm"] = UpdateArtifact(url = downloadUrl)
                    }
                }
                if (!artifactsMap.containsKey("android") && artifactsMap.containsKey("android-universal")) {
                    artifactsMap["android"] = artifactsMap["android-universal"]!!
                }

                val manifest = UpdateManifest(
                    version = tagName,
                    publishedAt = publishedAt,
                    changelog = body,
                    artifacts = artifactsMap,
                )
                return@runCatching compareAndReturn(manifest, installedCode)
            }
            UpdateResult.Error("GitHub API returned HTTP ${req.status.value}")
        }.getOrElse { e ->
            UpdateResult.Error(e.message ?: "Failed to resolve update")
        }
    }

    private fun compareAndReturn(manifest: UpdateManifest, installedCode: Int): UpdateResult {
        val isNewer = if (manifest.versionCode > 0 && installedCode > 0) {
            manifest.versionCode > installedCode
        } else {
            VersionManager.isBehind(manifest.version)
        }
        return if (isNewer) UpdateResult.Available(manifest) else UpdateResult.UpToDate
    }
}
