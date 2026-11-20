package com.streamify.app.data.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import kotlin.math.roundToLong

/**
 * Multi-provider synchronized lyrics resolver with a strict same-song identity gate.
 *
 * Resolution hierarchy (all network results are verified against the input track
 * metadata BEFORE being accepted, so a different song's lyrics can never win):
 *
 *   Tier 0: YouTube Music ATV timed lyrics for the EXACT pinned videoId
 *           (0.00s drift — timed to the actual audio being played)
 *   Tier 1: Parallel verified race:
 *           1. Musixmatch syllable RichSync (word-by-word karaoke)
 *           2. YouTube Music ATV timed lyrics (title-verified candidates)
 *           3. Musixmatch line-level subtitles
 *           4. LRCLIB synced lyrics (exact + fuzzy, metadata verified)
 *           5. NetEase synced lyrics (metadata verified)
 *   Tier 2: Verified plain lyrics → synthesized even-distribution timeline
 */
object LyricsResolver {

    private const val USER_AGENT_DESKTOP = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    private const val ANDROID_YTM_UA = "com.google.android.apps.youtube.music/7.21.50 (Linux; U; Android 14)"
    private const val YTM_KEY = "AIzaSyC1xlRQImGslL28Q8HqTqD_o-w-r2Q_Z4"
    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    private val VIDEO_ID_REGEX = Regex("^[a-zA-Z0-9_-]{11}$")

    @Volatile
    private var cachedMxmToken: String? = null

    // =====================================================================
    // 1. UNIVERSAL METADATA SANITIZERS (PLAN 23 / 24)
    // =====================================================================
    private fun cleanTitle(rawTitle: String): String {
        if (rawTitle.isBlank()) return ""
        var title = rawTitle

        // Split compound titles like "Title – Artist" or "Artist - Title"
        val delimiters = listOf(" – ", " — ", " - ", " // ", " | ")
        for (delim in delimiters) {
            if (title.contains(delim)) {
                val parts = title.split(delim)
                if (parts.size == 2) {
                    title = parts[0].trim()
                }
            }
        }

        // Strip parenthetical and bracket noise
        val noiseRegex = Regex(
            """(?i)\((?:official|audio|video|remastered|remaster|radio edit|edit|deluxe|version|feat\.|feat|ft\.|with|bonus|live|acoustic|anniversary|lyrics|lyric video|hd|4k|mv|topic|vevo).*?\)|\[.*?\]"""
        )
        title = noiseRegex.replace(title, "").trim()
        return title.ifBlank { rawTitle.trim() }
    }

    private fun cleanArtist(rawArtist: String): String {
        if (rawArtist.isBlank()) return ""
        var artist = rawArtist
            .replace(Regex("""(?i)(\s*-\s*Topic|\s*VEVO|\s*Official)"""), "")
            .replace(Regex("""(?i)(feat\.|ft\.).*"""), "")
            .split(',')[0]
            .trim()
        return artist.ifBlank { rawArtist.trim() }
    }

    /** Public sanitizer so every subsystem derives identical cache keys from raw metadata. */
    fun sanitizeCacheKeyTitle(rawTitle: String): String = cleanTitle(rawTitle).lowercase().trim()

    /** Public sanitizer so every subsystem derives identical cache keys from raw metadata. */
    fun sanitizeCacheKeyArtist(rawArtist: String): String = cleanArtist(rawArtist).lowercase().trim()

    /** Strict videoId format validation — rejects garbage like numeric DB ids ("42"). */
    fun isValidVideoId(videoId: String?): Boolean {
        return !videoId.isNullOrBlank() && VIDEO_ID_REGEX.matches(videoId)
    }

    // =====================================================================
    // 2. SAME-SONG IDENTITY VERIFICATION GATE
    // =====================================================================

