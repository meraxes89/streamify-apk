package com.streamify.app.data.network

import com.streamify.app.util.FleetConfig
import com.streamify.app.util.SLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.zip.GZIPInputStream

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * ArtistClipsApi — vertical 30-second artist Clips rail scraper (Phase 3, 1)
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * Scrapes the short vertical "Clips" (YouTube Shorts-class 30s videos) that
 * appear on artist pages and inside watch-next trees. These power the
 * vertical full-bleed clips rail on Artist/Album screens with synced audio.
 *
 * Transports (both tried, client-fleet fallback on each):
 *  • /browse  — the artist channel page (Shorts / "Clips from this artist"
 *    shelf, shortsLockupViewModel + reelItemRenderer shapes).
 *  • /next    — the watch-next tree of a seed track (clipRenderer,
 *    shortsLockupViewModel inside musicCarouselShelfRenderer headers that
 *    mention "Clips").
 *
 * 🛡️ Anti-failure architecture (Phase 2 scraper pillars, reused):
 *  1. Multi-client header fallback — WEB_REMIX → ANDROID_MUSIC.
 *  2. Defensive recursive JSON traversal — the parser keys off renderer
 *     *signatures* (shortsLockupViewModel / reelItemRenderer / clipRenderer
 *     / videoRenderer carrying a short-duration badge), never absolute
 *     paths, so shape rotation degrades to fewer clips, not crashes.
 *  3. Stale-while-revalidate cache — 6h TTL, artist-keyed.
 *  4. Artist browseId resolution chain: /browse with the artist's channel
 *     id when known, else a SONGS/ARTIST search to resolve it first.
 *
 * NEVER throws: public entry points return a valid [ClipsPage] (empty when
 * nothing was scrapable).
 */
object ArtistClipsApi {

    private const val TAG = "ArtistClipsApi"

    private const val BROWSE_URL =
        "https://music.youtube.com/youtubei/v1/browse?key=AIzaSyC9XL3ZjWddXya6X74dJoCTL-WEKFD3UVk"
    private const val NEXT_URL_MUSIC =
        "https://music.youtube.com/youtubei/v1/next?key=AIzaSyC9XL3ZjWddXya6X74dJoCTL-WEKFD3UVk"
    private const val SEARCH_URL =
        "https://music.youtube.com/youtubei/v1/search?key=AIzaSyC9XL3ZjWddXya6X74dJoCTL-WEKFD3UVk"

    private const val DESKTOP_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    private const val ANDROID_MUSIC_UA = "com.google.android.apps.youtube.music/8.6.53.52 (Linux; U; Android 14) US"

    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    /** Clips rails rotate weekly — a 6h TTL serves the shelf instantly. */
    internal val swr: SwrCache by lazy { SwrCache(dir = null) }
    private const val CLIPS_CACHE_TTL_MS = 6L * 60 * 60 * 1000

    // ────────────────────────────────────────────────────────────── model

    /** One vertical clip (~30s) in the discovery rail. */
    data class ArtistClip(
        val clipId: String,
        val videoId: String,
        val title: String,
        val thumbnailUrl: String = "",
        val durationSec: Int = 30,
        val viewCountText: String = "",
        val audioSyncTrackVideoId: String? = null
    ) {
        val isVertical30s: Boolean get() = durationSec in 1..45
    }

    /** A scraped clips page for an artist. */
    data class ClipsPage(
        val artistName: String = "",
        val clips: List<ArtistClip> = emptyList(),
        val fromCache: Boolean = false
    )

    /** InnerTube client spec for the clips fleet. */
    internal data class ClipsClientSpec(
        val label: String,
        val clientName: String,
        val clientVersion: String,
        val clientHeaderId: String,
        val origin: String,
        val userAgent: String,
        val contextExtras: JSONObject.() -> Unit = {}
    )

    // ─────────────────────────────────────── public entry points (no-throw)

    /**
     * Clips rail for an artist by name (SWR-cached 6h). Resolves the artist
     * browseId through search when [browseId] is not already known.
     */
    suspend fun clipsForArtist(artistName: String, browseId: String? = null, maxClips: Int = 12): ClipsPage {
        val clean = artistName.trim()
        if (clean.isBlank() && browseId.isNullOrBlank()) return ClipsPage()
        val cacheKey = "clips_${(browseId ?: clean).lowercase()}"
        val cached = swr.swr(
            key = cacheKey,
            ttlMs = CLIPS_CACHE_TTL_MS,
            fetch = { pageToCanonicalJson(resolveClipsFleet(clean, browseId, maxClips)) }
        ) ?: return ClipsPage(artistName = clean)
        return canonicalJsonToPage(cached)
    }

