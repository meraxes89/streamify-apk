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
import java.io.File
import java.util.zip.GZIPInputStream

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * YouTubeMusicRadioApi — InnerTube /youtubei/v1/next radio scraper (Gap #15)
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * Streams the infinite "radio" continuation lane for a seed track or artist
 * and extracts categorized shelves ("Similar Artists", "Discover / Deep Cuts",
 * "Mixed for You") from the /next watch-next tree.
 *
 * 🛡️ Anti-failure architecture (mandated by the Phase 2 directive):
 *
 *  1. Multi-client header fallback — WEB_REMIX → ANDROID_MUSIC → WEB.
 *     A 400 / empty / token-rotted response silently advances to the next
 *     client; the user never sees an error.
 *
 *  2. Defensive recursive JSON traversal — YouTube wraps shelves in a
 *     rotating zoo of renderers (playlistPanelVideoRenderer,
 *     musicResponsiveListItemRenderer, musicCardShelfRenderer, …). The
 *     parser walks the whole tree with opt* accessors only, so an
 *     unexpected renderer shape yields fewer tracks, never a JSONException.
 *
 *  3. Graceful fallback chain — an obscure artist seed with no radio
 *     continuation falls back to SearchFilter.SONGS, extracts the top
 *     result's videoId, and re-invokes the radio continuation.
 *
 *  4. Stale-while-revalidate cache — successful pages are persisted via
 *     [SwrCache]; offline / high-packet-loss opens serve the cached shelf
 *     instantly while a background revalidation refreshes it.
 *
 * The class NEVER throws: every public entry point returns a valid
 * (possibly empty) [RadioPage]; callers fall back to local-library shelves.
 */
object YouTubeMusicRadioApi {

    private const val TAG = "YtmRadioApi"

    private const val NEXT_URL_MUSIC = "https://music.youtube.com/youtubei/v1/next"
    private const val NEXT_URL_WEB = "https://www.youtube.com/youtubei/v1/next"

    private const val MAX_BODY_BYTES = 6L * 1024 * 1024
    private const val RADIO_CACHE_TTL_MS = 6L * 60 * 60 * 1000

    private val JSON_MEDIA_TYPE = "application/json; charset=UTF-8".toMediaType()

    /** Injectable seams so JVM unit tests can time-travel / isolate disk. */
    @Volatile var cacheDir: File? = null
    @Volatile var clock: () -> Long = { System.currentTimeMillis() }
    @Volatile var swrScope: kotlinx.coroutines.CoroutineScope? = null

    internal val swr: SwrCache by lazy {
        SwrCache(dir = cacheDir, clock = clock, scope = swrScope ?: kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.SupervisorJob() + Dispatchers.IO
        ))
    }

    // ─────────────────────────────────────────────────────────── data model

    enum class ShelfCategory(val label: String) {
        RADIO_LANE("Radio"),
        SIMILAR_ARTISTS("Similar Artists"),
        DISCOVER_DEEP_CUTS("Discover / Deep Cuts"),
        MIXED_FOR_YOU("Mixed for You"),
        OTHER("More");

        companion object {
            /** Keyword heuristic over live shelf titles — never throws. */
            fun categorize(rawTitle: String): ShelfCategory {
                val t = rawTitle.lowercase()
                return when {
                    t.contains("similar") || t.contains("related") || t.contains("fans like") ||
                        t.contains("you might also like") -> SIMILAR_ARTISTS
                    t.contains("discover") || t.contains("deep cut") || t.contains("new") ||
                        t.contains("fresh") || t.contains("release") -> DISCOVER_DEEP_CUTS
                    t.contains("mixed") || t.contains("for you") || t.contains("recommended") ||
                        t.contains("listen again") || t.contains("autoplay") -> MIXED_FOR_YOU
                    else -> OTHER
                }
            }
        }
    }

    data class RadioTrack(
        val videoId: String,
        val title: String,
        val artist: String,
        val durationSec: Int = 0,
        val thumbnailUrl: String = "",
        val shelfTitle: String? = null,
        val category: ShelfCategory = ShelfCategory.RADIO_LANE
    )

    data class RadioShelf(
        val title: String,
        val category: ShelfCategory,
        val tracks: List<RadioTrack>,
        val continuationToken: String? = null
    )

    data class RadioPage(
        val tracks: List<RadioTrack> = emptyList(),
        val shelves: List<RadioShelf> = emptyList(),
        val continuationToken: String? = null,
        val fromCache: Boolean = false
    ) {
        fun byCategory(category: ShelfCategory): List<RadioTrack> =
            tracks.filter { it.category == category }
    }

    /** InnerTube client spec used by the header-fallback fleet. */
    internal data class RadioClientSpec(
        val label: String,
        val clientName: String,
        val clientVersion: String,
        val clientHeaderId: String,
        val endpointUrl: String,
        val origin: String,
        val userAgent: String,
        val contextExtras: JSONObject.() -> Unit = {}
    )

    // ────────────────────────────────────────────────── public entry points

    /**
     * Radio for a concrete seed video (SWR-cached, 6h TTL). Falls back
     * internally through the client fleet; returns an empty page (never an
     * exception) when every transport fails and no cache exists.
     */
    suspend fun radioForSeed(seedVideoId: String, maxTracks: Int = 50): RadioPage {
        val id = seedVideoId.trim().takeIf { it.length == 11 } ?: return RadioPage()
        val cached = swr.swr(
            key = "radio_seed_$id",
            ttlMs = RADIO_CACHE_TTL_MS,
            fetch = { pageToCanonicalJson(resolveRadioFleet(id, maxTracks)) }
        ) ?: return RadioPage()
        return canonicalJsonToPage(cached)
    }

    /**
     * Radio for an arbitrary query / artist name. Chain (directive #3):
     * direct radio → SearchFilter.SONGS top result → radio re-invocation,
     * with the second search candidate as a final retry.
     */
    suspend fun radioForQuery(query: String, maxTracks: Int = 50): RadioPage {
        val clean = query.trim()
        if (clean.isBlank()) return RadioPage()
        val cached = swr.swr(
            key = "radio_query_${clean.lowercase()}",
            ttlMs = RADIO_CACHE_TTL_MS,
            fetch = { pageToCanonicalJson(resolveRadioForQuery(clean, maxTracks)) }
        ) ?: return RadioPage()
        return canonicalJsonToPage(cached)
    }

    /**
     * Live multi-page radio stream: first /next page plus up to [maxPages]
     * continuations of the infinite lane, deduped, seed excluded.
     */
    suspend fun radioStream(seedVideoId: String, maxPages: Int = 3, maxTracks: Int = 90): List<RadioTrack> =
        withContext(Dispatchers.IO) {
            val collected = LinkedHashMap<String, RadioTrack>()
            var page = resolveRadioFleet(seedVideoId, maxTracks)
            page.tracks.forEach { if (it.videoId != seedVideoId) collected[it.videoId] = it }
            var token = page.continuationToken
            var pages = 1
            while (token != null && pages < maxPages && collected.size < maxTracks) {
                page = continueRadioFleet(token, maxTracks - collected.size)
                if (page.tracks.isEmpty()) break
                page.tracks.forEach { if (it.videoId != seedVideoId) collected[it.videoId] = it }
                token = page.continuationToken
                pages++
            }
            collected.values.toList()
        }

    // ─────────────────────────────── orchestration (pure enough for tests)

    internal suspend fun resolveRadioFleet(seedVideoId: String, maxTracks: Int): RadioPage {
        for (spec in clientFleet()) {
            val page = executeNext(spec, videoId = seedVideoId, continuation = null, maxTracks)
            if (page.tracks.isNotEmpty() || page.shelves.isNotEmpty()) return page
        }
        return RadioPage()
    }

    internal suspend fun continueRadioFleet(token: String, maxTracks: Int): RadioPage {
        if (token.isBlank()) return RadioPage()
        for (spec in clientFleet()) {
            val page = executeNext(spec, videoId = null, continuation = token, maxTracks)
            if (page.tracks.isNotEmpty()) return page
        }
        return RadioPage()
    }

    /**
     * Query → radio resolution with the mandated fallback chain. The search
     * and radio lambdas are virtual so JVM tests can drive the chain without
     * a network.
     */
    internal suspend fun resolveRadioForQuery(
        query: String,
        maxTracks: Int,
        search: suspend (String) -> List<String> = { q -> searchSongVideoIds(q) },
        radio: suspend (String) -> RadioPage = { vid -> resolveRadioFleet(vid, maxTracks) }
    ): RadioPage {
        val direct = radio(queryToVideoIdHint(query) ?: "")
        if (direct.tracks.isNotEmpty()) return direct
        val candidates = runCatching { search(query) }.getOrDefault(emptyList())
        for (videoId in candidates.take(2)) {
            if (videoId.isBlank()) continue
            val page = runCatching { radio(videoId) }.getOrDefault(RadioPage())
            if (page.tracks.isNotEmpty()) return page
        }
        return RadioPage()
    }

    /** A bare 11-char id passes straight through; anything else is a query. */
    private fun queryToVideoIdHint(query: String): String? =
        query.trim().takeIf { it.length == 11 && it.all { c -> c.isLetterOrDigit() || c == '-' || c == '_' } }

    private suspend fun searchSongVideoIds(query: String): List<String> =
        runCatching {
            YouTubeMusicSearchApi.search(query, SearchFilter.SONGS, maxResults = 5)
                .mapNotNull { r -> YouTubeStreamResolver.extractVideoId(r.url, r.thumbnail) }
        }.getOrDefault(emptyList())

    // ────────────────────────────────────── multi-client InnerTube transport

    /**
     * The header-fallback fleet (directive #1). WEB_REMIX is primary; when
     * its payload 400s or rots, ANDROID_MUSIC is the drop-in replacement,
     * with plain WEB as the final tertiary. Versions come from FleetConfig
     * whenever the remote fleet-config carries an override.
     */
    internal fun clientFleet(): List<RadioClientSpec> {
        val musicVersion = FleetConfig.musicSearchVersion("1.20240101.01.00")
        val webVersion = FleetConfig.webSearchVersion("2.20240101.01.00")
        val androidMusicVersion = FleetConfig.audioTargets(defaultAndroidFleet())
            .firstOrNull { it.clientName == "ANDROID" }?.clientVersion ?: "8.6.53.52"

        return listOf(
            RadioClientSpec(
                label = "WEB_REMIX",
                clientName = "WEB_REMIX",
                clientVersion = musicVersion,
                clientHeaderId = "67",
                endpointUrl = NEXT_URL_MUSIC,
                origin = "https://music.youtube.com",
                userAgent = DESKTOP_UA
            ),
            RadioClientSpec(
                label = "ANDROID_MUSIC",
                clientName = "ANDROID_MUSIC",
                clientVersion = androidMusicVersion,
                clientHeaderId = "21",
                endpointUrl = NEXT_URL_MUSIC,
                origin = "https://music.youtube.com",
                userAgent = ANDROID_MUSIC_UA,
                contextExtras = {
                    put("androidSdkVersion", 34)
                    put("osName", "Android")
                    put("osVersion", "14")
                }
            ),
            RadioClientSpec(
                label = "WEB",
                clientName = "WEB",
                clientVersion = webVersion,
                clientHeaderId = "1",
                endpointUrl = NEXT_URL_WEB,
                origin = "https://www.youtube.com",
                userAgent = DESKTOP_UA
            )
        )
    }

    private fun defaultAndroidFleet(): List<FleetConfig.ClientSpec> = listOf(
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

    /**
     * One /next POST against one client spec. Any transport / parse failure
     * returns an empty page — the fleet loop decides whether to advance.
     */
    internal suspend fun executeNext(
        spec: RadioClientSpec,
        videoId: String?,
        continuation: String?,
        maxTracks: Int
    ): RadioPage = withContext(Dispatchers.IO) {
        runCatching {
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
                if (!videoId.isNullOrBlank()) put("videoId", videoId)
                if (!continuation.isNullOrBlank()) put("continuation", continuation)
            }

            val request = Request.Builder()
                .url(spec.endpointUrl)
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

            NetworkEngine.client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    SLog.d(TAG, "${spec.label} /next → HTTP ${response.code}; advancing fleet")
                    return@runCatching RadioPage()
                }
                val body = response.body ?: return@runCatching RadioPage()
                val encoding = response.header("Content-Encoding", "")
                val raw = if ("gzip".equals(encoding, ignoreCase = true)) {
                    GZIPInputStream(body.byteStream()).use { g ->
                        val bytes = g.readBytes()
                        if (bytes.size > MAX_BODY_BYTES) return@runCatching RadioPage()
                        String(bytes, Charsets.UTF_8)
                    }
                } else {
                    val bytes = body.bytes()
                    if (bytes.size > MAX_BODY_BYTES) return@runCatching RadioPage()
                    String(bytes, Charsets.UTF_8)
                }
                if (raw.isBlank()) return@runCatching RadioPage()
                val root = runCatching { JSONObject(raw) }.getOrNull() ?: return@runCatching RadioPage()
                RadioResponseParser.parse(root, maxTracks)
            }
        }.getOrElse {
            SLog.d(TAG, "${spec.label} /next failed (${it.message}); advancing fleet")
            RadioPage()
        }
    }

    // ────────────────────────────────── canonical cache serialization (JSON)

    private fun pageToCanonicalJson(page: RadioPage): String {
        val root = JSONObject()
        val tracks = JSONArray()
        page.tracks.forEach { t ->
            tracks.put(
                JSONObject()
                    .put("v", t.videoId)
                    .put("t", t.title)
                    .put("a", t.artist)
                    .put("d", t.durationSec)
                    .put("th", t.thumbnailUrl)
                    .put("s", t.shelfTitle ?: "")
                    .put("c", t.category.name)
            )
        }
        root.put("tracks", tracks)
        root.put("cont", page.continuationToken ?: "")
        return root.toString()
    }

    private fun canonicalJsonToPage(json: String): RadioPage = runCatching {
        val root = JSONObject(json)
        val tracks = ArrayList<RadioTrack>()
        val arr = root.optJSONArray("tracks") ?: JSONArray()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val videoId = o.optString("v", "")
            if (videoId.length != 11) continue
            tracks.add(
                RadioTrack(
                    videoId = videoId,
                    title = o.optString("t", ""),
                    artist = o.optString("a", ""),
                    durationSec = o.optInt("d", 0),
                    thumbnailUrl = o.optString("th", ""),
                    shelfTitle = o.optString("s", "").ifBlank { null },
                    category = runCatching { ShelfCategory.valueOf(o.optString("c", "OTHER")) }
                        .getOrDefault(ShelfCategory.OTHER)
                )
            )
        }
        RadioPage(
            tracks = tracks,
            shelves = groupShelves(tracks),
            continuationToken = o2cont(root),
            fromCache = true
        )
    }.getOrDefault(RadioPage(fromCache = true))

    private fun o2cont(root: JSONObject): String? =
        root.optString("cont", "").ifBlank { null }

    /** Re-groups flat cached tracks into shelves by [RadioTrack.shelfTitle]. */
    internal fun groupShelves(tracks: List<RadioTrack>): List<RadioShelf> {
        val byShelf = tracks.filter { it.shelfTitle != null }.groupBy { it.shelfTitle!! }
        return byShelf.map { (title, list) ->
            RadioShelf(
                title = title,
                category = ShelfCategory.categorize(title),
                tracks = list
            )
        }.sortedBy { it.category.ordinal }
    }

    private const val DESKTOP_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    private const val ANDROID_MUSIC_UA =
        "com.google.android.apps.youtube.music/8.6.53.52 (Linux; U; Android 14) com.google.android.apps.youtube.music.Build.V8.6.53.52"
}

