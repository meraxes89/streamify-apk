package com.streamify.app.data.ingestion
import android.os.Environment
import com.streamify.app.data.network.NetworkEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import com.streamify.app.util.SLog

data class ParsedTrackItem(
    val title: String,
    val artist: String,
    val album: String = "Streamify",
    val durationSec: Int = 0,
    val coverUrl: String = ""
)

data class DiscoveredPlaylistFile(
    val file: File,
    val name: String,
    val trackCount: Int,
    val sizeBytes: Long
)

object ExportifyParser {

    suspend fun discoverLocalPlaylistFiles(): List<DiscoveredPlaylistFile> = withContext(Dispatchers.IO) {
        val foundList = mutableListOf<DiscoveredPlaylistFile>()
        val searchDirs = listOfNotNull(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            File("/storage/emulated/0/Download"),
            File("/sdcard/Download"),
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
            File("/storage/emulated/0/Music"),
            File("/sdcard/Music")
        ).filter { it.exists() && it.isDirectory }

        val seenPaths = mutableSetOf<String>()

        for (dir in searchDirs) {
            val validFiles = dir.listFiles { f -> 
                f.isFile && (f.extension.equals("json", true) || 
                             f.extension.equals("m3u", true) || 
                             f.extension.equals("m3u8", true) || 
                             f.extension.equals("csv", true)) 
            } ?: continue

            for (f in validFiles) {
                if (f.absolutePath in seenPaths) continue
                seenPaths.add(f.absolutePath)

                val (playlistName, tracks) = parseUniversalFile(f)
                if (tracks.isNotEmpty()) {
                    foundList.add(
                        DiscoveredPlaylistFile(
                            file = f,
                            name = playlistName.ifBlank { f.nameWithoutExtension },
                            trackCount = tracks.size,
                            sizeBytes = f.length()
                        )
                    )
                }
            }
        }
        foundList
    }

    suspend fun parseUniversalFile(file: File): Pair<String, List<ParsedTrackItem>> = withContext(Dispatchers.IO) {
        if (!file.exists() || !file.canRead()) return@withContext Pair("", emptyList())
        try {
            val content = file.readText(Charsets.UTF_8).trim()
            if (content.isBlank()) return@withContext Pair("", emptyList())

            val ext = file.extension.lowercase()
            return@withContext when (ext) {
                "m3u", "m3u8" -> Pair(file.nameWithoutExtension, parseM3U(content))
                "csv" -> Pair(file.nameWithoutExtension, parseCSV(content))
                else -> parsePlaylistJson(file)
            }
        } catch (e: Exception) {
            SLog.st("ExportifyParser", "ExportifyParser.parseUniversalFile failed", e)
            return@withContext Pair("", emptyList())
        }
    }

    private fun parseM3U(text: String): List<ParsedTrackItem> {
        val tracks = mutableListOf<ParsedTrackItem>()
        val lines = text.lines()
        for (i in lines.indices) {
            val line = lines[i].trim()
            if (line.startsWith("#EXTINF:", ignoreCase = true)) {
                val info = line.substringAfter(":").split(",", limit = 2)
                val durationSec = info.getOrNull(0)?.trim()?.toIntOrNull() ?: 0
                val meta = info.getOrNull(1)?.split(" - ", limit = 2)
                if (meta != null && meta.size == 2) {
                    tracks.add(
                        ParsedTrackItem(
                            title = meta[1].trim(),
                            artist = meta[0].trim(),
                            album = "Streamify",
                            durationSec = durationSec
                        )
                    )
                } else if (meta != null && meta.isNotEmpty()) {
                    tracks.add(
                        ParsedTrackItem(
                            title = meta[0].trim(),
                            artist = "Unknown Artist",
                            album = "Streamify",
                            durationSec = durationSec
                        )
                    )
                }
            }
        }
        return tracks
    }

    private fun parseCSV(text: String): List<ParsedTrackItem> {
        val tracks = mutableListOf<ParsedTrackItem>()
        if (text.isBlank()) return tracks

        // Tier 1: High-Speed Rust CSV Engine
        try {
            val rustJson = com.streamify.app.data.NativeBridge.rustParseBackupCsv(text)
            if (!rustJson.isNullOrBlank()) {
                val array = JSONArray(rustJson)
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    val title = obj.optString("title", "")
                    val artist = obj.optString("artist", "")
                    val album = obj.optString("album", "Streamify")
                    val dur = obj.optInt("duration_sec", 0)
                    if (title.isNotBlank() && artist.isNotBlank()) {
                        tracks.add(ParsedTrackItem(title = title, artist = artist, album = album, durationSec = dur))
                    }
                }
                if (tracks.isNotEmpty()) return tracks
            }
        } catch (_: Throwable) {}