    /**
     * Clips discovered from the watch-next tree of a seed track (no browseId
     * needed — used by the player-adjacent discovery rail).
     */
    suspend fun clipsForSeedTrack(videoId: String, maxClips: Int = 12): ClipsPage {
        val id = videoId.trim().takeIf { it.length == 11 } ?: return ClipsPage()
        val cached = swr.swr(
            key = "clips_seed_$id",
            ttlMs = CLIPS_CACHE_TTL_MS,
            fetch = { pageToCanonicalJson(resolveSeedClipsFleet(id, maxClips)) }
        ) ?: return ClipsPage()
        return canonicalJsonToPage(cached)
    }

    // ─────────────────────────────────────────── fleet fallback internals

    internal suspend fun resolveClipsFleet(
        artistName: String,
        browseId: String?,
        maxClips: Int
    ): ClipsPage = withContext(Dispatchers.IO) {
        var channelId = browseId?.takeIf { it.isNotBlank() }
        if (channelId == null) {
            channelId = resolveArtistChannelId(artistName)
        }
        if (channelId != null) {
            for (spec in clientFleet()) {
                val root = executeBrowse(spec, channelId, "clips") ?: continue
                val clips = ClipsResponseParser.parseBrowse(root, maxClips)
                if (clips.isNotEmpty()) return@withContext ClipsPage(artistName, clips)
            }
            // Some artists only surface clips on the main (non-shorts) tab.
            for (spec in clientFleet()) {
                val root = executeBrowse(spec, channelId, null) ?: continue
                val clips = ClipsResponseParser.parseBrowse(root, maxClips)
                if (clips.isNotEmpty()) return@withContext ClipsPage(artistName, clips)
            }
        }
        // Final fallback: watch-next tree of the artist's top track.
        if (artistName.isNotBlank()) {
            val seedId = resolveSeedVideoForArtist(artistName)
            if (seedId != null) return@withContext resolveSeedClipsFleet(seedId, maxClips)
        }
        ClipsPage(artistName)
    }

    internal suspend fun resolveSeedClipsFleet(videoId: String, maxClips: Int): ClipsPage {
        for (spec in clientFleet()) {
            val root = executeNext(spec, videoId) ?: continue
            val clips = ClipsResponseParser.parseNext(root, maxClips)
            if (clips.isNotEmpty()) return ClipsPage(clips = clips)
        }
        return ClipsPage()
    }

    internal fun clientFleet(): List<ClipsClientSpec> {
        val musicVersion = FleetConfig.musicSearchVersion("1.20240101.01.00")
        val androidMusicVersion = FleetConfig.audioTargets(
            listOf(
                FleetConfig.ClientSpec(
                    clientName = "ANDROID",
                    clientVersion = "8.6.53.52",
                    clientNumber = "21",
                    userAgent = ANDROID_MUSIC_UA,
                    deviceMake = "Google",
                    deviceModel = "Pixel 8",
                    osName = "Android",
                    osVersion = "14"
                )
            )
        ).firstOrNull { it.clientName == "ANDROID" }?.clientVersion ?: "8.6.53.52"

        return listOf(
            ClipsClientSpec(
                label = "WEB_REMIX",
                clientName = "WEB_REMIX",
                clientVersion = musicVersion,
                clientHeaderId = "67",
                origin = "https://music.youtube.com",
                userAgent = DESKTOP_UA
            ),
            ClipsClientSpec(
                label = "ANDROID_MUSIC",
                clientName = "ANDROID_MUSIC",
                clientVersion = androidMusicVersion,
                clientHeaderId = "21",
                origin = "https://music.youtube.com",
                userAgent = ANDROID_MUSIC_UA,
                contextExtras = {
                    put("androidSdkVersion", 34)
                    put("osName", "Android")
                    put("osVersion", "14")
                }
            )
        )
    }

    /** Resolves an artist channel id (UC…) via an artist-filtered search. */
    internal suspend fun resolveArtistChannelId(artistName: String): String? {
        if (artistName.isBlank()) return null
        for (spec in clientFleet()) {
            val root = executeSearch(spec, artistName) ?: continue
            val id = ClipsResponseParser.firstArtistChannelId(root)
            if (id != null) return id
        }
        return null
    }