    /**
     * Verifies a candidate track title returned by a provider actually refers to the
     * requested song. Uses exact cleaned equality first, then bounded fuzzy similarity.
     */
    private fun candidateTitleMatches(inputCleanTitle: String, rawCandidateTitle: String?): Boolean {
        if (rawCandidateTitle.isNullOrBlank()) return false
        val cand = cleanTitle(rawCandidateTitle).lowercase().trim()
        val input = inputCleanTitle.lowercase().trim()
        if (cand.isEmpty() || input.isEmpty()) return false
        if (cand == input) return true

        val similarity = com.streamify.app.data.discovery.FuzzyTitleMatcher.calculateSimilarity(input, cand)
        return similarity >= 0.72
    }

    /** Verifies the candidate artist refers to the requested artist (substring tolerant). */
    private fun candidateArtistMatches(inputCleanArtist: String, rawCandidateArtist: String?): Boolean {
        if (inputCleanArtist.isBlank()) return true
        if (rawCandidateArtist.isNullOrBlank()) return false
        val cand = cleanArtist(rawCandidateArtist).lowercase().trim()
        val input = inputCleanArtist.lowercase().trim()
        if (cand.isEmpty() || input.isEmpty()) return true
        return cand == input || cand.contains(input) || input.contains(cand)
    }

    /** Duration gate (±15s). Unknown durations on either side never reject. */
    private fun candidateDurationMatches(inputDurationSec: Int, candidateDurationSec: Long, toleranceSec: Long = 15L): Boolean {
        if (inputDurationSec <= 0 || candidateDurationSec <= 0) return true
        return kotlin.math.abs(candidateDurationSec - inputDurationSec.toLong()) <= toleranceSec
    }

    // =====================================================================
    // 3. MAIN ENTRYPOINT
    // =====================================================================
    suspend fun fetchSyncedLyrics(
        title: String,
        artist: String,
        durationSec: Int = 0,
        videoId: String = ""
    ): String? = withContext(Dispatchers.IO) {
        val sanitizedTitle = cleanTitle(title)
        val sanitizedArtist = cleanArtist(artist)

        if (sanitizedTitle.isBlank()) return@withContext null

        // -----------------------------------------------------------------
        // TIER 0: Exact pinned video identity.
        // When we KNOW which video is playing, its ATV timed lyrics are the
        // ground truth (0.00s drift against the exact audio stream in use).
        // Garbage ids (e.g. numeric DB row ids) are rejected by format check.
        // -----------------------------------------------------------------
        if (isValidVideoId(videoId)) {
            val exactLyrics = withTimeoutOrNull(4500L) { tryExtractYtmTimedLyrics(videoId, null) }
            if (!exactLyrics.isNullOrBlank() && exactLyrics.contains("[")) {
                return@withContext exactLyrics
            }
        }

        // -----------------------------------------------------------------
        // TIER 1: Parallel verified provider race.
        // -----------------------------------------------------------------
        var mxmResult: String? = null
        var ytmResult: String? = null
        var lrcSynced: String? = null
        var lrcPlain: String? = null
        var netEaseResult: String? = null

        coroutineScope {
            val mxmDeferred = async {
                withTimeoutOrNull(3500L) { fetchMusixmatchVerified(sanitizedTitle, sanitizedArtist, durationSec) }
            }
            val ytmDeferred = async {
                withTimeoutOrNull(4000L) { fetchYouTubeMusicTimedLyrics(sanitizedTitle, sanitizedArtist) }
            }
            val lrcDeferred = async {
                withTimeoutOrNull(2500L) { fetchLrclibVerified(sanitizedTitle, sanitizedArtist, durationSec) }
            }
            val netEaseDeferred = async {
                withTimeoutOrNull(3000L) { fetchNetEaseVerified(sanitizedTitle, sanitizedArtist, durationSec) }
            }

            mxmResult = mxmDeferred.await()
            ytmResult = ytmDeferred.await()
            val lrcHit = lrcDeferred.await()
            lrcSynced = lrcHit?.synced
            lrcPlain = lrcHit?.plain
            netEaseResult = netEaseDeferred.await()
        }

        // Verified hierarchy:
        // 1. Musixmatch Syllable RichSync (Word-by-word karaoke)
        mxmResult?.takeIf { it.contains("<") }?.let { return@withContext it }
        // 2. YouTube Music TimedLyrics (line-level, 0.00s video sync)
        ytmResult?.takeIf { it.contains("[") }?.let { return@withContext it }
        // 3. Musixmatch Line-level subtitles
        mxmResult?.takeIf { it.contains("[") }?.let { return@withContext it }
        // 4. LRCLIB synced
        lrcSynced?.takeIf { it.contains("[") }?.let { return@withContext it }
        // 5. NetEase synced
        netEaseResult?.takeIf { it.contains("[") }?.let { return@withContext it }
        // 6. Plain text fallback → synthesize an evenly distributed synced timeline
        lrcPlain?.takeIf { it.length > 20 }?.let { return@withContext synthesizeSyncedLyricsFromPlain(it, durationSec) }

        return@withContext null
    }