/**
 * RadioResponseParser — the defensive recursive JSON traversal core.
 *
 * Pure, side-effect-free, and JVM-testable. Walks the /next renderer tree
 * with a node budget and depth cap, extracting track renderers of every
 * known (and unknown-but-well-formed) shape via opt* accessors. Unexpected
 * renderer trees degrade to fewer results — never to a JSONException.
 */
object RadioResponseParser {

    /** Hard traversal bounds — hostile/bloated payloads die quietly. */
    private const val MAX_NODES = 60_000
    private const val MAX_DEPTH = 256

    private val TRACK_RENDERER_KEYS = listOf(
        "playlistPanelVideoRenderer",
        "musicResponsiveListItemRenderer",
        "musicTwoRowItemRenderer",
        "videoRenderer",
        "compactVideoRenderer",
        "gridVideoRenderer"
    )

    private val SHELF_RENDERER_KEYS = listOf(
        "musicCarouselShelfRenderer",
        "musicShelfRenderer",
        "musicCardShelfRenderer",
        "musicImmersiveCarouselShelfRenderer"
    )

    private class WalkState(
        val maxTracks: Int,
        val seen: LinkedHashSet<String>,
        val flat: MutableList<YouTubeMusicRadioApi.RadioTrack>,
        val shelves: LinkedHashMap<String, MutableList<YouTubeMusicRadioApi.RadioTrack>>,
        val continuationTokens: MutableList<String>,
        var radioLaneContinuation: String? = null
    ) {
        var nodes = 0
    }