    /** Resolves one seed track videoId for an artist (SONGS top result). */
    internal suspend fun resolveSeedVideoForArtist(artistName: String): String? {
        if (artistName.isBlank()) return null
        for (spec in clientFleet()) {
            val root = executeSearch(spec, artistName) ?: continue
            val id = ClipsResponseParser.firstSongVideoId(root)
            if (id != null) return id
        }
        return null
    }

    // ─────────────────────────────────────────────── InnerTube transports

    private fun requestJson(spec: ClipsClientSpec, extras: JSONObject.() -> Unit): JSONObject =
        JSONObject().apply {
            put("context", JSONObject().apply {
                put("client", JSONObject().apply {
                    put("clientName", spec.clientName)
                    put("clientVersion", spec.clientVersion)
                    put("hl", "en")
                    put("gl", "US")
                    spec.contextExtras(this)
                })
            })
            extras(this)
        }

    private fun baseRequest(spec: ClipsClientSpec, url: String, requestJson: JSONObject): Request =
        Request.Builder()
            .url(url)
            .header("Content-Type", "application/json; charset=UTF-8")
            .header("User-Agent", spec.userAgent)
            .header("Accept", "*/*")
            .header("Accept-Encoding", "gzip, deflate")
            .header("X-YouTube-Client-Name", spec.clientHeaderId)
            .header("X-YouTube-Client-Version", spec.clientVersion)
            .header("Origin", spec.origin)
            .header("Referer", "${spec.origin}/")
            .post(requestJson.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

    /**
     * /browse for an artist channel. [tab] selects the params hint
     * ("clips" for the Shorts-style tab, null for the home tab).
     */
    internal suspend fun executeBrowse(
        spec: ClipsClientSpec,
        browseId: String,
        tab: String?
    ): JSONObject? = withContext(Dispatchers.IO) {
        runCatching {
            val json = requestJson(spec) {
                put("browseId", browseId)
                if (tab != null) put("params", browseParamsFor(tab))
            }
            execute(spec, BROWSE_URL, json)
        }.getOrNull()
    }

    internal suspend fun executeNext(spec: ClipsClientSpec, videoId: String): JSONObject? =
        withContext(Dispatchers.IO) {
            runCatching {
                val json = requestJson(spec) { put("videoId", videoId) }
                execute(spec, NEXT_URL_MUSIC, json)
            }.getOrNull()
        }

    internal suspend fun executeSearch(spec: ClipsClientSpec, query: String): JSONObject? =
        withContext(Dispatchers.IO) {
            runCatching {
                val json = requestJson(spec) {
                    put("query", query)
                    put("params", "EgWKAQIIAWoKEAkQBRAKEAMQBA%3D%3D")
                }
                execute(spec, SEARCH_URL, json)
            }.getOrNull()
        }

    private suspend fun execute(
        spec: ClipsClientSpec,
        url: String,
        requestJson: JSONObject
    ): JSONObject? {
        NetworkEngine.client.newCall(baseRequest(spec, url, requestJson)).execute().use { response ->
            if (!response.isSuccessful) {
                SLog.d(TAG, "${spec.label} POST → HTTP ${response.code}; advancing fleet")
                return null
            }
            val body = response.body ?: return null
            val encoding = response.header("Content-Encoding", "")
            val raw = if ("gzip".equals(encoding, ignoreCase = true)) {
                GZIPInputStream(body.byteStream()).use { g -> String(g.readBytes(), Charsets.UTF_8) }
            } else {
                String(body.bytes(), Charsets.UTF_8)
            }
            if (raw.isBlank()) return null
            return JSONObject(raw)
        }
    }

    /** Stable browse params for the artist "clips"/"songs" tab routes. */
    internal fun browseParamsFor(tab: String): String = when (tab) {
        "clips" -> "EgVzaG9ydHPyBgUKA5oBAA%3D%3D" // shorts/clips tab route
        else -> ""
    }

    // ────────────────────────────────────── canonical cache serialization

    private fun pageToCanonicalJson(page: ClipsPage): String {
        val root = JSONObject()
        root.put("artist", page.artistName)
        val arr = org.json.JSONArray()
        page.clips.forEach { c ->
            arr.put(
                JSONObject()
                    .put("cid", c.clipId)
                    .put("v", c.videoId)
                    .put("t", c.title)
                    .put("th", c.thumbnailUrl)
                    .put("d", c.durationSec)
                    .put("vc", c.viewCountText)
                    .put("sync", c.audioSyncTrackVideoId ?: "")
            )
        }
        root.put("clips", arr)
        return root.toString()
    }

    private fun canonicalJsonToPage(json: String): ClipsPage = runCatching {
        val root = JSONObject(json)
        val clips = ArrayList<ArtistClip>()
        val arr = root.optJSONArray("clips") ?: org.json.JSONArray()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val clipId = o.optString("cid", "")
            val videoId = o.optString("v", "")
            if (clipId.isBlank() || videoId.isBlank()) continue
            clips.add(
                ArtistClip(
                    clipId = clipId,
                    videoId = videoId,
                    title = o.optString("t", ""),
                    thumbnailUrl = o.optString("th", ""),
                    durationSec = o.optInt("d", 30),
                    viewCountText = o.optString("vc", ""),
                    audioSyncTrackVideoId = o.optString("sync", "").ifBlank { null }
                )
            )
        }
        ClipsPage(artistName = root.optString("artist", ""), clips = clips, fromCache = true)
    }.getOrDefault(ClipsPage(fromCache = true))
}

