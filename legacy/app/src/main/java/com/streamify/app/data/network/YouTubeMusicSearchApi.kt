package com.streamify.app.data.network

import com.streamify.app.viewmodel.OnlineSearchResult
import com.streamify.app.viewmodel.SearchResultType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.zip.GZIPInputStream
import com.streamify.app.util.SLog

enum class SearchFilter(val param: String?, val label: String) {
    ALL(null, "All"),
    SONGS("egWKAQIIAWoMEAMQBBAJEAoQBRAV", "Songs"),
    VIDEOS("egWKAQIQAWoMEAMQBBAJEAoQBRAV", "Videos"),
    ALBUMS("egWKAQIYAWoMEAMQBBAJEAoQBRAV", "Albums"),
    ARTISTS("egWKAQIgAWoMEAMQBBAJEAoQBRAV", "Artists"),
    PLAYLISTS("egWKAQIoAWoMEAMQBBAJEAoQBRAV", "Playlists");

    companion object {
        fun fromLabel(label: String): SearchFilter {
            return entries.find { it.label.equals(label, ignoreCase = true) } ?: ALL
        }
    }
}

object SearchResultCleaner {
    private val NOISE_PATTERNS = listOf(
        Regex("(?i)\\[\\s*official\\s+(music\\s+)?video\\s*\\]"),
        Regex("(?i)\\(\\s*official\\s+(music\\s+)?video\\s*\\)"),
        Regex("(?i)\\[\\s*official\\s+audio\\s*\\]"),
        Regex("(?i)\\(\\s*official\\s+audio\\s*\\)"),
        Regex("(?i)\\[\\s*audio\\s*\\]"),
        Regex("(?i)\\(\\s*audio\\s*\\)"),
        Regex("(?i)\\[\\s*visualizer\\s*\\]"),
        Regex("(?i)\\(\\s*visualizer\\s*\\)"),
        Regex("(?i)\\[\\s*4k\\s*(remastered|uhd)?\\s*\\]"),
        Regex("(?i)\\(\\s*4k\\s*(remastered|uhd)?\\s*\\)"),
        Regex("(?i)\\[\\s*hd\\s*\\]"),
        Regex("(?i)\\(\\s*hd\\s*\\)"),
        Regex("(?i)\\[\\s*hq\\s*\\]"),
        Regex("(?i)\\(\\s*hq\\s*\\)"),
        Regex("(?i)\\|\\s*official\\s+(music\\s+)?video")
    )

    // Rejection filter for low-effort junk / modified audio
    private val JUNK_MODIFIER_REGEX = Regex(
        "(?i)(slowed\\s*(\\+|and)?\\s*reverb|slowed\\s*down|sped\\s*up|speed\\s*up|8d\\s*audio|1\\s*hour\\s*loop|10\\s*hours|bass\\s*boosted|nightcore|daycore|tiktok\\s*version|chipmunk\\s*version)"
    )

    fun isJunkModifier(title: String): Boolean {
        return JUNK_MODIFIER_REGEX.containsMatchIn(title)
    }

    fun cleanTitle(rawTitle: String): String {
        var clean = rawTitle.trim()
        for (pattern in NOISE_PATTERNS) {
            clean = pattern.replace(clean, "").trim()
        }
        clean = clean.replace(Regex("[-–—]\\s*$"), "").trim()
        clean = clean.replace(Regex("\\s{2,}"), " ").trim()
        return clean.ifBlank { rawTitle }
    }

    fun cleanUploader(rawUploader: String): String {
        var clean = rawUploader.trim()
        clean = clean.replace(Regex("(?i)\\s*-\\s*topic$"), "")
        clean = clean.replace(Regex("(?i)\\s*vevo$"), "")
        return clean.ifBlank { rawUploader }
    }
}

object YouTubeMusicSearchApi {

    private const val INNERTUBE_MUSIC_SEARCH_URL = "https://music.youtube.com/youtubei/v1/search"
    private const val INNERTUBE_YT_SEARCH_URL = "https://www.youtube.com/youtubei/v1/search"
    private const val SUGGEST_URL = "https://suggestqueries.google.com/complete/search?client=firefox&ds=yt&q="
    private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    private val JSON_MEDIA_TYPE = "application/json; charset=UTF-8".toMediaType()