    /**
     * Parses a raw /next response. The radio lane (playlistPanelRenderer
     * descendants and any track outside a shelf) is tagged RADIO_LANE /
     * OTHER; named shelves are tagged with their live title and bucketed
     * into [YouTubeMusicRadioApi.ShelfCategory].
     */
    fun parse(root: JSONObject, maxTracks: Int = 50): YouTubeMusicRadioApi.RadioPage {
        val state = WalkState(
            maxTracks, LinkedHashSet(), mutableListOf(),
            LinkedHashMap(), mutableListOf()
        )
        runCatching { walk(root, state, 0, null) }
            .onFailure { SLog.d("YtmRadioApi", "parser aborted early: ${it.message}") }

        return YouTubeMusicRadioApi.RadioPage(
            tracks = state.flat.toList(),
            shelves = state.shelves.map { (title, list) ->
                YouTubeMusicRadioApi.RadioShelf(
                    title = title,
                    category = YouTubeMusicRadioApi.ShelfCategory.categorize(title),
                    tracks = list.toList()
                )
            }.sortedBy { it.category.ordinal },
            continuationToken = state.radioLaneContinuation ?: state.continuationTokens.firstOrNull()
        )
    }

    private fun walk(node: Any?, state: WalkState, depth: Int, shelfTitle: String?) {
        if (node == null || depth > MAX_DEPTH) return
        if (++state.nodes > MAX_NODES) throw IllegalStateException("node budget exceeded")
        when (node) {
            is JSONObject -> walkObject(node, state, depth, shelfTitle)
            is JSONArray -> {
                if (state.seen.size >= state.maxTracks && state.radioLaneContinuation != null) return
                for (i in 0 until node.length()) {
                    walk(node.opt(i), state, depth + 1, shelfTitle)
                    if (state.seen.size >= state.maxTracks && state.shelves.isNotEmpty()) return
                }
            }
        }
    }