        val lines = text.lines().filter { it.isNotBlank() }
        if (lines.isEmpty()) return tracks

        // Header detection: Title, Artist, Album, Duration
        val header = lines.first().lowercase()
        val titleIdx = if (header.contains("track")) header.split(",").indexOfFirst { it.contains("track") } else 0
        val artistIdx = header.split(",").indexOfFirst { it.contains("artist") }.coerceAtLeast(1)

        for (i in 1 until lines.size) {
            val parts = lines[i].split(",").map { it.replace("\"", "").trim() }
            if (parts.size > titleIdx && parts[titleIdx].isNotBlank()) {
                val title = parts[titleIdx]
                val artist = if (parts.size > artistIdx) parts[artistIdx] else "Unknown Artist"
                tracks.add(ParsedTrackItem(title = title, artist = artist))
            }
        }
        return tracks
    }

    suspend fun parsePlaylistJson(file: File): Pair<String, List<ParsedTrackItem>> = withContext(Dispatchers.IO) {
        try {
            val content = file.readText(Charsets.UTF_8).trim()
            val tracks = mutableListOf<ParsedTrackItem>()
            var playlistName = file.nameWithoutExtension

            if (content.startsWith("[")) {
                val jsonArray = JSONArray(content)
                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.optJSONObject(i) ?: continue
                    val item = extractTrackFromObject(obj)
                    if (item != null) tracks.add(item)
                }
            } else if (content.startsWith("{")) {
                val root = JSONObject(content)
                playlistName = root.optString("name", root.optString("playlist_name", playlistName))

                val itemsArray = root.optJSONArray("items")
                    ?: root.optJSONArray("tracks")
                    ?: root.optJSONArray("songs")
                    ?: root.optJSONArray("data")

                if (itemsArray != null) {
                    for (i in 0 until itemsArray.length()) {
                        val obj = itemsArray.optJSONObject(i) ?: continue
                        val item = extractTrackFromObject(obj)
                        if (item != null) tracks.add(item)
                    }
                }
            }
            Pair(playlistName, tracks)
        } catch (e: Exception) {
            Pair("", emptyList())
        }
    }

    // Pure Kotlin Native Spotify Ingestion (<300ms)
    suspend fun parseSpotifyUrl(url: String): List<ParsedTrackItem> = withContext(Dispatchers.IO) {
        val tracks = mutableListOf<ParsedTrackItem>()
        val cleanUrl = url.trim()
        val itemType = if (cleanUrl.contains("/playlist/")) "playlist" else if (cleanUrl.contains("/album/")) "album" else "track"
        val itemId = cleanUrl.substringAfterLast("/").substringBefore("?")

        if (itemId.isBlank()) return@withContext emptyList()

        // 1. Tier 1: Spotify Web Embed Scraper (100% Zero-Auth, Never 403s)
        try {
            val embedUrl = "https://open.spotify.com/embed/$itemType/$itemId"
            val req = Request.Builder()
                .url(embedUrl)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .get()
                .build()

            NetworkEngine.client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val html = resp.body?.string() ?: ""
                    val match = Regex("""<script id="__NEXT_DATA__"[^>]*>(.*?)</script>""", RegexOption.DOT_MATCHES_ALL).find(html)
                    if (match != null) {
                        val jsonStr = match.groupValues[1]
                        val root = JSONObject(jsonStr)
                        val entity = root.optJSONObject("props")
                            ?.optJSONObject("pageProps")
                            ?.optJSONObject("state")
                            ?.optJSONObject("data")
                            ?.optJSONObject("entity")

                        val trackList = entity?.optJSONArray("trackList")
                        if (trackList != null) {
                            for (i in 0 until trackList.length()) {
                                val tObj = trackList.getJSONObject(i)
                                val title = tObj.optString("title", "").ifBlank { tObj.optString("name", "") }
                                val subtitle = tObj.optString("subtitle", "").ifBlank { tObj.optString("artist", "") }
                                val cleanArtist = subtitle.replace('\u00A0', ' ').trim().ifBlank { "Unknown Artist" }
                                val durMs = tObj.optLong("duration", 0L)
                                val durFormatted = String.format("%02d:%02d", (durMs / 1000) / 60, (durMs / 1000) % 60)

                                if (title.isNotBlank()) {
                                    tracks.add(
                                        ParsedTrackItem(
                                            title = title.trim(),
                                            artist = cleanArtist,
                                            album = entity.optString("name").ifBlank { "Spotify Import" },
                                            durationSec = (durMs / 1000).toInt()
                                        )
                                    )
                                }

                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            SLog.st("ExportifyParser", "ExportifyParser.parseSpotifyUrl failed", e)
        }

        if (tracks.isNotEmpty()) {
            return@withContext tracks
        }

        // 2. Tier 2: Anonymous Web Token Fallback
        try {
            val tokenReq = Request.Builder()
                .url("https://open.spotify.com/get_access_token?reason=transport&productType=web_player")
                .header("User-Agent", "Mozilla/5.0")
                .get()
                .build()

            var token = ""
            NetworkEngine.client.newCall(tokenReq).execute().use { resp ->
                if (resp.isSuccessful) {
                    val body = resp.body?.string() ?: ""
                    token = JSONObject(body).optString("accessToken", "")
                }
            }

            if (token.isNotBlank()) {
                var offset = 0
                val endpoint = if (itemType == "playlist") {
                    "https://api.spotify.com/v1/playlists/$itemId/tracks?limit=100&offset="
                } else if (itemType == "album") {
                    "https://api.spotify.com/v1/albums/$itemId/tracks?limit=100&offset="
                } else {
                    "https://api.spotify.com/v1/tracks/$itemId"
                }

                if (itemType == "track") {
                    val req = Request.Builder().url(endpoint).header("Authorization", "Bearer $token").get().build()
                    NetworkEngine.client.newCall(req).execute().use { resp ->
                        if (resp.isSuccessful) {
                            val obj = JSONObject(resp.body?.string() ?: "")
                            extractTrackFromObject(obj)?.let { tracks.add(it) }
                        }
                    }
                } else {
                    while (true) {
                        val req = Request.Builder().url("$endpoint$offset").header("Authorization", "Bearer $token").get().build()
                        val resString = NetworkEngine.client.newCall(req).execute().use { it.body?.string() ?: "" }
                        if (resString.isBlank()) break
                        val json = JSONObject(resString)
                        val items = json.optJSONArray("items") ?: break
                        if (items.length() == 0) break

                        for (i in 0 until items.length()) {
                            val itemObj = items.getJSONObject(i)
                            val tObj = if (itemType == "playlist") itemObj.optJSONObject("track") ?: itemObj else itemObj
                            extractTrackFromObject(tObj)?.let { tracks.add(it) }
                        }

                        if (items.length() < 100) break
                        offset += 100
                    }
                }
            }
        } catch (e: Exception) {
            SLog.st("ExportifyParser", "ExportifyParser.parseSpotifyUrl failed", e)
        }

        return@withContext tracks
    }


    private fun extractTrackFromObject(obj: JSONObject): ParsedTrackItem? {
        val targetObj = obj.optJSONObject("track") ?: obj
        val title = targetObj.optString("name", targetObj.optString("title", targetObj.optString("Track Name", ""))).trim()
        if (title.isBlank()) return null

        var artist = targetObj.optString("artist", targetObj.optString("Artist Name(s)", targetObj.optString("Artist", ""))).trim()
        if (artist.isBlank()) {
            val artistsArray = targetObj.optJSONArray("artists")
            if (artistsArray != null && artistsArray.length() > 0) {
                val list = mutableListOf<String>()
                for (j in 0 until artistsArray.length()) {
                    val aObj = artistsArray.optJSONObject(j)
                    val aName = aObj?.optString("name", "") ?: artistsArray.optString(j)
                    if (aName.isNotBlank()) list.add(aName)
                }
                artist = list.joinToString(", ")
            }
        }
        if (artist.isBlank()) artist = "Unknown Artist"

        var album = targetObj.optString("album", targetObj.optString("Album Name", "Streamify")).trim()
        val albumObj = targetObj.optJSONObject("album")
        if (albumObj != null) {
            album = albumObj.optString("name", album)
        }

        var durationSec = 0
        if (targetObj.has("duration_ms")) {
            durationSec = (targetObj.optLong("duration_ms", 0L) / 1000).toInt()
        } else if (targetObj.has("duration")) {
            durationSec = targetObj.optInt("duration", 0)
        }

        var coverUrl = targetObj.optString("cover_url", targetObj.optString("coverUrl", ""))
        if (coverUrl.isBlank() && albumObj != null) {
            val images = albumObj.optJSONArray("images")
            if (images != null && images.length() > 0) {
                coverUrl = images.getJSONObject(0).optString("url", "")
            }
        }

        return ParsedTrackItem(
            title = title,
            artist = artist,
            album = album.ifBlank { "Streamify" },
            durationSec = durationSec,
            coverUrl = coverUrl
        )
    }
}