    // =====================================================================
    // 4. YOUTUBE MUSIC CANONICAL ATV SWEEP ENGINE (PLAN 23 / 24)
    // =====================================================================
    private fun fetchYouTubeMusicTimedLyrics(title: String, artist: String): String? {
        try {
            val candidates = resolveTopAtvCandidates(title, artist)
            for (vid in candidates) {
                val timedLrc = tryExtractYtmTimedLyrics(vid, title)
                if (!timedLrc.isNullOrBlank()) {
                    return timedLrc
                }
            }
        } catch (_: Throwable) {
            // Ignore single provider error
        }
        return null
    }

    private fun resolveTopAtvCandidates(title: String, artist: String): List<String> {
        val ids = mutableListOf<String>()
        try {
            val searchUrl = "https://music.youtube.com/youtubei/v1/search?key=$YTM_KEY&prettyPrint=false"
            val payload = JSONObject().apply {
                put("context", JSONObject().apply {
                    put("client", JSONObject().apply {
                        put("clientName", "ANDROID_MUSIC")
                        put("clientVersion", "7.21.50")
                        put("androidSdkVersion", 34)
                        put("hl", "en")
                        put("gl", "US")
                    })
                })
                put("query", "$title $artist")
                put("params", "EgWKAQIIAWoQEAMQBBAJEAoQBRAREBAQFQ%3D%3D") // Filter: Songs (ATVs)
            }

            val request = Request.Builder()
                .url(searchUrl)
                .header("User-Agent", ANDROID_YTM_UA)
                .header("X-YouTube-Client-Name", "21")
                .header("X-YouTube-Client-Version", "7.21.50")
                .header("Content-Type", "application/json")
                .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()

            NetworkEngine.client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return ids
                val body = response.body?.string() ?: return ids
                val json = JSONObject(body)

                // 1. Check musicCardShelfRenderer (Top result card)
                try {
                    val tabs = json.optJSONObject("contents")
                        ?.optJSONObject("tabbedSearchResultsRenderer")
                        ?.optJSONArray("tabs")
                    val contents = tabs?.optJSONObject(0)
                        ?.optJSONObject("tabRenderer")
                        ?.optJSONObject("content")
                        ?.optJSONObject("sectionListRenderer")
                        ?.optJSONArray("contents")

                    val cardShelf = contents?.optJSONObject(0)?.optJSONObject("musicCardShelfRenderer")
                    val topId = cardShelf?.optJSONObject("onTap")
                        ?.optJSONObject("watchEndpoint")
                        ?.optString("videoId", "")
                    if (!topId.isNullOrBlank() && !ids.contains(topId)) {
                        ids.add(topId)
                    }

                    // 2. Dual-Shelf Fallback: Check musicShelfRenderer items (Recommendation 1 of Plan 24)
                    for (cIdx in 0 until (contents?.length() ?: 0)) {
                        val shelf = contents?.optJSONObject(cIdx)?.optJSONObject("musicShelfRenderer")
                        val items = shelf?.optJSONArray("contents")
                        if (items != null) {
                            for (i in 0 until items.length().coerceAtMost(3)) {
                                val item = items.optJSONObject(i)?.optJSONObject("musicResponsiveListItemRenderer")
                                val vid = item?.optJSONObject("playlistItemData")?.optString("videoId", "")
                                    ?: item?.optJSONObject("doubleTapEndpoint")?.optJSONObject("watchEndpoint")?.optString("videoId", "")
                                if (!vid.isNullOrBlank() && !ids.contains(vid)) {
                                    ids.add(vid)
                                }
                            }
                        }
                    }
                } catch (_: Throwable) {}

                // Fallback scan if tree was different
                if (ids.isEmpty()) {
                    val matcher = Regex(""""videoId"\s*:\s*"([a-zA-Z0-9_-]{11})"""").findAll(body)
                    for (m in matcher) {
                        val vid = m.groupValues[1]
                        if (!ids.contains(vid)) {
                            ids.add(vid)
                            if (ids.size >= 3) break
                        }
                    }
                }
            }
        } catch (_: Throwable) {}
        return ids.take(3)
    }