/**
 * Pure parser for ArtistClipsApi — no network, JVM-unit-testable.
 *
 * Recognized clip renderer signatures (collected defensively, in priority
 * order):
 *  • shortsLockupViewModel  — modern Shorts shelf entry
 *    (entityId + content.image.sources + overlayMetadata)
 *  • reelItemRenderer       — legacy Shorts shelf entry (videoId + headline)
 *  • clipRenderer           — curated "Clips" on watch-next (videoId +
 *    accessibility label + durationText)
 *  • shortVideoRenderer     — short videoRenderer with ~≤45s badge
 *
 * Any renderer shape that fails one of these signatures is skipped without
 * throwing; shelf titles containing "Clip"/"Short" are used only to boost
 * confidence, never required.
 */
object ClipsResponseParser {

    private val SHELF_HINTS = listOf("clip", "short", "reel")

    /**
     * Parses an artist /browse response for clip renderers.
     */
    fun parseBrowse(root: JSONObject, maxClips: Int): List<ArtistClipsApi.ArtistClip> =
        collectClips(root, maxClips)

    /**
     * Parses a watch-next /next response for clip renderers.
     */
    fun parseNext(root: JSONObject, maxClips: Int): List<ArtistClipsApi.ArtistClip> =
        collectClips(root, maxClips)

    /** First artist channel id (UC…) from a search response. */
    fun firstArtistChannelId(root: JSONObject): String? {
        var found: String? = null
        CanvasResponseParser.walk(root) { node, parentKey ->
            if (found != null) return@walk
            if (node !is JSONObject) return@walk
            if (parentKey == null || !parentKey.endsWith("Renderer")) return@walk
            val nav = node.optJSONObject("navigationEndpoint") ?: return@walk
            val browseEp = nav.optJSONObject("browseEndpoint") ?: return@walk
            val id = browseEp.optString("browseId", "")
            if (id.startsWith("UC") && id.length == 24) found = id
        }
        return found
    }

    /** First song videoId from a search response. */
    fun firstSongVideoId(root: JSONObject): String? {
        var found: String? = null
        CanvasResponseParser.walk(root) { node, parentKey ->
            if (found != null) return@walk
            if (node !is JSONObject) return@walk
            if (parentKey == null || !parentKey.endsWith("Renderer")) return@walk
            val id = node.optString("videoId", "")
            if (id.length == 11) found = id
        }
        return found
    }

    // ─────────────────────────────────────────── clip renderer signatures

