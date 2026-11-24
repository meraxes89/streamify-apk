package com.maxrave.simpmusic.di

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.browser.localStorage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

fun getWebLocalStorage(key: String): String? = try {
    localStorage.getItem(key)
} catch (_: Throwable) {
    null
}

fun setWebLocalStorage(key: String, value: String) {
    try {
        localStorage.setItem(key, value)
    } catch (_: Throwable) {}
}

/**
 * Resolves a file URL relative to current window.location, ensuring correct path resolution
 * whether hosted on root (e.g. localhost:8080/), custom port, or subpath (e.g. /streamify-apk/).
 */
fun resolveWebResourceUrl(filename: String): String {
    return try {
        val location = kotlinx.browser.window.location
        val pathname = location.pathname
        val dir = if (pathname.endsWith("/")) pathname else pathname.substringBeforeLast('/') + "/"
        location.origin.trimEnd('/') + dir + filename.trimStart('/')
    } catch (_: Throwable) {
        filename
    }
}

object DemoCatalogLoader {
    private const val STORAGE_KEY = "streamify_demo_catalog"
    private const val LEGACY_RELEASE_URL =
        "https://github.com/meraxes89/docs/releases/download/v1.0.0/demo-catalog.json"

    private val _catalog = MutableStateFlow<JsonObject?>(null)
    val catalog: StateFlow<JsonObject?> = _catalog

    var isLoaded: Boolean = false
        private set

    suspend fun load() {
        if (isLoaded && _catalog.value != null) return

        // 1. Try local storage cache first
        try {
            val cachedJson = getWebLocalStorage(STORAGE_KEY)
            if (!cachedJson.isNullOrBlank()) {
                val parsed = Json.parseToJsonElement(cachedJson).jsonObject
                _catalog.value = parsed
                isLoaded = true
                seedLocalStorageIfEmpty(parsed)
                println("[DemoCatalogLoader] Loaded catalog from localStorage cache")
            }
        } catch (e: Throwable) {
            println("[DemoCatalogLoader] Error reading localStorage cache: ${e.message}")
        }

        // 2. Fetch from same-origin resource, then legacy fallback URL
        val candidateUrls = listOfNotNull(
            resolveWebResourceUrl("demo-catalog.json"),
            "demo-catalog.json",
            "./demo-catalog.json",
            LEGACY_RELEASE_URL
        ).distinct()

        for (url in candidateUrls) {
            try {
                val responseText = webKtorClient.get(url).bodyAsText()
                if (responseText.isNotBlank() && responseText.startsWith("{")) {
                    val parsed = Json.parseToJsonElement(responseText).jsonObject
                    _catalog.value = parsed
                    isLoaded = true
                    setWebLocalStorage(STORAGE_KEY, responseText)
                    seedLocalStorageIfEmpty(parsed)
                    println("[DemoCatalogLoader] Successfully fetched and cached demo catalog from: $url")
                    break
                }
            } catch (e: Throwable) {
                println("[DemoCatalogLoader] Fetch from $url failed: ${e.message}")
            }
        }
    }

    private fun seedLocalStorageIfEmpty(root: JsonObject) {
        val seedLib = root["seedLibrary"]?.jsonObject ?: return

        // Seed songs if streamify_songs is empty
        val existingSongs = getWebLocalStorage("streamify_songs")
        if (existingSongs.isNullOrBlank() || existingSongs == "[]") {
            val songsArr = seedLib["songs"]?.jsonArray
            if (songsArr != null && songsArr.isNotEmpty()) {
                setWebLocalStorage("streamify_songs", songsArr.toString())
                println("[DemoCatalogLoader] Seeded ${songsArr.size} songs into localStorage")
            }
        }

        // Seed playlists if streamify_local_playlists is empty
        val existingPlaylists = getWebLocalStorage("streamify_local_playlists")
        if (existingPlaylists.isNullOrBlank() || existingPlaylists == "[]") {
            val plArr = seedLib["playlists"]?.jsonArray
            if (plArr != null && plArr.isNotEmpty()) {
                val storedPlaylists = mutableListOf<StoredWebPlaylist>()
                var idCounter = 1L
                for (elem in plArr) {
                    val obj = elem.jsonObject
                    val title = obj["title"]?.jsonPrimitive?.content ?: "Playlist"
                    val tracks = obj["tracks"]?.jsonArray?.mapNotNull { it.jsonObject["videoId"]?.jsonPrimitive?.content } ?: emptyList()
                    val thumb = obj["tracks"]?.jsonArray?.firstOrNull()?.jsonObject?.get("thumbnails")?.jsonPrimitive?.content
                    storedPlaylists.add(
                        StoredWebPlaylist(
                            id = idCounter++,
                            title = title,
                            thumbnail = thumb,
                            tracks = tracks
                        )
                    )
                }
                setWebLocalStorage("streamify_local_playlists", Json.encodeToString(storedPlaylists))
                println("[DemoCatalogLoader] Seeded ${storedPlaylists.size} playlists into localStorage")
            }
        }
    }

    fun getHomeJson(): JsonObject? = _catalog.value?.get("home")?.jsonObject
    fun getSearchQueries(): JsonObject? = _catalog.value?.get("search")?.jsonObject?.get("results")?.jsonObject
    fun getSearchSuggestions(): JsonObject? = _catalog.value?.get("search")?.jsonObject?.get("suggestions")?.jsonObject
    fun getBrowseItem(id: String): JsonObject? = _catalog.value?.get("browse")?.jsonObject?.get(id)?.jsonObject
    fun getPlayerItem(videoId: String): JsonObject? = _catalog.value?.get("player")?.jsonObject?.get(videoId)?.jsonObject
    fun getAnalytics(): JsonObject? = _catalog.value?.get("analytics")?.jsonObject
    fun getRootCatalog(): JsonObject? = _catalog.value
}