    /**
     * Extracts YTM timed lyrics for one video.
     *
     * @param expectedTitle when non-null, the video's header title (parsed from the
     *        /next response) must match this title before lyrics are accepted.
     *        When null (Tier 0 exact-video mode) the video identity is already
     *        trusted and verification is skipped.
     */
    private fun tryExtractYtmTimedLyrics(videoId: String, expectedTitle: String?): String? {
        try {
            // Step 1: /next to resolve MPLYt_ browseId (+ optional title cross-check)
            val nextUrl = "https://music.youtube.com/youtubei/v1/next?key=$YTM_KEY&prettyPrint=false"
            val nextPayload = JSONObject().apply {
                put("context", JSONObject().apply {
                    put("client", JSONObject().apply {
                        put("clientName", "ANDROID_MUSIC")
                        put("clientVersion", "7.21.50")
                        put("androidSdkVersion", 34)
                        put("hl", "en")
                        put("gl", "US")
                    })
                })
                put("videoId", videoId)
                put("isAudioOnly", true)
            }

            val nextReq = Request.Builder()
                .url(nextUrl)
                .header("User-Agent", ANDROID_YTM_UA)
                .header("X-YouTube-Client-Name", "21")
                .header("X-YouTube-Client-Version", "7.21.50")
                .post(nextPayload.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()

            var lyricBrowseId: String? = null
            NetworkEngine.client.newCall(nextReq).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val body = resp.body?.string() ?: return null
                val json = JSONObject(body)

                // Same-song guard: reject videos whose on-screen title differs from the query
                if (expectedTitle != null) {
                    val headerRenderer = findFirstJsonObject(json, "musicResponsiveHeaderRenderer")
                    val headerText = headerRenderer
                        ?.optJSONObject("title")?.optJSONArray("runs")
                        ?.optJSONObject(0)?.optString("text", "")
                    if (!headerText.isNullOrBlank() && !candidateTitleMatches(expectedTitle, headerText)) {
                        return null
                    }
                }

                val tabs = json.optJSONObject("contents")
                    ?.optJSONObject("singleColumnMusicWatchNextResultsRenderer")
                    ?.optJSONObject("tabbedRenderer")
                    ?.optJSONObject("watchNextTabbedResultsRenderer")
                    ?.optJSONArray("tabs")
                    ?: json.optJSONObject("contents")
                        ?.optJSONObject("twoColumnBrowseResultsRenderer")
                        ?.optJSONArray("tabs")

                if (tabs != null) {
                    for (i in 0 until tabs.length()) {
                        val tab = tabs.optJSONObject(i)?.optJSONObject("tabRenderer")
                        val endpoint = tab?.optJSONObject("endpoint")?.optJSONObject("browseEndpoint")?.optString("browseId", "") ?: ""
                        val title = tab?.optString("title", "") ?: ""
                        if (title.contains("LYRIC", ignoreCase = true) || endpoint.startsWith("MPLYt_")) {
                            lyricBrowseId = endpoint
                            break
                        }
                    }
                }
            }

            if (lyricBrowseId.isNullOrBlank()) return null

            // Step 2: /browse to extract timedLyricsData model
            val browseUrl = "https://music.youtube.com/youtubei/v1/browse?key=$YTM_KEY&prettyPrint=false"
            val browsePayload = JSONObject().apply {
                put("context", JSONObject().apply {
                    put("client", JSONObject().apply {
                        put("clientName", "ANDROID_MUSIC")
                        put("clientVersion", "7.21.50")
                        put("androidSdkVersion", 34)
                        put("hl", "en")
                        put("gl", "US")
                    })
                })
                put("browseId", lyricBrowseId)
            }

            val browseReq = Request.Builder()
                .url(browseUrl)
                .header("User-Agent", ANDROID_YTM_UA)
                .header("X-YouTube-Client-Name", "21")
                .header("X-YouTube-Client-Version", "7.21.50")
                .post(browsePayload.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()

            NetworkEngine.client.newCall(browseReq).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val body = resp.body?.string() ?: return null
                val json = JSONObject(body)

                // Search for timedLyricsData in json tree
                val timedData = findTimedLyricsInJson(json)
                if (timedData != null && timedData.length() > 0) {
                    val sb = StringBuilder()
                    for (i in 0 until timedData.length()) {
                        val item = timedData.optJSONObject(i) ?: continue
                        val cue = item.optJSONObject("cueRange")
                        val startMsStr = cue?.optString("startTimeMilliseconds", "0") ?: "0"
                        val startMs = startMsStr.toLongOrNull() ?: 0L
                        val text = item.optString("lyricLine", "").trim()
                        if (text.isNotBlank()) {
                            val mm = startMs / 60000
                            val ss = (startMs % 60000) / 1000
                            val xx = (startMs % 1000) / 10
                            sb.append(String.format(java.util.Locale.US, "[%02d:%02d.%02d]%s\n", mm, ss, xx, text))
                        }
                    }
                    val lrcResult = sb.toString().trim()
                    if (lrcResult.isNotBlank()) return lrcResult
                }
            }
        } catch (_: Throwable) {}
        return null
    }