    private fun collectClips(root: JSONObject, maxClips: Int): List<ArtistClipsApi.ArtistClip> {
        // ── Pass 1: harvest Clips/Short-flavored shelf headers ANYWHERE in
        // the tree. STRICT title-type check — org.json's optString() coerces
        // JSONObject values through toString(), so a track title object like
        // {"simpleText":"A short"} would string-match "short" and false-boost
        // the gate; only a REAL string title counts. Two passes make the
        // boost order-independent (headers may trail their renderers).
        var shelfBoost = false
        CanvasResponseParser.walk(root) { node, _ ->
            if (shelfBoost) return@walk
            if (node is JSONObject) {
                val titleValue = node.opt("title")
                if (titleValue is String &&
                    SHELF_HINTS.any { titleValue.contains(it, ignoreCase = true) }
                ) {
                    shelfBoost = true
                }
            }
        }

        // ── Pass 2: collect renderers with the boost already settled.
        val out = LinkedHashMap<String, ArtistClipsApi.ArtistClip>()
        CanvasResponseParser.walk(root) { node, parentKey ->
            if (out.size >= maxClips) return@walk

            val clip = when {
                parentKey == "shortsLockupViewModel" -> parseShortsLockup(node)
                parentKey == "reelItemRenderer" -> parseReelItem(node)
                parentKey == "clipRenderer" -> parseClipRenderer(node)
                parentKey == "shortVideoRenderer" && isShort(node) -> parseShortVideo(node)
                else -> null
            } ?: return@walk

            // Shelf-adjacent renderers always pass; stray short videos
            // only count when a Clips shelf header was seen somewhere.
            if (parentKey == "shortVideoRenderer" && !shelfBoost) return@walk
            if (clip.videoId.isBlank() && clip.clipId.isBlank()) return@walk
            out.putIfAbsent(clip.clipId.ifBlank { clip.videoId }, clip)
        }
        return out.values.toList().take(maxClips)
    }

    /** Modern Shorts shelf entry. */
    private fun parseShortsLockup(node: Any?): ArtistClipsApi.ArtistClip? {
        if (node !is JSONObject) return null
        val entityId = node.optString("entityId", "")
        val content = node.optJSONObject("content") ?: node
        // videoId is nested inside the onTap innertube command, or recoverable
        // from the thumbnail URL.
        val videoId = extractVideoIdFromNav(content) ?: extractVideoIdFromThumb(content)
        if (entityId.isBlank() && videoId == null) return null
        val title = content.optJSONObject("overlayMetadata")
            ?.optJSONObject("primaryText")
            ?.optString("content", "")
            ?.ifBlank { null }
            ?: content.optJSONObject("metadata")?.optString("title", "") ?: ""
        val thumb = content.optJSONObject("image")
            ?.optJSONArray("sources")
            ?.let { sources ->
                (0 until sources.length()).mapNotNull { sources.optJSONObject(it)?.optString("url", "") }
                    .filter { it.isNotBlank() }.maxByOrNull { it.length }
            } ?: ""
        val secondary = content.optJSONObject("overlayMetadata")
            ?.optJSONObject("secondaryText")?.optString("content", "") ?: ""
        val durationSec = parseDurationBadge(secondary)
        return ArtistClipsApi.ArtistClip(
            clipId = entityId.ifBlank { videoId ?: return null },
            videoId = videoId ?: entityId.substringAfterLast("/").ifBlank { "" },
            title = title,
            thumbnailUrl = thumb,
            durationSec = durationSec ?: 30,
            viewCountText = stripDurationFromBadge(secondary)
        )
    }

    /** Legacy Shorts shelf entry. */
    private fun parseReelItem(node: Any?): ArtistClipsApi.ArtistClip? {
        if (node !is JSONObject) return null
        val videoId = node.optString("videoId", "")
        if (videoId.isBlank()) return null
        val title = node.optJSONObject("headline")?.optString("simpleText", "") ?: ""
        val thumb = node.optJSONObject("thumbnail")?.optJSONArray("thumbnails")?.let { sources ->
            (0 until sources.length()).mapNotNull { sources.optJSONObject(it)?.optString("url", "") }
                .filter { it.isNotBlank() }.maxByOrNull { it.length }
        } ?: ""
        return ArtistClipsApi.ArtistClip(
            clipId = videoId,
            videoId = videoId,
            title = title,
            thumbnailUrl = thumb,
            durationSec = 30,
            viewCountText = node.optJSONObject("viewCountText")?.optString("simpleText", "") ?: ""
        )
    }

    /** Curated watch-next Clip. */
    private fun parseClipRenderer(node: Any?): ArtistClipsApi.ArtistClip? {
        if (node !is JSONObject) return null
        val videoId = node.optString("videoId", "")
        if (videoId.isBlank()) return null
        val title = node.optJSONObject("title")
            ?.let { t ->
                t.optJSONObject("accessibility")?.optJSONObject("accessibilityData")
                    ?.optString("label", "")?.ifBlank { null }
                    ?: t.optString("simpleText", "")
            } ?: ""
        val durationSec = parseDurationBadge(node.optString("durationText", ""))
            ?: node.optJSONObject("durationText")?.optString("simpleText", "")?.let { parseDurationBadge(it) }
            ?: 30
        val thumb = node.optJSONObject("thumbnail")?.optJSONArray("thumbnails")?.let { sources ->
            (0 until sources.length()).mapNotNull { sources.optJSONObject(it)?.optString("url", "") }
                .filter { it.isNotBlank() }.maxByOrNull { it.length }
        } ?: ""
        return ArtistClipsApi.ArtistClip(
            clipId = videoId,
            videoId = videoId,
            title = title,
            thumbnailUrl = thumb,
            durationSec = durationSec
        )
    }