    suspend fun search(
        query: String,
        filter: SearchFilter = SearchFilter.ALL,
        maxResults: Int = 30
    ): List<OnlineSearchResult> = withContext(Dispatchers.IO) {
        val cleanQuery = query.trim()
        if (cleanQuery.isBlank()) return@withContext emptyList()

        // 1. Primary: Pure-Rust RustyPipe YTM search (in-process QuickJS parsing + SQLite cache)
        if (filter == SearchFilter.ALL || filter == SearchFilter.SONGS) {
            val rustResults = try {
                val dbPath = YouTubeStreamResolver.appContext?.getDatabasePath("streamify_universal.db")?.absolutePath ?: ""
                com.streamify.app.data.NativeBridge.searchTracks(cleanQuery, maxResults, dbPath)
            } catch (t: Throwable) {
                SLog.w("YouTubeMusicSearchApi", "RustyPipe search error for '$cleanQuery': ${t.message}")
                null
            }

            if (!rustResults.isNullOrEmpty()) {
                val filtered = rustResults.filterNot { SearchResultCleaner.isJunkModifier(it.title) }
                if (filtered.isNotEmpty()) {
                    SLog.d("YouTubeMusicSearchApi", "RustyPipe search HIT for '$cleanQuery' (${filtered.size} items)")
                    return@withContext filtered
                }
            }
        }

        // 2. Fallback to YouTube Music Parametric Search
        val musicResults = executeInnertubeSearch(
            query = cleanQuery,
            endpointUrl = INNERTUBE_MUSIC_SEARCH_URL,
            isMusic = true,
            filter = filter,
            maxResults = maxResults
        )

        val filteredMusicResults = musicResults.filterNot { SearchResultCleaner.isJunkModifier(it.title) }

        if (filteredMusicResults.isNotEmpty()) {
            return@withContext filteredMusicResults
        }

        // 3. Fallback to standard YouTube Innertube search for maximum coverage
        val ytResults = executeInnertubeSearch(
            query = cleanQuery,
            endpointUrl = INNERTUBE_YT_SEARCH_URL,
            isMusic = false,
            filter = SearchFilter.ALL,
            maxResults = maxResults
        )

        return@withContext ytResults.filterNot { SearchResultCleaner.isJunkModifier(it.title) }
    }