    private fun findTimedLyricsInJson(obj: Any?): JSONArray? {
        if (obj is JSONObject) {
            if (obj.has("timedLyricsData")) {
                return obj.optJSONArray("timedLyricsData")
            }
            val keys = obj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val res = findTimedLyricsInJson(obj.opt(k))
                if (res != null) return res
            }
        } else if (obj is JSONArray) {
            for (i in 0 until obj.length()) {
                val res = findTimedLyricsInJson(obj.opt(i))
                if (res != null) return res
            }
        }
        return null
    }

    /** Recursively finds the first JSONObject stored under the given key. */
    private fun findFirstJsonObject(obj: Any?, key: String): JSONObject? {
        if (obj is JSONObject) {
            val direct = obj.optJSONObject(key)
            if (direct != null) return direct
            val keys = obj.keys()
            while (keys.hasNext()) {
                val res = findFirstJsonObject(obj.opt(keys.next()), key)
                if (res != null) return res
            }
        } else if (obj is JSONArray) {
            for (i in 0 until obj.length()) {
                val res = findFirstJsonObject(obj.opt(i), key)
                if (res != null) return res
            }
        }
        return null
    }

    // =====================================================================
    // 5. MUSIXMATCH VERIFIED ENGINE (PLAN 23 / 24 HARDENED)
    // =====================================================================
    private fun getMusixmatchToken(): String? {
        cachedMxmToken?.let { return it }
        try {
            val url = "https://apic-desktop.musixmatch.com/ws/1.1/token.get?app_id=web-desktop-app-v1.0"
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT_DESKTOP)
                .get()
                .build()

            NetworkEngine.client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val body = resp.body?.string() ?: ""
                    val json = JSONObject(body)
                    val token = json.optJSONObject("message")?.optJSONObject("body")?.optString("user_token", "")
                    if (!token.isNullOrBlank() && token != "Upgrade.me") {
                        cachedMxmToken = token
                        return token
                    }
                }
            }
        } catch (_: Throwable) {}
        return null
    }

    /**
     * Identity-safe Musixmatch pipeline:
     * 1. track.search returns full metadata WITH each candidate, so every accepted
     *    track_id is verified (title + artist + duration) BEFORE any lyric download.
     * 2. RichSync (syllable) preferred, line-level LRC subtitles as fallback.
     * The legacy unverified blind page_size=1 match has been removed entirely.
     */
    private fun fetchMusixmatchVerified(title: String, artist: String, durationSec: Int): String? {
        try {
            val token = getMusixmatchToken() ?: return null
            val qTrack = URLEncoder.encode(title, "UTF-8")
            val qArtist = URLEncoder.encode(artist, "UTF-8")

            val searchUrl = "https://apic-desktop.musixmatch.com/ws/1.1/track.search?" +
                    "format=json&page_size=10&s_track_rating=desc&f_has_richsync=1" +
                    "&app_id=web-desktop-app-v1.0&usertoken=$token" +
                    "&q_track=$qTrack&q_artist=$qArtist"

            val req = Request.Builder()
                .url(searchUrl)
                .header("User-Agent", USER_AGENT_DESKTOP)
                .header("Authority", "apic-desktop.musixmatch.com")
                .header("Cookie", "x-mxm-token-id=$token")
                .get()
                .build()

            var verifiedTrackId = 0L
            NetworkEngine.client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val body = resp.body?.string() ?: return null
                val json = JSONObject(body)
                val trackList = json.optJSONObject("message")?.optJSONObject("body")?.optJSONArray("track_list")
                    ?: return null

                for (i in 0 until trackList.length()) {
                    val track = trackList.optJSONObject(i)?.optJSONObject("track") ?: continue
                    val tId = track.optLong("track_id", 0L)
                    if (tId <= 0L) continue

                    val okTitle = candidateTitleMatches(title, track.optString("track_name", ""))
                    val okArtist = candidateArtistMatches(artist, track.optString("artist_name", ""))
                    val okDuration = candidateDurationMatches(durationSec, track.optLong("track_length", 0L))
                    if (okTitle && okArtist && okDuration) {
                        verifiedTrackId = tId
                        break
                    }
                }
            }

            if (verifiedTrackId <= 0L) return null

            // 1. Syllable-Level RichSync
            fetchMusixmatchRichsync(token, verifiedTrackId)?.let { return it }

            // 2. Line-Level subtitle fallback
            return fetchMusixmatchSubtitles(token, verifiedTrackId)
        } catch (_: Throwable) {}
        return null
    }

    private fun fetchMusixmatchRichsync(token: String, trackId: Long): String? {
        return try {
            val richUrl = "https://apic-desktop.musixmatch.com/ws/1.1/track.richsync.get?" +
                    "format=json&subtitle_format=mxm&app_id=web-desktop-app-v1.0&usertoken=$token&track_id=$trackId"
            val req = Request.Builder().url(richUrl).header("User-Agent", USER_AGENT_DESKTOP).get().build()
            NetworkEngine.client.newCall(req).execute().use { rResp ->
                if (!rResp.isSuccessful) return@use null
                val rBody = rResp.body?.string() ?: return@use null
                val rawRich = JSONObject(rBody)
                    .optJSONObject("message")?.optJSONObject("body")
                    ?.optJSONObject("richsync")?.optString("richsync_body", "")
                if (!rawRich.isNullOrBlank()) convertMxmRichsyncToSyllableLrc(rawRich) else null
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun fetchMusixmatchSubtitles(token: String, trackId: Long): String? {
        return try {
            val subUrl = "https://apic-desktop.musixmatch.com/ws/1.1/track.subtitles.get?" +
                    "format=json&subtitle_format=lrc&app_id=web-desktop-app-v1.0&usertoken=$token&track_id=$trackId"
            val req = Request.Builder().url(subUrl).header("User-Agent", USER_AGENT_DESKTOP).get().build()
            NetworkEngine.client.newCall(req).execute().use { sResp ->
                if (!sResp.isSuccessful) return@use null
                val sBody = sResp.body?.string() ?: return@use null
                val subList = JSONObject(sBody)
                    .optJSONObject("message")?.optJSONObject("body")
                    ?.optJSONArray("subtitle_list") ?: return@use null
                if (subList.length() == 0) return@use null
                val subBody = subList.optJSONObject(0)?.optJSONObject("subtitle")?.optString("subtitle_body", "")
                if (!subBody.isNullOrBlank() && subBody.contains("[") && subBody.contains("]")) subBody else null
            }
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Converts Musixmatch RichSync JSON array to Syllable-Tagged LRC string with explicit rounding (Plan 24 Recommendation 3)
     * Output format: [00:12.45]<00:12.45>Word1 <00:12.80>Word2
     */
    private fun convertMxmRichsyncToSyllableLrc(richsyncJson: String): String? {
        try {
            val array = JSONArray(richsyncJson)
            if (array.length() == 0) return null

            val sb = StringBuilder()
            for (i in 0 until array.length()) {
                val lineObj = array.optJSONObject(i) ?: continue
                val tsSec = lineObj.optDouble("ts", 0.0)
                val lineStartMs = (tsSec * 1000.0).roundToLong()

                val lMin = (lineStartMs / 60000).toInt()
                val lSec = ((lineStartMs % 60000) / 1000).toInt()
                val lMs = ((lineStartMs % 1000) / 10).toInt()

                sb.append(String.format(java.util.Locale.US, "[%02d:%02d.%02d]", lMin, lSec, lMs))

                val wordsArray = lineObj.optJSONArray("l")
                if (wordsArray != null && wordsArray.length() > 0) {
                    for (w in 0 until wordsArray.length()) {
                        val wordObj = wordsArray.optJSONObject(w) ?: continue
                        val wordText = wordObj.optString("c", "")
                        val offsetSec = wordObj.optDouble("o", 0.0)
                        val wordStartMs = lineStartMs + (offsetSec * 1000.0).roundToLong()

                        val wMin = (wordStartMs / 60000).toInt()
                        val wSec = ((wordStartMs % 60000) / 1000).toInt()
                        val wMs = ((wordStartMs % 1000) / 10).toInt()

                        sb.append(String.format(java.util.Locale.US, "<%02d:%02d.%02d>%s", wMin, wSec, wMs, wordText))
                    }
                } else {
                    val lineText = lineObj.optString("x", "")
                    sb.append(lineText)
                }
                sb.append("\n")
            }
            val result = sb.toString().trim()
            return if (result.isNotBlank()) result else null
        } catch (_: Throwable) {
            return null
        }
    }

    // =====================================================================
    // 6. LRCLIB & NETEASE VERIFIED FALLBACKS
    // =====================================================================
    private class LrcLibHit(val synced: String?, val plain: String?)

    /**
     * LRCLIB resolver with metadata verification on both endpoints.
     * /api/get echoes matched trackName/artistName/duration — mismatching responses
     * are rejected and the verified /api/search pass runs instead.
     */
    private fun fetchLrclibVerified(title: String, artist: String, durationSec: Int): LrcLibHit? {
        try {
            // ---- Pass 1: exact endpoint ----
            var url = "https://lrclib.net/api/get?track_name=${URLEncoder.encode(title, "UTF-8")}&artist_name=${URLEncoder.encode(artist, "UTF-8")}"
            if (durationSec > 0) {
                url += "&duration=$durationSec"
            }

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT_DESKTOP)
                .get()
                .build()

            NetworkEngine.client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string()
                    if (!body.isNullOrBlank()) {
                        val json = JSONObject(body)
                        if (verifyLrclibEntry(json, title, artist, durationSec)) {
                            val synced = json.optString("syncedLyrics", "")
                            if (synced.isNotBlank() && synced.contains("[")) return LrcLibHit(synced, null)
                            val plain = json.optString("plainLyrics", "")
                            if (plain.isNotBlank() && plain.length > 20) return LrcLibHit(null, plain)
                        }
                    }
                }
            }

            // ---- Pass 2: verified search endpoint ----
            val q = URLEncoder.encode("$title $artist", "UTF-8")
            val searchUrl = "https://lrclib.net/api/search?q=$q"

            val searchRequest = Request.Builder()
                .url(searchUrl)
                .header("User-Agent", USER_AGENT_DESKTOP)
                .get()
                .build()

            NetworkEngine.client.newCall(searchRequest).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body?.string() ?: return null
                val array = JSONArray(body)

                var plainBackup: String? = null
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    if (!verifyLrclibEntry(item, title, artist, durationSec)) continue

                    val synced = item.optString("syncedLyrics", "")
                    if (synced.isNotBlank() && synced.contains("[")) return LrcLibHit(synced, null)

                    val plain = item.optString("plainLyrics", "")
                    if (plainBackup.isNullOrBlank() && plain.isNotBlank() && plain.length > 20) {
                        plainBackup = plain
                    }
                }
                if (plainBackup != null) return LrcLibHit(null, plainBackup)
            }
        } catch (_: Exception) {}
        return null
    }

    private fun verifyLrclibEntry(entry: JSONObject, title: String, artist: String, durationSec: Int): Boolean {
        val okTitle = candidateTitleMatches(title, entry.optString("trackName", ""))
        val okArtist = candidateArtistMatches(artist, entry.optString("artistName", ""))
        val okDuration = candidateDurationMatches(durationSec, entry.optLong("duration", 0L))
        val instrumental = entry.optBoolean("instrumental", false)
        return okTitle && okArtist && okDuration && !instrumental
    }

    /** NetEase resolver: verifies name/artist/duration from the search payload before fetching lyrics. */
    private fun fetchNetEaseVerified(title: String, artist: String, durationSec: Int): String? {
        try {
            val q = URLEncoder.encode("$title $artist", "UTF-8")
            val searchUrl = "https://music.163.com/api/search/get/web?s=$q&type=1&offset=0&total=true&limit=5"

            val reqSearch = Request.Builder()
                .url(searchUrl)
                .header("User-Agent", USER_AGENT_DESKTOP)
                .header("Referer", "https://music.163.com/")
                .get()
                .build()

            NetworkEngine.client.newCall(reqSearch).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val body = resp.body?.string() ?: return null
                val root = JSONObject(body)
                val songs = root.optJSONObject("result")?.optJSONArray("songs") ?: return null

                for (i in 0 until songs.length()) {
                    val song = songs.optJSONObject(i) ?: continue
                    val songId = song.optLong("id", 0L)
                    if (songId <= 0) continue

                    val okTitle = candidateTitleMatches(title, song.optString("name", ""))
                    val candArtist = song.optJSONArray("artists")?.optJSONObject(0)?.optString("name", "")
                    val okArtist = candidateArtistMatches(artist, candArtist)
                    val okDuration = candidateDurationMatches(durationSec, song.optLong("duration", 0L) / 1000L)
                    if (!okTitle || !okArtist || !okDuration) continue

                    val lrcUrl = "https://music.163.com/api/song/lyric?os=pc&id=$songId&lv=-1&kv=-1&tv=-1"
                    val reqLrc = Request.Builder().url(lrcUrl).header("User-Agent", USER_AGENT_DESKTOP).get().build()
                    NetworkEngine.client.newCall(reqLrc).execute().use { lrcResp ->
                        if (lrcResp.isSuccessful) {
                            val lrcBody = lrcResp.body?.string()
                            if (!lrcBody.isNullOrBlank()) {
                                val lrcStr = JSONObject(lrcBody).optJSONObject("lrc")?.optString("lyric", "")
                                if (!lrcStr.isNullOrBlank() && lrcStr.contains("[")) return lrcStr
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) {}
        return null
    }

    fun synthesizeSyncedLyricsFromPlain(plainText: String, durationSec: Int): String {
        try {
            val durationMs = if (durationSec > 0) durationSec * 1000 else 180000
            val nativeJson = com.streamify.app.data.NativeBridge.rustAlignAndCompileLyrics(plainText, durationMs, null)
            if (!nativeJson.isNullOrBlank()) {
                val array = JSONArray(nativeJson)
                val sb = StringBuilder()
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    val startMs = obj.optInt("start_ms", 0)
                    val lineText = obj.optString("line_text", "")
                    val mm = startMs / 60000
                    val ss = (startMs % 60000) / 1000
                    val xx = (startMs % 1000) / 10
                    sb.append(String.format(java.util.Locale.US, "[%02d:%02d.%02d]%s\n", mm, ss, xx, lineText))
                }
                val result = sb.toString().trim()
                if (result.isNotBlank()) return result
            }
        } catch (_: Throwable) {}
        return plainText
    }
}