    /** shortVideoRenderer only counts when it carries a ≤45s duration badge. */
    private fun parseShortVideo(node: Any?): ArtistClipsApi.ArtistClip? {
        if (node !is JSONObject) return null
        val videoId = node.optString("videoId", "")
        if (videoId.isBlank()) return null
        val title = node.optJSONObject("title")
            ?.optJSONArray("runs")?.optJSONObject(0)?.optString("text", "")
            ?: node.optJSONObject("title")?.optString("simpleText", "")
        val thumb = node.optJSONObject("thumbnail")?.optJSONArray("thumbnails")?.let { sources ->
            (0 until sources.length()).mapNotNull { sources.optJSONObject(it)?.optString("url", "") }
                .filter { it.isNotBlank() }.maxByOrNull { it.length }
        } ?: ""
        val durationSec = node.optJSONObject("lengthText")?.optString("simpleText", "")
            ?.let { parseDurationBadge(it) } ?: 30
        return ArtistClipsApi.ArtistClip(
            clipId = videoId,
            videoId = videoId,
            title = title ?: "",
            thumbnailUrl = thumb,
            durationSec = durationSec,
            viewCountText = node.optJSONObject("viewCountText")?.optString("simpleText", "") ?: ""
        )
    }

    private fun isShort(node: Any?): Boolean {
        if (node !is JSONObject) return false
        val badge = node.optJSONObject("lengthText")?.optString("simpleText", "")
            ?: node.optString("lengthText", "")
        val secs = parseDurationBadge(badge) ?: return false
        return secs in 1..45
    }

    // ──────────────────────────────────────────────────── helper decoding

    private fun extractVideoIdFromNav(content: JSONObject): String? {
        val onTap = content.optJSONObject("onTap")
            ?: content.optJSONObject("rendererContext")?.optJSONObject("commandContext")?.optJSONObject("onTap")
            ?: return null
        val watchEp = onTap.optJSONObject("watchEndpoint")
            ?: onTap.optJSONObject("innertubeCommand")?.optJSONObject("watchEndpoint")
            ?: return null
        val id = watchEp.optString("videoId", "")
        return id.takeIf { it.isNotBlank() }
    }

    private fun extractVideoIdFromThumb(content: JSONObject): String? {
        val sources = content.optJSONObject("image")?.optJSONArray("sources")
            ?: content.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
            ?: return null
        for (i in 0 until sources.length()) {
            val url = sources.optJSONObject(i)?.optString("url", "") ?: continue
            // /vi/<videoId>/… thumbnails encode the videoId directly.
            val match = Regex("/vi/([A-Za-z0-9_-]{11})/").find(url) ?: continue
            return match.groupValues[1]
        }
        return null
    }

    /**
     * "3:12" / "0:45" / "45s" / "30 sec" → seconds; null when unparseable.
     */
    internal fun parseDurationBadge(text: String): Int? {
        if (text.isBlank()) return null
        val t = text.trim()
        // Unanchored mm:ss so badges embedded in richer overlay text
        // ("0:32 • 1.2M views") still decode.
        val mmss = Regex("\\b(\\d{1,2}):(\\d{2})\\b").find(t)
        if (mmss != null) {
            return (mmss.groupValues[1].toInt() * 60) + mmss.groupValues[2].toInt()
        }
        // Seconds form REQUIRES the 's' suffix so bare numbers inside view
        // counts ("1.2M views") can never match.
        val secs = Regex("\\b(\\d{1,3})\\s*(?:s|sec|secs|second|seconds)\\b", RegexOption.IGNORE_CASE).find(t)
        if (secs != null) return secs.groupValues[1].toInt()
        return null
    }

    private fun stripDurationFromBadge(badge: String): String =
        badge.replace(Regex("\\d{1,2}:\\d{2}|\\d+\\s*(?:s|sec|secs|second|seconds)\\b", RegexOption.IGNORE_CASE), "")
            .replace(Regex("^[^A-Za-z0-9]+"), "")
            .trim()
}