    private fun walkObject(obj: JSONObject, state: WalkState, depth: Int, shelfTitle: String?) {
        // 1. Shelf renderers open a new titled context for their children.
        for (shelfKey in SHELF_RENDERER_KEYS) {
            if (obj.has(shelfKey)) {
                val shelf = obj.optJSONObject(shelfKey) ?: continue
                val title = shelfTitleOf(shelf)
                harvestContinuations(shelf, state, isRadioLane = false)
                walkChildren(shelf, state, depth, title)
                return
            }
        }

        // 2. The radio lane panel: its own continuation is the preferred one.
        if (obj.has("playlistPanelRenderer")) {
            val panel = obj.optJSONObject("playlistPanelRenderer") ?: return
            harvestContinuations(panel, state, isRadioLane = true)
            if (state.radioLaneContinuation == null) {
                state.radioLaneContinuation = harvestFirstContinuation(panel)
            }
            walkChildren(panel, state, depth, shelfTitle)
            return
        }

        // 3. Track renderers anywhere in the tree.
        for (rendererKey in TRACK_RENDERER_KEYS) {
            if (obj.has(rendererKey)) {
                val track = extractTrack(obj.optJSONObject(rendererKey), rendererKey, shelfTitle)
                if (track != null && state.seen.add(track.videoId)) {
                    state.flat.add(track)
                    state.shelves.getOrPut(track.shelfTitle ?: "") { mutableListOf() }.add(track)
                }
                // Track renderers are leaves: still descend for safety (menus
                // sometimes nest a radio seed inside).
                if (state.seen.size < state.maxTracks) {
                    walkChildren(obj.optJSONObject(rendererKey) ?: return, state, depth, shelfTitle)
                }
                return
            }
        }

        // 4. Bare continuation commands (shelves' own "load more" handles).
        harvestContinuations(obj, state, isRadioLane = false)
        walkChildren(obj, state, depth, shelfTitle)
    }