    suspend fun fetchSearchSuggestions(query: String): List<String> = withContext(Dispatchers.IO) {
        val clean = query.trim()
        if (clean.length < 2) return@withContext emptyList()

        try {
            val url = SUGGEST_URL + URLEncoder.encode(clean, "UTF-8")
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json")
                .get()
                .build()

            NetworkEngine.client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext emptyList()
                val body = response.body?.string() ?: return@withContext emptyList()

                val jsonArray = JSONArray(body)
                if (jsonArray.length() >= 2) {
                    val suggestionsArray = jsonArray.optJSONArray(1) ?: return@withContext emptyList()
                    val suggestions = mutableListOf<String>()
                    for (i in 0 until suggestionsArray.length()) {
                        val item = suggestionsArray.optString(i, "").trim()
                        if (item.isNotBlank() && !suggestions.contains(item) && !SearchResultCleaner.isJunkModifier(item)) {
                            suggestions.add(item)
                        }
                    }
                    return@withContext suggestions.take(6)
                }
            }
        } catch (e: Exception) {
            // Non-blocking
        }
        return@withContext emptyList()
    }

    private fun executeInnertubeSearch(
        query: String,
        endpointUrl: String,
        isMusic: Boolean,
        filter: SearchFilter,
        maxResults: Int
    ): List<OnlineSearchResult> {
        try {
            val clientName = if (isMusic) "WEB_REMIX" else "WEB"
            // Release-free adaptation: versions come from FleetConfig when the
            // remote fleet-config.json carries searchClients overrides.
            val clientVersion = if (isMusic) {
                com.streamify.app.util.FleetConfig.musicSearchVersion("1.20240101.01.00")
            } else {
                com.streamify.app.util.FleetConfig.webSearchVersion("2.20240101.01.00")
            }

            val requestJson = JSONObject().apply {
                put("context", JSONObject().apply {
                    put("client", JSONObject().apply {
                        put("clientName", clientName)
                        put("clientVersion", clientVersion)
                        put("hl", "en")
                        put("gl", "US")
                    })
                })
                put("query", query)
                if (isMusic && filter.param != null) {
                    put("params", filter.param)
                }
            }

            val request = Request.Builder()
                .url(endpointUrl)
                .header("Content-Type", "application/json; charset=UTF-8")
                .header("User-Agent", USER_AGENT)
                .header("Accept", "*/*")
                .header("Accept-Encoding", "gzip, deflate")
                .header("X-YouTube-Client-Name", if (isMusic) "67" else "1")
                .header("X-YouTube-Client-Version", clientVersion)
                .header("Origin", if (isMusic) "https://music.youtube.com" else "https://www.youtube.com")
                .header("Referer", if (isMusic) "https://music.youtube.com/" else "https://www.youtube.com/")
                .post(requestJson.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()

            NetworkEngine.client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return emptyList()

                val body = response.body ?: return emptyList()
                val encoding = response.header("Content-Encoding", "")

                val responseBody = if ("gzip".equals(encoding, ignoreCase = true)) {
                    GZIPInputStream(body.byteStream()).bufferedReader(Charsets.UTF_8).use { it.readText() }
                } else {
                    body.string()
                }
                if (responseBody.isBlank()) return emptyList()

                val root = JSONObject(responseBody)
                return parseInnertubeResponse(root, maxResults)
            }
        } catch (e: Exception) {
            return emptyList()
        }
    }

    private fun parseInnertubeResponse(root: JSONObject, maxResults: Int): List<OnlineSearchResult> {
        val results = mutableListOf<OnlineSearchResult>()
        try {
            findMusicItems(root, results, maxResults)
        } catch (e: Exception) {
            SLog.st("YouTubeMusicSearchApi", "YouTubeMusicSearchApi.parseInnertubeResponse failed", e)
        }
        return results
    }

    private fun findMusicItems(obj: Any?, list: MutableList<OnlineSearchResult>, maxResults: Int) {
        if (list.size >= maxResults || obj == null) return

        when (obj) {
            is JSONObject -> {
                if (obj.has("musicCardShelfRenderer")) {
                    val cardObj = obj.getJSONObject("musicCardShelfRenderer")
                    val item = parseMusicCardShelf(cardObj)
                    if (item != null && list.none { it.url == item.url }) {
                        list.add(0, item)
                    }
                } else if (obj.has("musicResponsiveListItemRenderer")) {
                    val item = parseMusicItem(obj.getJSONObject("musicResponsiveListItemRenderer"))
                    if (item != null && list.none { it.url == item.url }) {
                        list.add(item)
                        if (list.size >= maxResults) return
                    }
                } else if (obj.has("musicTwoRowItemRenderer")) {
                    val item = parseMusicTwoRowItem(obj.getJSONObject("musicTwoRowItemRenderer"))
                    if (item != null && list.none { it.url == item.url }) {
                        list.add(item)
                        if (list.size >= maxResults) return
                    }
                } else if (obj.has("videoRenderer")) {
                    val item = parseVideoRenderer(obj.getJSONObject("videoRenderer"))
                    if (item != null && list.none { it.url == item.url }) {
                        list.add(item)
                        if (list.size >= maxResults) return
                    }
                } else if (obj.has("compactVideoRenderer")) {
                    val item = parseVideoRenderer(obj.getJSONObject("compactVideoRenderer"))
                    if (item != null && list.none { it.url == item.url }) {
                        list.add(item)
                        if (list.size >= maxResults) return
                    }
                } else {
                    val keys = obj.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        findMusicItems(obj.opt(key), list, maxResults)
                        if (list.size >= maxResults) return
                    }
                }
            }
            is JSONArray -> {
                for (i in 0 until obj.length()) {
                    findMusicItems(obj.opt(i), list, maxResults)
                    if (list.size >= maxResults) return
                }
            }
        }
    }

    private fun parseMusicCardShelf(cardObj: JSONObject): OnlineSearchResult? {
        try {
            val titleRuns = cardObj.optJSONObject("title")?.optJSONArray("runs")
            val title = titleRuns?.optJSONObject(0)?.optString("text", "") ?: ""
            if (title.isBlank()) return null

            val subtitleRuns = cardObj.optJSONObject("subtitle")?.optJSONArray("runs")
            val rawType = subtitleRuns?.optJSONObject(0)?.optString("text", "Artist") ?: "Artist"
            val uploader = if (subtitleRuns != null && subtitleRuns.length() > 2) {
                subtitleRuns.optJSONObject(2)?.optString("text", title) ?: title
            } else title

            val isArtist = rawType.contains("Artist", ignoreCase = true)
            var thumbnail = ""
            val thumbObj = cardObj.optJSONObject("thumbnail")?.optJSONObject("musicThumbnailRenderer")?.optJSONObject("thumbnail")
            val thumbsArray = thumbObj?.optJSONArray("thumbnails")
            if (thumbsArray != null && thumbsArray.length() > 0) {
                val lastThumb = thumbsArray.getJSONObject(thumbsArray.length() - 1)
                thumbnail = upgradeThumbnailResolution(lastThumb.optString("url", ""))
            }

            val navEp = cardObj.optJSONObject("title")?.optJSONArray("runs")?.optJSONObject(0)?.optJSONObject("navigationEndpoint")
            // Direct-hit first: a Top Hit SONG card carries a real 11-char videoId in
            // onTap.watchEndpoint. browseEndpoint values are entity IDs (MPREb_… albums,
            // UC… channels — 17+/24 chars) that can never resolve as streams and must
            // not shadow the videoId. Length-11 gate blocks truncated-ID false hits.
            val watchVideoId = cardObj.optJSONObject("onTap")?.optJSONObject("watchEndpoint")
                ?.optString("videoId", "")?.takeIf { it.length == 11 }
            val browseId = navEp?.optJSONObject("browseEndpoint")?.optString("browseId", "")
                ?: cardObj.optJSONObject("onTap")?.optJSONObject("watchEndpoint")?.optString("videoId", "") ?: ""

            val type = if (isArtist) SearchResultType.ARTIST else SearchResultType.SONG
            val targetUrl = when {
                watchVideoId != null -> "https://www.youtube.com/watch?v=$watchVideoId"
                isArtist -> "https://music.youtube.com/channel/$browseId"
                browseId.length == 11 -> "https://www.youtube.com/watch?v=$browseId"
                else -> "https://music.youtube.com/browse/$browseId"
            }

            return OnlineSearchResult(
                title = SearchResultCleaner.cleanTitle(title),
                uploader = SearchResultCleaner.cleanUploader(uploader),
                url = targetUrl,
                duration = 0,
                thumbnail = thumbnail,
                type = type,
                subtitle = if (isArtist) "Artist" else "Top Hit",
                browseId = browseId,
                isVerified = true
            )
        } catch (e: Exception) {
            return null
        }
    }

    private fun parseMusicTwoRowItem(itemObj: JSONObject): OnlineSearchResult? {
        try {
            val titleRuns = itemObj.optJSONObject("title")?.optJSONArray("runs")
            val title = titleRuns?.optJSONObject(0)?.optString("text", "") ?: ""
            if (title.isBlank()) return null

            val subtitleRuns = itemObj.optJSONObject("subtitle")?.optJSONArray("runs")
            val rawType = subtitleRuns?.optJSONObject(0)?.optString("text", "")?.lowercase() ?: ""
            val uploader = if (subtitleRuns != null && subtitleRuns.length() > 2) {
                subtitleRuns.optJSONObject(2)?.optString("text", "Artist") ?: "Artist"
            } else {
                subtitleRuns?.optJSONObject(0)?.optString("text", "Artist") ?: "Artist"
            }

            // Direct-hit: song rows carry their playable videoId on the watchEndpoint;
            // a browse-only reference cannot resolve as a stream.
            val watchVideoId = itemObj.optJSONObject("navigationEndpoint")?.optJSONObject("watchEndpoint")
                ?.optString("videoId", "")?.takeIf { it.length == 11 }
            val browseEp = itemObj.optJSONObject("navigationEndpoint")?.optJSONObject("browseEndpoint")
            val browseId = browseEp?.optString("browseId", "") ?: ""
            val pageType = browseEp?.optJSONObject("browseEndpointContextSupportedConfigs")
                ?.optJSONObject("browseEndpointContextMusicConfig")?.optString("pageType", "")?.uppercase() ?: ""

            val type = when {
                pageType.contains("ARTIST") || rawType.contains("artist") -> SearchResultType.ARTIST
                pageType.contains("ALBUM") || rawType.contains("album") || rawType.contains("ep") || rawType.contains("single") -> SearchResultType.ALBUM
                pageType.contains("PLAYLIST") || rawType.contains("playlist") -> SearchResultType.PLAYLIST
                else -> SearchResultType.SONG
            }

            var thumbnail = ""
            val thumbObj = itemObj.optJSONObject("thumbnailRenderer")?.optJSONObject("musicThumbnailRenderer")?.optJSONObject("thumbnail")
            val thumbsArray = thumbObj?.optJSONArray("thumbnails")
            if (thumbsArray != null && thumbsArray.length() > 0) {
                val lastThumb = thumbsArray.getJSONObject(thumbsArray.length() - 1)
                thumbnail = upgradeThumbnailResolution(lastThumb.optString("url", ""))
            }

            val targetUrl = when (type) {
                SearchResultType.ARTIST -> "https://music.youtube.com/channel/$browseId"
                SearchResultType.ALBUM -> "https://music.youtube.com/browse/$browseId"
                SearchResultType.PLAYLIST -> "https://music.youtube.com/playlist?list=${browseId.removePrefix("VL")}"
                else -> if (watchVideoId != null) "https://www.youtube.com/watch?v=$watchVideoId"
                else "https://music.youtube.com/browse/$browseId"
            }

            return OnlineSearchResult(
                title = SearchResultCleaner.cleanTitle(title),
                uploader = SearchResultCleaner.cleanUploader(uploader),
                url = targetUrl,
                duration = 0,
                thumbnail = thumbnail,
                type = type,
                subtitle = rawType.replaceFirstChar { it.uppercase() },
                browseId = browseId
            )
        } catch (e: Exception) {
            return null
        }
    }

    private fun parseMusicItem(itemObj: JSONObject): OnlineSearchResult? {
        try {
            var videoId = ""
            var title = "Unknown"
            var uploader = "Unknown"
            var duration = 0
            var thumbnail = ""

            // 1. Extract Video ID
            if (itemObj.has("playlistItemData")) {
                videoId = itemObj.optJSONObject("playlistItemData")?.optString("videoId", "") ?: ""
            }
            if (videoId.isBlank()) {
                val ep = itemObj.optJSONObject("navigationEndpoint")?.optJSONObject("watchEndpoint")
                videoId = ep?.optString("videoId", "") ?: ""
            }
            if (videoId.isBlank()) {
                val overlay = itemObj.optJSONObject("overlay")?.optJSONObject("musicItemThumbnailOverlayRenderer")
                val playNav = overlay?.optJSONObject("content")?.optJSONObject("musicPlayButtonRenderer")?.optJSONObject("playNavigationEndpoint")?.optJSONObject("watchEndpoint")
                videoId = playNav?.optString("videoId", "") ?: ""
            }

            if (videoId.isBlank()) return null

            // 2. Extract Flex Columns (Title, Artist, Duration)
            val flexColumns = itemObj.optJSONArray("flexColumns")
            if (flexColumns != null && flexColumns.length() > 0) {
                val col0 = flexColumns.optJSONObject(0)
                val runs0 = col0?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")?.optJSONObject("text")?.optJSONArray("runs")
                if (runs0 != null && runs0.length() > 0) {
                    title = runs0.getJSONObject(0).optString("text", "Unknown")
                }

                for (c in 1 until flexColumns.length()) {
                    val col = flexColumns.optJSONObject(c)
                    val runs = col?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")?.optJSONObject("text")?.optJSONArray("runs")
                    if (runs != null && runs.length() > 0) {
                        if (c == 1 && uploader == "Unknown") {
                            uploader = runs.getJSONObject(0).optString("text", "Unknown")
                        }
                        for (j in 0 until runs.length()) {
                            val textVal = runs.getJSONObject(j).optString("text", "").trim()
                            if (textVal.matches(Regex("\\d+:\\d+(:\\d+)?"))) {
                                duration = parseDurationToSeconds(textVal)
                            }
                        }
                    }
                }
            }

            // Fixed columns for duration
            val fixedColumns = itemObj.optJSONArray("fixedColumns")
            if (fixedColumns != null && fixedColumns.length() > 0) {
                for (k in 0 until fixedColumns.length()) {
                    val col = fixedColumns.optJSONObject(k)
                    val runs = col?.optJSONObject("musicResponsiveListItemFixedColumnRenderer")?.optJSONObject("text")?.optJSONArray("runs")
                    if (runs != null && runs.length() > 0) {
                        val textVal = runs.getJSONObject(0).optString("text", "").trim()
                        if (textVal.matches(Regex("\\d+:\\d+(:\\d+)?"))) {
                            duration = parseDurationToSeconds(textVal)
                        }
                    }
                }
            }

            // 3. Extract High-Res Thumbnail
            val thumbObj = itemObj.optJSONObject("thumbnail")?.optJSONObject("musicThumbnailRenderer")?.optJSONObject("thumbnail")
            val thumbsArray = thumbObj?.optJSONArray("thumbnails")
            if (thumbsArray != null && thumbsArray.length() > 0) {
                val lastThumb = thumbsArray.getJSONObject(thumbsArray.length() - 1)
                val rawUrl = lastThumb.optString("url", "")
                thumbnail = upgradeThumbnailResolution(rawUrl)
            }

            if (thumbnail.isBlank()) {
                thumbnail = "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"
            }

            val isVideo = itemObj.optJSONObject("navigationEndpoint")?.optJSONObject("watchEndpoint")
                ?.optJSONObject("watchEndpointMusicSupportedConfigs")?.optJSONObject("watchEndpointMusicConfig")
                ?.optString("musicVideoType", "")?.contains("VIDEO", ignoreCase = true) == true

            return OnlineSearchResult(
                title = SearchResultCleaner.cleanTitle(title),
                uploader = SearchResultCleaner.cleanUploader(uploader),
                url = "https://www.youtube.com/watch?v=$videoId",
                duration = duration,
                thumbnail = thumbnail,
                type = if (isVideo) SearchResultType.VIDEO else SearchResultType.SONG
            )
        } catch (e: Exception) {
            return null
        }
    }

    private fun parseVideoRenderer(videoObj: JSONObject): OnlineSearchResult? {
        try {
            val videoId = videoObj.optString("videoId", "")
            if (videoId.isBlank()) return null

            var title = "Unknown"
            val titleObj = videoObj.optJSONObject("title")
            val titleRuns = titleObj?.optJSONArray("runs")
            if (titleRuns != null && titleRuns.length() > 0) {
                title = titleRuns.getJSONObject(0).optString("text", "Unknown")
            } else if (titleObj?.has("simpleText") == true) {
                title = titleObj.optString("simpleText", "Unknown")
            }

            var uploader = "Unknown"
            val ownerObj = videoObj.optJSONObject("ownerText") ?: videoObj.optJSONObject("shortBylineText")
            val ownerRuns = ownerObj?.optJSONArray("runs")
            if (ownerRuns != null && ownerRuns.length() > 0) {
                uploader = ownerRuns.getJSONObject(0).optString("text", "Unknown")
            }

            var duration = 0
            val lengthObj = videoObj.optJSONObject("lengthText")
            val lengthText = lengthObj?.optString("simpleText", "") ?: ""
            if (lengthText.isNotBlank()) {
                duration = parseDurationToSeconds(lengthText)
            }

            var thumbnail = "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"
            val thumbObj = videoObj.optJSONObject("thumbnail")
            val thumbsArray = thumbObj?.optJSONArray("thumbnails")
            if (thumbsArray != null && thumbsArray.length() > 0) {
                val lastThumb = thumbsArray.getJSONObject(thumbsArray.length() - 1)
                val rawUrl = lastThumb.optString("url", "")
                if (rawUrl.isNotBlank()) {
                    thumbnail = upgradeThumbnailResolution(rawUrl)
                }
            }

            return OnlineSearchResult(
                title = SearchResultCleaner.cleanTitle(title),
                uploader = SearchResultCleaner.cleanUploader(uploader),
                url = "https://www.youtube.com/watch?v=$videoId",
                duration = duration,
                thumbnail = thumbnail,
                type = SearchResultType.VIDEO
            )
        } catch (e: Exception) {
            return null
        }
    }

    private fun upgradeThumbnailResolution(url: String): String {
        if (url.isBlank()) return ""
        if (url.contains("googleusercontent.com") || url.contains("ggpht.com")) {
            return if (url.contains("=")) {
                url.replace(Regex("=w\\d+-h\\d+.*"), "=w800-h800-l90-rj").replace(Regex("=s\\d+.*"), "=s800")
            } else {
                "$url=w800-h800-l90-rj"
            }
        }
        return url
    }

    private fun parseDurationToSeconds(durationStr: String): Int {
        return try {
            val parts = durationStr.split(":").map { it.trim().toIntOrNull() ?: 0 }
            if (parts.size == 2) {
                parts[0] * 60 + parts[1]
            } else if (parts.size == 3) {
                parts[0] * 3600 + parts[1] * 60 + parts[2]
            } else {
                0
            }
        } catch (e: Exception) {
            0
        }
    }
}
