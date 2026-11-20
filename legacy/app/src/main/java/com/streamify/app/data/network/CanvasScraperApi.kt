package com.streamify.app.data.network

import com.streamify.app.util.FleetConfig
import com.streamify.app.util.SLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.zip.GZIPInputStream

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * CanvasScraperApi — 8-second Canvas video loop scraper (Phase 3, area 1)
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * Resolves the short vertical MP4/WebM "Canvas" loop that plays behind a
 * full-screen player. YouTube Music ships these through the InnerTube
 * `/player` response (auxiliaryAssets / canvasMediaRenderer attachments)
 * and occasionally through the `/next` watch-next tree's player overlays.
 * The loop is expected to be ~8 seconds and must be consumed with
 * REPEAT_MODE_ONE by the Media3 surface.
 *
 * 🛡️ Anti-failure architecture (same pillars as the Phase 2 radio scraper):
 *
 *  1. Multi-client header fallback — WEB_REMIX → ANDROID_MUSIC → WEB.
 *     Token-rotted / 400 responses silently advance the fleet; the caller
 *     never sees an exception.
 *
 *  2. Defensive recursive JSON traversal — Canvas assets live at rotating,
 *     undocumented paths (auxiliaryAssets[].auxiliaryAssetRenderer,
 *     canvasMediaRenderer, canvasOverlayRenderer, …). The parser walks the
 *     whole tree with opt* accessors and collects ANY object that carries a
 *     video URL under a canvas-ish key; unexpected shapes yield fewer
 *     loops, never a JSONException.
 *
 *  3. Stale-while-revalidate cache — loops are immutable per videoId, so a
 *     24h TTL serves the URL instantly while quietly revalidating.
 *
 * The object NEVER throws: every public entry point returns a valid
 * [CanvasLoop?] (null when nothing scrapable was found after the full
 * fallback chain).
 */
object CanvasScraperApi {

    private const val TAG = "CanvasScraperApi"

    /** InnerTube endpoints for the two canvas transports. */
    internal const val PLAYER_URL_MUSIC = "https://music.youtube.com/youtubei/v1/player?key=AIzaSyC9XL3ZjWddXya6X74dJoCTL-WEKFD3UVk"
    internal const val NEXT_URL_MUSIC = "https://music.youtube.com/youtubei/v1/next?key=AIzaSyC9XL3ZjWddXya6X74dJoCTL-WEKFD3UVk"

    private const val DESKTOP_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    private const val ANDROID_MUSIC_UA = "com.google.android.apps.youtube.music/8.6.53.52 (Linux; U; Android 14) US"

    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    /** Canvas loops are immutable per videoId → long SWR TTL. */
    internal val swr: SwrCache by lazy {
        SwrCache(dir = null)
    }
    private const val CANVAS_CACHE_TTL_MS = 24L * 60 * 60 * 1000

    // ────────────────────────────────────────────────────────────── model

    /**
     * A scraped Canvas loop. [loopUrl] points at the raw video container
     * (mp4/webm); [durationMs] is the loop length when the source exposes
     * it (default 8s per the Canvas spec).
     */
    data class CanvasLoop(
        val videoId: String,
        val loopUrl: String,
        val mimeType: String,
        val durationMs: Int = 8_000,
        val width: Int = 0,
        val height: Int = 0,
        /** Suggested ambient glow seed (ARGB) extracted from the payload when present. */
        val paletteColor: Long? = null,
        val fromCache: Boolean = false
    ) {
        val isVertical: Boolean get() = width == 0 || height == 0 || height >= width
    }

    /** InnerTube client spec for the canvas fleet. */
    internal data class CanvasClientSpec(
        val label: String,
        val clientName: String,
        val clientVersion: String,
        val clientHeaderId: String,
        val endpointUrl: String,
        val origin: String,
        val userAgent: String,
        val contextExtras: JSONObject.() -> Unit = {}
    )

    // ─────────────────────────────────────── public entry points (no-throw)

    /**
     * Canvas loop for a concrete videoId (SWR-cached 24h). Tries the
     * `/player` fleet first, then the `/next` fleet. Returns null when the
     * track simply has no canvas — that is a normal outcome, not an error.
     */
    suspend fun canvasFor(videoId: String): CanvasLoop? {
        val id = videoId.trim().takeIf { it.length == 11 } ?: return null
        val cached = swr.swr(
            key = "canvas_$id",
            ttlMs = CANVAS_CACHE_TTL_MS,
            fetch = { loopToCanonicalJson(resolveCanvasFleet(id)) }
        ) ?: return null
        return canonicalJsonToLoop(cached)
    }