    private fun walkChildren(obj: JSONObject, state: WalkState, depth: Int, shelfTitle: String?) {
        val keys = obj.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (key == "continuations" || key == "continuationItemRenderer") continue
            walk(obj.opt(key), state, depth + 1, shelfTitle)
        }
    }

    // ───────────────────────────────────── shelf titles and continuations

    private fun shelfTitleOf(shelf: JSONObject): String? {
        // musicCarouselShelfRenderer.header.musicCarouselShelfBasicHeaderRenderer.title.runs
        val header = shelf.optJSONObject("header") ?: shelf.optJSONObject("contents")?.let { null }
        val basicHeader = header?.optJSONObject("musicCarouselShelfBasicHeaderRenderer")
            ?: header?.optJSONObject("musicResponsiveHeaderRenderer")
            ?: shelf.optJSONObject("title")
        val runs = basicHeader?.optJSONObject("title")?.optJSONArray("runs")
            ?: basicHeader?.optJSONArray("runs")
        val title = runs?.optJSONObject(0)?.optString("text", "")?.trim().orEmpty()
        return title.ifBlank { null } ?: basicHeader?.optString("title", "")?.trim()?.ifBlank { null }
    }

    private fun harvestContinuations(obj: JSONObject, state: WalkState, isRadioLane: Boolean) {
        val conts = obj.optJSONArray("continuations")
        if (conts != null) {
            for (i in 0 until conts.length()) {
                val c = conts.optJSONObject(i) ?: continue
                val token = c.optJSONObject("nextContinuationData")?.optString("continuation", "")
                    ?: c.optJSONObject("nextRadioContinuationData")?.optString("continuation", "")
                noteContinuation(token, state, isRadioLane)
            }
        }
        // The modern "load more" shape: a bare continuationItemRenderer
        // sibling whose continuationCommand carries the token — real /next
        // music shelves ship this form, so it must be harvested too.
        obj.optJSONObject("continuationItemRenderer")?.let { item ->
            val token = item.optJSONObject("continuationCommand")?.optString("token", "")
                ?: item.optJSONObject("nextRadioContinuationData")?.optString("continuation", "")
            noteContinuation(token, state, isRadioLane)
        }
    }

    private fun noteContinuation(token: String?, state: WalkState, isRadioLane: Boolean) {
        if (token.isNullOrBlank() || token in state.continuationTokens) return
        state.continuationTokens.add(token)
        if (isRadioLane && state.radioLaneContinuation == null) {
            state.radioLaneContinuation = token
        }
    }

    private fun harvestFirstContinuation(obj: JSONObject): String? {
        val conts = obj.optJSONArray("continuations") ?: return null
        for (i in 0 until conts.length()) {
            val c = conts.optJSONObject(i) ?: continue
            val token = c.optJSONObject("nextContinuationData")?.optString("continuation", "")
                ?: c.optJSONObject("nextRadioContinuationData")?.optString("continuation", "")
            if (!token.isNullOrBlank()) return token
        }
        return null
    }

    // ──────────────────────────────────────── per-renderer track extraction

    private fun extractTrack(
        r: JSONObject?,
        rendererKey: String,
        shelfTitle: String?
    ): YouTubeMusicRadioApi.RadioTrack? {
        if (r == null) return null
        val videoId = extractVideoId(r) ?: return null
        val title = extractTitle(r).ifBlank { return null }
        val artist = extractArtist(r)
        val durationSec = extractDuration(r)
        val thumbnail = extractThumbnail(r, videoId)
        val category = if (shelfTitle == null) {
            YouTubeMusicRadioApi.ShelfCategory.RADIO_LANE
        } else {
            YouTubeMusicRadioApi.ShelfCategory.categorize(shelfTitle)
        }
        return YouTubeMusicRadioApi.RadioTrack(
            videoId = videoId,
            title = title,
            artist = artist,
            durationSec = durationSec,
            thumbnailUrl = thumbnail,
            shelfTitle = shelfTitle,
            category = category
        )
    }

    private fun extractVideoId(r: JSONObject): String? {
        // Direct fields first (playlistPanelVideoRenderer / videoRenderer).
        r.optString("videoId", "").takeIf { it.length == 11 }?.let { return it }
        // musicResponsiveListItemRenderer paths.
        r.optJSONObject("playlistItemData")?.optString("videoId", "")?.takeIf { it.length == 11 }
            ?.let { return it }
        // Overlay play button (musicResponsiveListItemRenderer).
        r.optJSONObject("overlay")?.optJSONObject("musicItemThumbnailOverlayRenderer")
            ?.optJSONObject("content")?.optJSONObject("musicPlayButtonRenderer")
            ?.optJSONObject("playNavigationEndpoint")?.optJSONObject("watchEndpoint")
            ?.optString("videoId", "")?.takeIf { it.length == 11 }?.let { return it }
        // Navigation endpoints (musicTwoRowItemRenderer & friends).
        r.optJSONObject("navigationEndpoint")?.optJSONObject("watchEndpoint")
            ?.optString("videoId", "")?.takeIf { it.length == 11 }?.let { return it }
        // Card shelf onTap.
        r.optJSONObject("onTap")?.optJSONObject("watchEndpoint")
            ?.optString("videoId", "")?.takeIf { it.length == 11 }?.let { return it }
        return null
    }

    private fun extractTitle(r: JSONObject): String {
        // playlistPanelVideoRenderer.title.runs[0].text
        r.optJSONObject("title")?.optJSONArray("runs")?.optJSONObject(0)?.optString("text", "")
            ?.let { return it.trim() }
        r.optJSONObject("title")?.optString("simpleText", "")?.let { return it.trim() }
        // musicResponsiveListItemRenderer flex column 0.
        r.optJSONArray("flexColumns")?.optJSONObject(0)
            ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
            ?.optJSONObject("text")?.optJSONArray("runs")?.optJSONObject(0)
            ?.optString("text", "")?.let { return it.trim() }
        return ""
    }

    private fun extractArtist(r: JSONObject): String {
        // playlistPanelVideoRenderer.longBylineText.runs[0] → artist.
        r.optJSONObject("longBylineText")?.optJSONArray("runs")?.optJSONObject(0)
            ?.optString("text", "")?.let { return clean(it) }
        // videoRenderer ownerText / longBylineText.
        r.optJSONObject("ownerText")?.optJSONArray("runs")?.optJSONObject(0)
            ?.optString("text", "")?.let { return clean(it) }
        // musicResponsiveListItemRenderer flex column 1.
        r.optJSONArray("flexColumns")?.optJSONObject(1)
            ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
            ?.optJSONObject("text")?.optJSONArray("runs")?.optJSONObject(0)
            ?.optString("text", "")?.let { return clean(it) }
        // musicTwoRowItemRenderer subtitle.
        r.optJSONObject("subtitle")?.optJSONArray("runs")?.optJSONObject(0)
            ?.optString("text", "")?.let { return clean(it) }
        return ""
    }

    private fun clean(raw: String): String {
        var c = raw.trim()
        c = Regex("(?i)\\s*-\\s*topic$").replace(c, "")
        c = Regex("(?i)\\s*vevo$").replace(c, "")
        return c.trim()
    }

    private fun extractDuration(r: JSONObject): Int {
        // playlistPanelVideoRenderer.lengthText.
        r.optJSONObject("lengthText")?.let { return parseClock(it.optString("simpleText", "")) }
        // videoRenderer.lengthText.simpleText.
        r.optJSONObject("lengthText")?.optJSONArray("runs")?.optJSONObject(0)
            ?.optString("text", "")?.let { return parseClock(it) }
        // musicResponsiveListItemRenderer fixed column.
        r.optJSONArray("fixedColumns")?.optJSONObject(0)
            ?.optJSONObject("musicResponsiveListItemFixedColumnRenderer")
            ?.optJSONObject("text")?.optJSONArray("runs")?.optJSONObject(0)
            ?.optString("text", "")?.let { return parseClock(it) }
        // Fallback: scan flex columns for a clock-looking run.
        val flex = r.optJSONArray("flexColumns") ?: return 0
        for (i in 0 until flex.length()) {
            val runs = flex.optJSONObject(i)
                ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
                ?.optJSONObject("text")?.optJSONArray("runs") ?: continue
            for (j in 0 until runs.length()) {
                val text = runs.optJSONObject(j)?.optString("text", "")?.trim() ?: continue
                if (text.matches(Regex("\\d+:\\d+(:\\d+)?"))) return parseClock(text)
            }
        }
        return 0
    }

    /** "1:02:03" → 3723; "3:45" → 225; garbage → 0. */
    fun parseClock(text: String): Int {
        val parts = text.split(":")
        if (parts.size !in 2..3) return 0
        val seconds = parts.last().toIntOrNull() ?: return 0
        val minutes = parts.getOrNull(parts.size - 2)?.toIntOrNull() ?: return 0
        val hours = if (parts.size == 3) parts[0].toIntOrNull() ?: 0 else 0
        if (seconds !in 0..59 || minutes < 0 || hours < 0) return 0
        return hours * 3600 + minutes * 60 + seconds
    }

    private fun extractThumbnail(r: JSONObject, videoId: String): String {
        // playlistPanelVideoRenderer.thumbnail.thumbnails[last]
        r.optJSONObject("thumbnail")?.optJSONArray("thumbnails")?.let { arr ->
            (arr.length() - 1).coerceAtLeast(0).let { last ->
                arr.optJSONObject(last)?.optString("url", "")?.takeIf { it.isNotBlank() }?.let { return it }
            }
        }
        // musicThumbnailRenderer nesting.
        r.optJSONObject("thumbnailRenderer")?.optJSONObject("musicThumbnailRenderer")
            ?.optJSONObject("thumbnail")?.optJSONArray("thumbnails")?.let { arr ->
                (arr.length() - 1).coerceAtLeast(0).let { last ->
                    arr.optJSONObject(last)?.optString("url", "")?.takeIf { it.isNotBlank() }?.let { return it }
                }
            }
        // videoRenderer.thumbnail.thumbnails.
        r.optJSONObject("thumbnail")?.optJSONArray("thumbnails")?.let { arr ->
            arr.optJSONObject(0)?.optString("url", "")?.takeIf { it.isNotBlank() }?.let { return it }
        }
        return "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"
    }
}
