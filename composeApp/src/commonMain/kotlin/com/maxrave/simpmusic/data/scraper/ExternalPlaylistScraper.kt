package com.maxrave.simpmusic.data.scraper

import com.maxrave.logger.Logger
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

class ExternalPlaylistScraper(
    private val client: HttpClient = HttpClient(CIO) {
        install(HttpTimeout) {
            requestTimeoutMillis = 20_000
            connectTimeoutMillis = 15_000
            socketTimeoutMillis = 20_000
        }
    },
) {
    private val jsonParser = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    suspend fun scrape(rawUrl: String): ScrapedPlaylist = withContext(Dispatchers.IO) {
        val url = rawUrl.trim()
        when {
            url.contains("spotify.com") || url.startsWith("spotify:") -> scrapeSpotify(url)
            url.contains("music.apple.com") -> scrapeAppleMusic(url)
            else -> throw IllegalArgumentException("Unsupported playlist URL. Please provide a Spotify or Apple Music link.")
        }
    }

    suspend fun scrapeSpotify(url: String): ScrapedPlaylist {
        val itemType = when {
            url.contains("playlist/") || url.startsWith("spotify:playlist:") -> "playlist"
            url.contains("album/") || url.startsWith("spotify:album:") -> "album"
            url.contains("track/") || url.startsWith("spotify:track:") -> "track"
            else -> "playlist"
        }
        val playlistId = when {
            url.contains("playlist/") -> url.substringAfter("playlist/").substringBefore("?").substringBefore("/")
            url.contains("album/") -> url.substringAfter("album/").substringBefore("?").substringBefore("/")
            url.contains("track/") -> url.substringAfter("track/").substringBefore("?").substringBefore("/")
            url.startsWith("spotify:playlist:") -> url.substringAfter("spotify:playlist:")
            url.startsWith("spotify:album:") -> url.substringAfter("spotify:album:")
            url.startsWith("spotify:track:") -> url.substringAfter("spotify:track:")
            else -> ""
        }
        if (playlistId.isBlank()) throw IllegalArgumentException("Invalid Spotify URL")

        val tracks = mutableListOf<ScrapedTrack>()
        var playlistName = "Imported Spotify Playlist"

        // Tier 1: Spotify Web Embed Scraper (Zero-Auth)
        try {
            val embedUrl = "https://open.spotify.com/embed/$itemType/$playlistId"
            val html = client.get(embedUrl) {
                header("User-Agent", USER_AGENT)
                header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            }.bodyAsText()

            val match = Regex("""<script id="__NEXT_DATA__"[^>]*>(.*?)</script>""", RegexOption.DOT_MATCHES_ALL).find(html)
            if (match != null) {
                val jsonStr = match.groupValues[1]
                val root = jsonParser.parseToJsonElement(jsonStr).jsonObject
                val entity = root["props"]?.jsonObject
                    ?.get("pageProps")?.jsonObject
                    ?.get("state")?.jsonObject
                    ?.get("data")?.jsonObject
                    ?.get("entity")?.jsonObject

                if (entity != null) {
                    val entityName = entity["name"]?.jsonPrimitive?.contentOrNull
                        ?: entity["title"]?.jsonPrimitive?.contentOrNull
                    if (!entityName.isNullOrBlank()) {
                        playlistName = entityName
                    }

                    val trackList = entity["trackList"]?.jsonArray
                    if (trackList != null) {
                        for (element in trackList) {
                            val tObj = element.jsonObject
                            val title = tObj["title"]?.jsonPrimitive?.contentOrNull
                                ?: tObj["name"]?.jsonPrimitive?.contentOrNull
                                ?: ""
                            val subtitle = tObj["subtitle"]?.jsonPrimitive?.contentOrNull
                                ?: tObj["artist"]?.jsonPrimitive?.contentOrNull
                                ?: ""
                            val cleanArtist = subtitle.replace('\u00A0', ' ').trim().ifBlank { "Unknown Artist" }
                            val durationSec = ((tObj["duration"]?.jsonPrimitive?.longOrNull ?: 0L) / 1000L).toInt()

                            if (title.isNotBlank()) {
                                tracks.add(
                                    ScrapedTrack(
                                        title = title.trim(),
                                        artist = cleanArtist,
                                        durationSec = durationSec,
                                    ),
                                )
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Logger.w(TAG, "Spotify embed scraping failed: ${e.message}")
        }

        // Tier 2: Anonymous Web Token Fallback & Pagination
        if (tracks.isEmpty()) {
            try {
                val tokenResponseText = client.get("https://open.spotify.com/get_access_token?reason=transport&productType=web_player") {
                    header("User-Agent", USER_AGENT)
                    header("Accept", "application/json")
                }.bodyAsText()

                val tokenJson = jsonParser.parseToJsonElement(tokenResponseText).jsonObject
                val token = tokenJson["accessToken"]?.jsonPrimitive?.contentOrNull ?: ""
                if (token.isNotBlank()) {
                    var nextUrl: String? = "https://api.spotify.com/v1/playlists/$playlistId?fields=name,tracks.items(track(name,artists(name),duration_ms)),tracks.next"

                    while (!nextUrl.isNullOrBlank() && tracks.size < 500) {
                        val apiResponse = client.get(nextUrl) {
                            header("Authorization", "Bearer $token")
                            header("User-Agent", USER_AGENT)
                        }
                        if (apiResponse.status.value in 200..299) {
                            val root = jsonParser.parseToJsonElement(apiResponse.bodyAsText()).jsonObject
                            root["name"]?.jsonPrimitive?.contentOrNull?.let {
                                if (it.isNotBlank()) playlistName = it
                            }

                            val tracksObj = root["tracks"]?.jsonObject ?: root
                            val items = tracksObj["items"]?.jsonArray
                            if (items != null) {
                                for (item in items) {
                                    val itemObj = item.jsonObject
                                    val trackObj = itemObj["track"]?.jsonObject ?: itemObj
                                    val title = trackObj["name"]?.jsonPrimitive?.contentOrNull ?: ""
                                    val artists = trackObj["artists"]?.jsonArray
                                    val artist = artists?.firstOrNull()?.jsonObject?.get("name")?.jsonPrimitive?.contentOrNull ?: "Unknown Artist"
                                    val durMs = trackObj["duration_ms"]?.jsonPrimitive?.longOrNull ?: 0L
                                    if (title.isNotBlank()) {
                                        tracks.add(
                                            ScrapedTrack(
                                                title = title,
                                                artist = artist,
                                                durationSec = (durMs / 1000L).toInt(),
                                            ),
                                        )
                                    }
                                }
                            }
                            nextUrl = tracksObj["next"]?.jsonPrimitive?.contentOrNull
                        } else {
                            nextUrl = null
                        }
                    }
                }
            } catch (e: Exception) {
                Logger.w(TAG, "Spotify Web API scraping failed: ${e.message}")
            }
        }

        if (tracks.isEmpty()) {
            throw IllegalStateException("Failed to extract tracks from Spotify link. Ensure the playlist is public.")
        }

        return ScrapedPlaylist(name = playlistName, tracks = tracks, source = PlaylistSource.SPOTIFY)
    }

    suspend fun scrapeAppleMusic(url: String): ScrapedPlaylist {
        val tracks = mutableListOf<ScrapedTrack>()
        var playlistName = "Imported Apple Music Playlist"

        try {
            val html = client.get(url) {
                header("User-Agent", USER_AGENT)
            }.bodyAsText()

            val scriptRegex = Regex("""<script\s+type="application/ld\+json"[^>]*>(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
            val matches = scriptRegex.findAll(html)

            for (m in matches) {
                val jsonStr = m.groupValues[1].trim()
                if (jsonStr.startsWith("{")) {
                    val json = jsonParser.parseToJsonElement(jsonStr).jsonObject
                    val type = json["@type"]?.jsonPrimitive?.contentOrNull ?: ""
                    if (type.contains("MusicPlaylist", ignoreCase = true) || type.contains("MusicAlbum", ignoreCase = true)) {
                        json["name"]?.jsonPrimitive?.contentOrNull?.let {
                            if (it.isNotBlank()) playlistName = it
                        }
                        val trackArray = json["track"]?.jsonArray
                        if (trackArray != null) {
                            for (tElement in trackArray) {
                                val t = tElement.jsonObject
                                val title = t["name"]?.jsonPrimitive?.contentOrNull ?: ""
                                val byArtistObj = t["byArtist"]
                                val artist = if (byArtistObj != null) {
                                    runCatching {
                                        byArtistObj.jsonObject["name"]?.jsonPrimitive?.contentOrNull
                                    }.getOrNull() ?: byArtistObj.jsonPrimitive.contentOrNull ?: "Unknown Artist"
                                } else {
                                    "Unknown Artist"
                                }
                                if (title.isNotBlank()) {
                                    tracks.add(ScrapedTrack(title = title, artist = artist))
                                }
                            }
                        }
                        if (tracks.isNotEmpty()) break
                    }
                }
            }
        } catch (e: Exception) {
            Logger.w(TAG, "Apple Music scraping failed: ${e.message}")
        }

        if (tracks.isEmpty()) {
            throw IllegalStateException("Failed to extract tracks from Apple Music link. Ensure the playlist is public.")
        }

        return ScrapedPlaylist(name = playlistName, tracks = tracks, source = PlaylistSource.APPLE_MUSIC)
    }

    companion object {
        private const val TAG = "ExternalPlaylistScraper"
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    }
}