    /**
     * Canvas loop for an arbitrary query — resolves the query to a videoId
     * through a SONGS-filtered search, then fetches the canvas for it.
     */
    suspend fun canvasForQuery(query: String): CanvasLoop? {
        val clean = query.trim()
        if (clean.isBlank()) return null
        val videoId = searchSongVideoId(clean) ?: return null
        return canvasFor(videoId)
    }

    // ─────────────────────────────────────────── fleet fallback internals

    internal suspend fun resolveCanvasFleet(videoId: String): CanvasLoop? {
        // Transport A: /player auxiliary canvas assets.
        for (spec in clientFleet()) {
            val root = executePost(spec) { requestFor(spec, videoId) } ?: continue
            val loop = CanvasResponseParser.parsePlayer(root, videoId)
            if (loop != null) return loop
        }
        // Transport B: /next watch-next player overlays.
        for (spec in clientFleet()) {
            val root = executePost(spec) { requestForNext(spec, videoId) } ?: continue
            val loop = CanvasResponseParser.parseNext(root, videoId)
            if (loop != null) return loop
        }
        return null
    }

    internal fun clientFleet(): List<CanvasClientSpec> {
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
            CanvasClientSpec(
                label = "WEB_REMIX",
                clientName = "WEB_REMIX",
                clientVersion = musicVersion,
                clientHeaderId = "67",
                endpointUrl = PLAYER_URL_MUSIC,
                origin = "https://music.youtube.com",
                userAgent = DESKTOP_UA
            ),
            CanvasClientSpec(
                label = "ANDROID_MUSIC",
                clientName = "ANDROID_MUSIC",
                clientVersion = androidMusicVersion,
                clientHeaderId = "21",
                endpointUrl = PLAYER_URL_MUSIC,
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

    /** Resolves one SONGS-filtered search videoId for a query (best-effort). */
    internal suspend fun searchSongVideoId(query: String): String? {
        for (spec in clientFleet()) {
            val root = executePost(spec) { requestForSearch(spec, query) } ?: continue
            val id = CanvasResponseParser.firstSongVideoId(root)
            if (id != null) return id
        }
        return null
    }

    // ─────────────────────────────────────────────── InnerTube transports

    private fun requestFor(spec: CanvasClientSpec, videoId: String): Request {
        val requestJson = JSONObject().apply {
            put("context", JSONObject().apply {
                put("client", JSONObject().apply {
                    put("clientName", spec.clientName)
                    put("clientVersion", spec.clientVersion)
                    put("hl", "en")
                    put("gl", "US")
                    spec.contextExtras(this)
                })
            })
            put("videoId", videoId)
            put("contentCheckOk", true)
        }
        return baseRequest(spec, spec.endpointUrl, requestJson)
    }

    private fun requestForNext(spec: CanvasClientSpec, videoId: String): Request {
        val requestJson = JSONObject().apply {
            put("context", JSONObject().apply {
                put("client", JSONObject().apply {
                    put("clientName", spec.clientName)
                    put("clientVersion", spec.clientVersion)
                    put("hl", "en")
                    put("gl", "US")
                    spec.contextExtras(this)
                })
            })
            put("videoId", videoId)
        }
        return baseRequest(spec, NEXT_URL_MUSIC, requestJson)
    }

    private fun requestForSearch(spec: CanvasClientSpec, query: String): Request {
        val requestJson = JSONObject().apply {
            put("context", JSONObject().apply {
                put("client", JSONObject().apply {
                    put("clientName", spec.clientName)
                    put("clientVersion", spec.clientVersion)
                    put("hl", "en")
                    put("gl", "US")
                    spec.contextExtras(this)
                })
            })
            put("query", query)
            put("params", "EgWKAQIIAWoKEAkQBRAKEAMQBA%3D%3D") // SearchFilter.SONGS
        }
        return baseRequest(
            spec,
            "https://music.youtube.com/youtubei/v1/search?key=AIzaSyC9XL3ZjWddXya6X74dJoCTL-WEKFD3UVk",
            requestJson
        )
    }

    private fun baseRequest(spec: CanvasClientSpec, url: String, requestJson: JSONObject): Request =
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

    /** Executes one POST; returns the parsed JSON root or null on any failure. */
    internal suspend fun executePost(
        spec: CanvasClientSpec,
        requestBuilder: () -> Request
    ): JSONObject? = withContext(Dispatchers.IO) {
        runCatching {
            NetworkEngine.client.newCall(requestBuilder()).execute().use { response ->
                if (!response.isSuccessful) {
                    SLog.d(TAG, "${spec.label} POST → HTTP ${response.code}; advancing fleet")
                    return@runCatching null
                }
                val body = response.body ?: return@runCatching null
                val encoding = response.header("Content-Encoding", "")
                val raw = if ("gzip".equals(encoding, ignoreCase = true)) {
                    GZIPInputStream(body.byteStream()).use { g -> String(g.readBytes(), Charsets.UTF_8) }
                } else {
                    String(body.bytes(), Charsets.UTF_8)
                }
                if (raw.isBlank()) return@runCatching null
                JSONObject(raw)
            }
        }.getOrNull()
    }

    // ────────────────────────────────────── canonical cache serialization

    private fun loopToCanonicalJson(loop: CanvasLoop?): String {
        if (loop == null) return ""
        return JSONObject()
            .put("v", loop.videoId)
            .put("u", loop.loopUrl)
            .put("m", loop.mimeType)
            .put("d", loop.durationMs)
            .put("w", loop.width)
            .put("h", loop.height)
            .put("p", loop.paletteColor ?: 0L)
            .toString()
    }

    private fun canonicalJsonToLoop(json: String): CanvasLoop? = runCatching {
        if (json.isBlank()) return@runCatching null
        val o = JSONObject(json)
        val videoId = o.optString("v", "")
        val url = o.optString("u", "")
        if (videoId.length != 11 || url.isBlank()) return@runCatching null
        CanvasLoop(
            videoId = videoId,
            loopUrl = url,
            mimeType = o.optString("m", "video/mp4"),
            durationMs = o.optInt("d", 8_000),
            width = o.optInt("w", 0),
            height = o.optInt("h", 0),
            paletteColor = o.optLong("p", 0L).takeIf { it != 0L },
            fromCache = true
        )
    }.getOrNull()
}

/**
 * Pure parser for CanvasScraperApi — no network, fully JVM-unit-testable.
 *
 * Walks any InnerTube response shape defensively and extracts the first
 * canvas-class video loop it can find. Known hosts for canvas payloads:
 *
 *  • /player → `auxiliaryAssets: [{ auxiliaryAssetRenderer: { url, type:
 *    "CANVAS" | "CANNED" | … } }]`
 *  • /player → `playerOverlays.decoratedPlayerBarRenderer… canvasMediaRenderer`
 *  • /next   → `playerOverlays.…canvasMediaRenderer { loopUrl / mediaUrl }`
 *  • /next   → `engagementPanels[].engagementPanelSectionListRenderer.content.
 *              continuationItemRenderer… canvas` attachments
 *
 * Because these rotate, the parser keys off *content signatures* (a JSON
 * object that has a video-ish URL and a canvas-ish parent key) instead of
 * hardcoded absolute paths.
 */
object CanvasResponseParser {

    /** Parent/owner key names that identify a canvas-ish subtree. */
    private val CANVAS_KEY_HINTS = setOf(
        "canvasMediaRenderer", "canvasOverlayRenderer", "canvasRenderer",
        "auxiliaryAssetRenderer", "canvas", "canvasUrl", "loopUrl", "canvasData"
    )

    /** Auxiliary asset type values that mean "this is the canvas loop". */
    private val CANVAS_TYPE_VALUES = setOf("CANVAS", "CANNED", "CANVAS_LOOP", "SHORT_LOOP")

    /** URL substrings that mark a URL as a canvas loop candidate. */
    private val URL_HINTS = listOf("googlevideo.com", "canvas", "loop")

    private val VIDEO_MIME = Regex("video/(mp4|webm)", RegexOption.IGNORE_CASE)

    /**
     * Parses a `/player` response root for a canvas loop.
     */
    fun parsePlayer(root: JSONObject, videoId: String): CanvasScraperApi.CanvasLoop? {
        // Fast path: documented auxiliaryAssets array.
        val aux = root.optJSONArray("auxiliaryAssets")
        if (aux != null) {
            val loop = parseAuxiliaryAssets(aux, videoId)
            if (loop != null) return loop
        }
        // Slow path: canvas hidden anywhere in the tree.
        return walkForCanvas(root, videoId)
    }

    /**
     * Parses a `/next` response root for a canvas loop.
     */
    fun parseNext(root: JSONObject, videoId: String): CanvasScraperApi.CanvasLoop? =
        walkForCanvas(root, videoId)

    /** Extracts the top SONGS result videoId from a search response. */
    fun firstSongVideoId(root: JSONObject): String? {
        val sink = mutableListOf<String>()
        walk(root) { node, parentKey ->
            if (node !is JSONObject) return@walk
            val vid = node.optString("videoId", "")
            if (vid.length == 11) {
                val isSongish = parentKey?.let {
                    it.endsWith("Renderer") || it.endsWith("ViewModel")
                } ?: false
                if (isSongish) sink.add(vid)
            }
        }
        return sink.firstOrNull()
    }

    // ─────────────────────────────────────────── auxiliaryAssets handling

    internal fun parseAuxiliaryAssets(
        assets: JSONArray,
        videoId: String
    ): CanvasScraperApi.CanvasLoop? {
        for (i in 0 until assets.length()) {
            val asset = assets.optJSONObject(i) ?: continue
            val renderer = asset.optJSONObject("auxiliaryAssetRenderer") ?: asset
            val type = renderer.optString("type", "")
            val url = renderer.optString("url", "")
            if (url.isBlank()) continue
            val typeMatches = type.isBlank() || type.uppercase() in CANVAS_TYPE_VALUES
            if (typeMatches && looksLikeCanvasUrl(url)) {
                return buildLoop(videoId, renderer, url)
            }
        }
        return null
    }

    // ──────────────────────────────────────── defensive canvas tree walk

    /**
     * Depth-first recursive scan. A node qualifies as a canvas loop when:
     *  • its *parent key* is canvas-ish, AND
     *  • it carries a usable video URL (directly or one level deep).
     */
    private fun walkForCanvas(root: JSONObject, videoId: String): CanvasScraperApi.CanvasLoop? {
        var found: CanvasScraperApi.CanvasLoop? = null
        walk(root) { node, parentKey ->
            if (found != null) return@walk
            if (node !is JSONObject) return@walk
            val hintMatch = parentKey != null && parentKey in CANVAS_KEY_HINTS
            if (!hintMatch) return@walk
            val url = extractVideoUrl(node) ?: return@walk
            found = buildLoop(videoId, node, url)
        }
        return found
    }

    /** Pulls the first video-looking URL out of a node (one level deep). */
    private fun extractVideoUrl(node: JSONObject): String? {
        node.optString("url", "").takeIf { it.isNotBlank() && looksLikeCanvasUrl(it) }?.let { return it }
        node.optString("loopUrl", "").takeIf { it.isNotBlank() && looksLikeCanvasUrl(it) }?.let { return it }
        node.optString("mediaUrl", "").takeIf { it.isNotBlank() && looksLikeCanvasUrl(it) }?.let { return it }
        // Nested one level (e.g. canvasMediaRenderer.player…
        for (key in listOf("player", "video", "media", "attachment")) {
            val child = node.optJSONObject(key) ?: continue
            child.optString("url", "").takeIf { it.isNotBlank() && looksLikeCanvasUrl(it) }?.let { return it }
        }
        return null
    }

    private fun buildLoop(videoId: String, node: JSONObject, url: String): CanvasScraperApi.CanvasLoop {
        val mime = node.optString("mimeType", node.optString("contentType", ""))
            .takeIf { VIDEO_MIME.containsMatchIn(it) } ?: "video/mp4"
        val durationMs = node.optLong("durationMs", node.optLong("duration", 0L)).toInt()
            .takeIf { it > 0 } ?: 8_000
        val width = node.optInt("width", 0)
        val height = node.optInt("height", 0)
        // Some canvas payloads ship a theme/brand color for ambient glow.
        val palette = node.optString("themeColor", node.optString("backgroundColor", ""))
            .takeIf { it.startsWith("#") && it.length == 7 }
            ?.let { hexToArgbLong(it) }
        return CanvasScraperApi.CanvasLoop(
            videoId = videoId,
            loopUrl = url,
            mimeType = mime,
            durationMs = durationMs,
            width = width,
            height = height,
            paletteColor = palette
        )
    }

    private fun hexToArgbLong(hex: String): Long {
        val rgb = hex.removePrefix("#").toLongOrNull(16) ?: return 0xFF000000L
        return (0xFF000000L or rgb)
    }

    private fun looksLikeCanvasUrl(url: String): Boolean {
        if (VIDEO_MIME.containsMatchIn(url)) return true
        // Canvas loops are served from googlevideo with .mp4/.webm fragments.
        if (url.contains("googlevideo.com") && (url.contains(".mp4") || url.contains(".webm"))) return true
        return URL_HINTS.any { url.contains(it, ignoreCase = true) }
    }

    // ────────────────────────────────────────── generic opt-only traversal

    /**
     * Visits every node of the JSON tree with its parent key, using opt*
     * accessors exclusively so hostile shapes never throw. NOT inline —
     * the walk is recursive and Kotlin forbids recursive inline functions.
     */
    internal fun walk(root: Any?, visitor: (node: Any?, parentKey: String?) -> Unit) {
        when (root) {
            is JSONObject -> {
                for (key in root.keys()) {
                    val child = root.opt(key) ?: continue
                    visitor(child, key)
                    walk(child, visitor)
                }
            }
            is JSONArray -> {
                for (i in 0 until root.length()) {
                    val child = root.opt(i) ?: continue
                    visitor(child, null)
                    walk(child, visitor)
                }
            }
            else -> visitor(root, null)
        }
    }
}
