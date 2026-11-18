package com.streamify.app.data.network

import com.streamify.app.viewmodel.OnlineSearchResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.zip.GZIPInputStream
import com.streamify.app.util.SLog

object iTunesSearchApi {

    private const val ITUNES_SEARCH_URL = "https://itunes.apple.com/search"

    suspend fun search(query: String, maxResults: Int = 20): List<OnlineSearchResult> = withContext(Dispatchers.IO) {
        val cleanQuery = query.trim()
        if (cleanQuery.isBlank()) return@withContext emptyList()

        try {
            val encodedQuery = URLEncoder.encode(cleanQuery, "UTF-8")
            val targetUrl = "$ITUNES_SEARCH_URL?term=$encodedQuery&media=music&entity=song&limit=$maxResults"
            val conn = (URL(targetUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 3000
                readTimeout = 3000
                setRequestProperty("User-Agent", "Mozilla/5.0")
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Accept-Encoding", "gzip, deflate")
            }

            if (conn.responseCode != 200) {
                return@withContext emptyList()
            }

            val inputStream = if ("gzip".equals(conn.contentEncoding, ignoreCase = true)) {
                GZIPInputStream(conn.inputStream)
            } else {
                conn.inputStream
            }

            val responseBody = BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8)).use { it.readText() }
            val root = JSONObject(responseBody)
            val resultsArray = root.optJSONArray("results") ?: return@withContext emptyList()

            val results = mutableListOf<OnlineSearchResult>()
            for (i in 0 until resultsArray.length()) {
                val item = resultsArray.getJSONObject(i)
                val title = item.optString("trackName", "Unknown")
                val artist = item.optString("artistName", "Unknown")
                val durationMs = item.optLong("trackTimeMillis", 0L)
                val durationSec = (durationMs / 1000).toInt()
                val rawThumb = item.optString("artworkUrl100", "")
                val hdThumb = if (rawThumb.isNotBlank()) {
                    rawThumb.replace(Regex("\\d+x\\d+bb"), "1400x1400bb").replace("100x100", "1400x1400")
                } else ""

                val trackId = item.optString("trackId", "")
                val canonicalQuery = "ytsearch:${title.trim()} ${artist.trim()}"

                results.add(
                    OnlineSearchResult(
                        title = title,
                        uploader = artist,
                        url = canonicalQuery,
                        duration = durationSec,
                        thumbnail = hdThumb
                    )
                )
            }

            return@withContext results
        } catch (e: Exception) {
            SLog.st("iTunesSearchApi", "iTunesSearchApi.search failed", e)
            return@withContext emptyList()
        }
    }

    suspend fun fetchHdCoverArt(title: String, artist: String): String? = withContext(Dispatchers.IO) {
        if (title.isBlank()) return@withContext null
        try {
            val cleanTitle = title.replace(Regex("\\(feat\\.[^)]+\\)", RegexOption.IGNORE_CASE), "").trim()
            val primaryArtist = artist.split(",", " feat.", " ft.", "&").firstOrNull()?.trim() ?: ""
            val query = "$cleanTitle $primaryArtist".trim()
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val targetUrl = "$ITUNES_SEARCH_URL?term=$encodedQuery&media=music&entity=song&limit=5"

            val conn = (URL(targetUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 3000
                readTimeout = 3000
                setRequestProperty("User-Agent", "Mozilla/5.0")
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Accept-Encoding", "gzip, deflate")
            }

            if (conn.responseCode != 200) return@withContext null

            val inputStream = if ("gzip".equals(conn.contentEncoding, ignoreCase = true)) {
                GZIPInputStream(conn.inputStream)
            } else {
                conn.inputStream
            }

            val responseBody = BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8)).use { it.readText() }
            val root = JSONObject(responseBody)
            val resultsArray = root.optJSONArray("results") ?: return@withContext null

            for (i in 0 until resultsArray.length()) {
                val item = resultsArray.getJSONObject(i)
                val trackName = item.optString("trackName", "").lowercase()
                val artistName = item.optString("artistName", "").lowercase()
                val normTargetTitle = cleanTitle.lowercase()

                if (normTargetTitle in trackName || trackName in normTargetTitle) {
                    val rawArt = item.optString("artworkUrl100", "")
                    if (rawArt.isNotBlank()) {
                        return@withContext rawArt.replace(Regex("\\d+x\\d+bb"), "1400x1400bb").replace("100x100", "1400x1400")
                    }
                }
            }
            null
        } catch (e: Exception) {
            null
        }
    }
}
