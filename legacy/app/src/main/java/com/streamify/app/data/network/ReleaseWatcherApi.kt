package com.streamify.app.data.network

import android.content.Context
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
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * ReleaseWatcherApi — upcoming-release scraper + Pre-Save store (Phase 3, 1)
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * Scrapes an artist's upcoming releases (albums / EPs / singles) together
 * with their countdown timers, powering:
 *  • the countdown banner + Pre-Save button on ArtistScreen,
 *  • the "Your Updates" hub feed for followed artists,
 *  • pre-save alerts when the release goes live.
 *
 * Transports (client-fleet fallback on each):
 *  • /browse  — the artist channel "Albums and singles" / "Upcoming
 *    releases" shelves (musicTwoRowItemRenderer with release-date or
 *    countdown subtitle runs).
 *  • /search  — artist resolution when no browseId is known.
 *
 * Countdown decoding — YouTube encodes release windows in several rotating
 * shapes; all are normalized to an epoch-millis ETA:
 *  • ISO-8601 duration fragments: "P1DT12H30M", "PT10M", "P14D"
 *  • HUD text: "3D 2H", "in 5 days", "2 hours left", "Tomorrow", "Tonight"
 *  • Relative badge runs: "Coming soon", "Pre-release", "Out now" (→ live)
 *
 * 🛡️ Anti-failure architecture: multi-client header fallback, defensive
 * recursive JSON traversal (opt* only), stale-while-revalidate cache (2h
 * TTL — countdowns must be fresh-ish), and a local JSON PreSaveStore that
 * survives restarts. The object NEVER throws.
 */
object ReleaseWatcherApi {

    private const val TAG = "ReleaseWatcherApi"

    private const val BROWSE_URL =
        "https://music.youtube.com/youtubei/v1/browse?key=AIzaSyC9XL3ZjWddXya6X74dJoCTL-WEKFD3UVk"
    private const val SEARCH_URL =
        "https://music.youtube.com/youtubei/v1/search?key=AIzaSyC9XL3ZjWddXya6X74dJoCTL-WEKFD3UVk"

    private const val DESKTOP_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    private const val ANDROID_MUSIC_UA = "com.google.android.apps.youtube.music/8.6.53.52 (Linux; U; Android 14) US"

    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    /** Countdown accuracy matters — 2h TTL instead of the usual 6h. */
    internal val swr: SwrCache by lazy { SwrCache(dir = null) }
    private const val WATCH_CACHE_TTL_MS = 2L * 60 * 60 * 1000

    // ────────────────────────────────────────────────────────────── model

    /** One upcoming or just-released item on an artist page. */
    data class ReleaseCandidate(
        val releaseId: String,
        val browseId: String = "",
        val artistName: String = "",
        val title: String,
        val releaseType: String = "Single",
        /** Epoch-millis ETA; null when the payload has no countdown at all. */
        val expectedAtMs: Long? = null,
        val rawCountdownText: String = "",
        val coverUrl: String = "",
        val trackCount: Int = 0
    ) {
        val isLive: Boolean
            get() = expectedAtMs == null || expectedAtMs <= System.currentTimeMillis()

        /** Countdown millis remaining; 0 when live. */
        fun millisRemaining(nowMs: Long = System.currentTimeMillis()): Long =
            ((expectedAtMs ?: nowMs) - nowMs).coerceAtLeast(0L)

        fun countdownLabel(nowMs: Long = System.currentTimeMillis()): String {
            val remaining = millisRemaining(nowMs)
            if (remaining <= 0L) return "Out now"
            return ReleaseWatcherApi.formatCountdown(remaining)
        }
    }

    /** Watch result for one artist. */
    data class ReleaseWatch(
        val artistName: String = "",
        val artistBrowseId: String = "",
        val releases: List<ReleaseCandidate> = emptyList(),
        val fromCache: Boolean = false
    ) {
        /** The next upcoming release (soonest ETA), or null when all live. */
        val nextUpcoming: ReleaseCandidate?
            get() = releases.filter { !it.isLive }.minByOrNull { it.expectedAtMs ?: Long.MAX_VALUE }
    }

    /** InnerTube client spec for the watcher fleet. */
    internal data class WatcherClientSpec(
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
     * Watches an artist's releases (SWR-cached 2h). Resolves the channel id
     * via search when [browseId] is unknown.
     */
    suspend fun watchArtist(artistName: String, browseId: String? = null): ReleaseWatch {
        val clean = artistName.trim()
        if (clean.isBlank() && browseId.isNullOrBlank()) return ReleaseWatch()
        val cacheKey = "watch_${(browseId ?: clean).lowercase()}"
        val cached = swr.swr(
            key = cacheKey,
            ttlMs = WATCH_CACHE_TTL_MS,
            fetch = { watchToCanonicalJson(resolveWatchFleet(clean, browseId)) }
        ) ?: return ReleaseWatch(artistName = clean)
        return canonicalJsonToWatch(cached)
    }

    // ─────────────────────────────────────────── fleet fallback internals

    internal suspend fun resolveWatchFleet(artistName: String, browseId: String?): ReleaseWatch {
        var channelId = browseId?.takeIf { it.isNotBlank() }
        if (channelId == null) channelId = resolveArtistChannelId(artistName)
        if (channelId != null) {
            for (spec in clientFleet()) {
                val root = executeBrowse(spec, channelId) ?: continue
                val releases = ReleaseResponseParser.parseBrowse(root, artistName)
                if (releases.isNotEmpty()) {
                    return ReleaseWatch(artistName, channelId, releases)
                }
            }
        }
        return ReleaseWatch(artistName = artistName, artistBrowseId = channelId ?: "")
    }

    internal fun clientFleet(): List<WatcherClientSpec> {
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
            WatcherClientSpec(
                label = "WEB_REMIX",
                clientName = "WEB_REMIX",
                clientVersion = musicVersion,
                clientHeaderId = "67",
                origin = "https://music.youtube.com",
                userAgent = DESKTOP_UA
            ),
            WatcherClientSpec(
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

    internal suspend fun resolveArtistChannelId(artistName: String): String? {
        if (artistName.isBlank()) return null
        for (spec in clientFleet()) {
            val root = executeSearch(spec, artistName) ?: continue
            val id = ReleaseResponseParser.firstArtistChannelId(root)
            if (id != null) return id
        }
        return null
    }

    // ─────────────────────────────────────────────── InnerTube transports

    private fun requestJson(spec: WatcherClientSpec, extras: JSONObject.() -> Unit): JSONObject =
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

    internal suspend fun executeBrowse(spec: WatcherClientSpec, browseId: String): JSONObject? =
        withContext(Dispatchers.IO) {
            runCatching {
                val json = requestJson(spec) { put("browseId", browseId) }
                execute(spec, BROWSE_URL, json)
            }.getOrNull()
        }

    internal suspend fun executeSearch(spec: WatcherClientSpec, query: String): JSONObject? =
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
        spec: WatcherClientSpec,
        url: String,
        requestJson: JSONObject
    ): JSONObject? {
        val request = Request.Builder()
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

        NetworkEngine.client.newCall(request).execute().use { response ->
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

    // ─────────────────────────────────────────────────── countdown math

    /**
     * Normalizes any countdown text shape (ISO-8601, HUD, wordy, tomorrow)
     * into a RELATIVE duration in millis, or null when unparseable.
     */
    fun parseCountdownMillis(text: String): Long? = CountdownCodec.toMillis(text)

    /**
     * Formats remaining millis as a Spotify-style countdown:
     * "3d 02h", "02h 14m", "14m 09s", "09s".
     */
    fun formatCountdown(remainingMs: Long): String {
        if (remainingMs <= 0L) return "Out now"
        val days = TimeUnit.MILLISECONDS.toDays(remainingMs)
        val hours = TimeUnit.MILLISECONDS.toHours(remainingMs) % 24
        val minutes = TimeUnit.MILLISECONDS.toMinutes(remainingMs) % 60
        val seconds = TimeUnit.MILLISECONDS.toSeconds(remainingMs) % 60
        return when {
            days > 0 -> "%dd %02dh".format(days, hours)
            hours > 0 -> "%02dh %02dm".format(hours, minutes)
            minutes > 0 -> "%02dm %02ds".format(minutes, seconds)
            else -> "%02ds".format(seconds)
        }
    }

    // ────────────────────────────────────── canonical cache serialization

    private fun watchToCanonicalJson(watch: ReleaseWatch): String {
        val root = JSONObject()
        root.put("artist", watch.artistName)
        root.put("browse", watch.artistBrowseId)
        val arr = JSONArray()
        watch.releases.forEach { r ->
            arr.put(
                JSONObject()
                    .put("rid", r.releaseId)
                    .put("bid", r.browseId)
                    .put("t", r.title)
                    .put("rt", r.releaseType)
                    .put("eta", r.expectedAtMs ?: 0L)
                    .put("raw", r.rawCountdownText)
                    .put("cover", r.coverUrl)
                    .put("tc", r.trackCount)
            )
        }
        root.put("releases", arr)
        return root.toString()
    }

    private fun canonicalJsonToWatch(json: String): ReleaseWatch = runCatching {
        val root = JSONObject(json)
        val releases = ArrayList<ReleaseCandidate>()
        val arr = root.optJSONArray("releases") ?: JSONArray()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val releaseId = o.optString("rid", "")
            val title = o.optString("t", "")
            if (releaseId.isBlank() || title.isBlank()) continue
            releases.add(
                ReleaseCandidate(
                    releaseId = releaseId,
                    browseId = o.optString("bid", ""),
                    artistName = root.optString("artist", ""),
                    title = title,
                    releaseType = o.optString("rt", "Single"),
                    expectedAtMs = o.optLong("eta", 0L).takeIf { it > 0L },
                    rawCountdownText = o.optString("raw", ""),
                    coverUrl = o.optString("cover", ""),
                    trackCount = o.optInt("tc", 0)
                )
            )
        }
        ReleaseWatch(
            artistName = root.optString("artist", ""),
            artistBrowseId = root.optString("browse", ""),
            releases = releases,
            fromCache = true
        )
    }.getOrDefault(ReleaseWatch(fromCache = true))
}

/**
 * Pure parser for ReleaseWatcherApi — no network, JVM-unit-testable.
 *
 * Recognized release item signatures on artist /browse pages:
 *  • musicTwoRowItemRenderer with a subtitle run mentioning a release
 *    window (countdown text / ISO duration / "coming soon" / "out now")
 *  • musicResponsiveListItemRenderer under an "Upcoming releases" shelf
 *  • musicCarouselShelfRenderer / shelfRenderer whose header mentions
 *    "release", then the item renderers nested under it
 */
object ReleaseResponseParser {

    private val UPCOMING_HINTS = listOf(
        "upcoming", "coming soon", "pre-release", "pre-save", "preorder",
        "new releases", "album", "single", "ep"
    )
    private val LIVE_HINTS = listOf("out now", "new album", "just dropped", "released")

    /** Parses an artist /browse response for release candidates. */
    fun parseBrowse(root: JSONObject, artistName: String): List<ReleaseWatcherApi.ReleaseCandidate> {
        val items = LinkedHashMap<String, ReleaseWatcherApi.ReleaseCandidate>()
        CanvasResponseParser.walk(root) { node, parentKey ->
            if (node !is JSONObject) return@walk
            if (parentKey == null) return@walk
            val candidate = when (parentKey) {
                "musicTwoRowItemRenderer" -> parseTwoRow(node, artistName)
                "musicResponsiveListItemRenderer" -> parseResponsiveListItem(node, artistName)
                else -> null
            } ?: return@walk
            if (candidate.releaseId.isBlank() || candidate.title.isBlank()) return@walk
            items.putIfAbsent(candidate.releaseId, candidate)
        }
        return items.values.toList()
    }

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

    // ─────────────────────────────────────────── release item signatures

    /**
     * musicTwoRowItemRenderer: title.runs + subtitle.runs where the last
     * run is usually "Album • P1DT12H" / "Single • Out now".
     */
    private fun parseTwoRow(node: JSONObject, artistName: String): ReleaseWatcherApi.ReleaseCandidate? {
        val title = readRuns(node.optJSONObject("title"))
        if (title.isBlank()) return null
        val subtitleRuns = readAllRuns(node.optJSONObject("subtitle"))
        if (subtitleRuns.isEmpty()) return null
        // Only release-ish rows count — playlist rows have no date signal.
        val lastRun = subtitleRuns.last().lowercase()
        val hasSignal = UPCOMING_HINTS.any { lastRun.contains(it) } ||
            LIVE_HINTS.any { lastRun.contains(it) } ||
            ReleaseWatcherApi.parseCountdownMillis(subtitleRuns.joinToString(" ")) != null
        if (!hasSignal) return null

        val releaseType = subtitleRuns.firstOrNull()
            ?.substringBefore("•")?.trim()?.ifBlank { null }
            ?.replaceFirstChar { it.uppercase() }
            ?: "Single"
        val releaseId = node.optJSONObject("navigationEndpoint")
            ?.optJSONObject("browseEndpoint")?.optString("browseId", "")
            ?.ifBlank { null }
            ?: title.lowercase().replace(" ", "_")

        val countdownText = subtitleRuns.last()
        // Countdowns decode to RELATIVE durations — anchor them to the epoch.
        val expectedAt = ReleaseWatcherApi.parseCountdownMillis(countdownText)
            ?.plus(System.currentTimeMillis())
        val trackCount = subtitleRuns.firstOrNull { Regex("^\\d+\\s+tracks?$").containsMatchIn(it) }
            ?.let { Regex("^(\\d+)").find(it)?.groupValues?.get(1)?.toIntOrNull() } ?: 0
        val cover = node.optJSONObject("thumbnailRenderer")
            ?.optJSONObject("musicThumbnailRenderer")
            ?.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
            ?.let { bestThumb(it) } ?: ""

        return ReleaseWatcherApi.ReleaseCandidate(
            releaseId = releaseId,
            browseId = node.optJSONObject("navigationEndpoint")
                ?.optJSONObject("browseEndpoint")?.optString("browseId", "") ?: "",
            artistName = artistName,
            title = title,
            releaseType = releaseType,
            expectedAtMs = expectedAt,
            rawCountdownText = countdownText,
            coverUrl = cover,
            trackCount = trackCount
        )
    }

    /** musicResponsiveListItemRenderer under an "Upcoming releases" shelf. */
    private fun parseResponsiveListItem(node: JSONObject, artistName: String): ReleaseWatcherApi.ReleaseCandidate? {
        val title = readFlexColumns(node, 0)
        if (title.isBlank()) return null
        val subline = readFlexColumns(node, 1)
        if (subline.isBlank()) return null
        val signal = UPCOMING_HINTS.any { subline.lowercase().contains(it) } ||
            LIVE_HINTS.any { subline.lowercase().contains(it) } ||
            ReleaseWatcherApi.parseCountdownMillis(subline) != null
        if (!signal) return null

        val releaseId = node.optJSONObject("navigationEndpoint")
            ?.optJSONObject("browseEndpoint")?.optString("browseId", "")
            ?.ifBlank { null }
            ?: title.lowercase().replace(" ", "_")

        return ReleaseWatcherApi.ReleaseCandidate(
            releaseId = releaseId,
            browseId = node.optJSONObject("navigationEndpoint")
                ?.optJSONObject("browseEndpoint")?.optString("browseId", "") ?: "",
            artistName = artistName,
            title = title,
            releaseType = subline.substringBefore("•").trim().ifBlank { "Single" },
            expectedAtMs = ReleaseWatcherApi.parseCountdownMillis(subline.substringAfterLast("•").trim())
                ?.plus(System.currentTimeMillis()),
            rawCountdownText = subline.substringAfterLast("•").trim(),
            coverUrl = node.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
                ?.let { bestThumb(it) } ?: ""
        )
    }

    // ──────────────────────────────────────────────────── helper decoding

    private fun readRuns(node: JSONObject?): String {
        node ?: return ""
        node.optString("simpleText", "").takeIf { it.isNotBlank() }?.let { return it }
        return readAllRuns(node).joinToString("")
    }

    private fun readAllRuns(node: JSONObject?): List<String> {
        node ?: return emptyList()
        val runs = node.optJSONArray("runs") ?: return emptyList()
        return (0 until runs.length()).mapNotNull { runs.optJSONObject(it)?.optString("text", "") }
    }

    private fun readFlexColumns(node: JSONObject, index: Int): String {
        val columns = node.optJSONArray("flexColumns") ?: return ""
        if (index >= columns.length()) return ""
        return readRuns(columns.optJSONObject(index)?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
            ?.optJSONObject("text"))
    }

    private fun bestThumb(thumbnails: JSONArray): String =
        (0 until thumbnails.length())
            .mapNotNull { thumbnails.optJSONObject(it)?.optString("url", "") }
            .filter { it.isNotBlank() }
            .maxByOrNull { it.length } ?: ""
}

/**
 * Local pre-save store — persists "notify + pre-add this release" intents
 * as a JSON file so they survive restarts and power the "Your Updates" hub.
 * Injectable [file] for JVM unit tests.
 */
object PreSaveStore {

    private const val TAG = "PreSaveStore"
    private const val FILE_NAME = "release_presaves.json"

    private var storeFile: File? = null
    private val lock = Any()

    data class PreSave(
        val releaseId: String,
        val artistName: String,
        val title: String,
        val expectedAtMs: Long?,
        val savedAtMs: Long = System.currentTimeMillis()
    )

    fun init(context: Context) {
        synchronized(lock) {
            storeFile = File(context.filesDir, FILE_NAME)
        }
    }

    /** Test / alternate-dir injection. */
    fun initWith(file: File?) {
        synchronized(lock) {
            storeFile = file
        }
    }

    fun addPreSave(preSave: PreSave) {
        val current = loadAll().toMutableList()
        current.removeAll { it.releaseId == preSave.releaseId }
        current.add(preSave)
        persist(current)
    }

    fun removePreSave(releaseId: String) {
        val current = loadAll().toMutableList()
        current.removeAll { it.releaseId == releaseId }
        persist(current)
    }

    fun isPreSaved(releaseId: String): Boolean =
        loadAll().any { it.releaseId == releaseId }

    fun loadAll(): List<PreSave> {
        val file = synchronized(lock) { storeFile } ?: return emptyList()
        return runCatching {
            if (!file.exists()) return emptyList()
            val arr = JSONArray(file.readText(Charsets.UTF_8))
            val out = ArrayList<PreSave>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val rid = o.optString("rid", "")
                if (rid.isBlank()) continue
                out.add(
                    PreSave(
                        releaseId = rid,
                        artistName = o.optString("artist", ""),
                        title = o.optString("title", ""),
                        expectedAtMs = o.optLong("eta", 0L).takeIf { it > 0L },
                        savedAtMs = o.optLong("saved", System.currentTimeMillis())
                    )
                )
            }
            out
        }.getOrElse {
            SLog.d(TAG, "PreSaveStore.loadAll failed (${it.message})")
            emptyList()
        }
    }

    private fun persist(items: List<PreSave>) {
        val file = synchronized(lock) { storeFile } ?: return
        runCatching {
            val arr = JSONArray()
            items.forEach {
                arr.put(
                    JSONObject()
                        .put("rid", it.releaseId)
                        .put("artist", it.artistName)
                        .put("title", it.title)
                        .put("eta", it.expectedAtMs ?: 0L)
                        .put("saved", it.savedAtMs)
                )
            }
            file.parentFile?.mkdirs()
            file.writeText(arr.toString(), Charsets.UTF_8)
        }.onFailure {
            SLog.d(TAG, "PreSaveStore.persist failed (${it.message})")
        }
    }
}

/**
 * Normalizes any countdown text shape into a relative epoch-millis ETA.
 * Pure functions — JVM-unit-testable without any store or clock.
 */
internal object CountdownCodec {
    val ISO_DURATION = Regex("^P(?!$)(?:(\\d+)Y)?(?:(\\d+)M)?(?:(\\d+)W)?(?:(\\d+)D)?(?:T(?!$)(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+(?:\\.\\d+)?)S)?)?$")
    val HUD = Regex("(\\d+)\\s*([dDhHmMsSwW])(?!\\w)")
    val WORDY = Regex("(\\d+)\\s*(day|days|hour|hours|minute|minutes|second|seconds|week|weeks)", RegexOption.IGNORE_CASE)
    val TOMORROW = Regex("\\btomorrow\\b|\\btonight\\b", RegexOption.IGNORE_CASE)

    fun toMillis(text: String): Long? {
        val t = text.trim()
        if (t.isBlank()) return null

        // 0. Guard: durations are the countdown signal — but "Out now"/
        //    "Coming soon" must never match the HUD or wordy decoders.
        val lower = t.lowercase()
        if ("out now" in lower || "released" in lower) return null

        // 1. ISO-8601 duration: P1DT12H30M / PT10M / P14D / P2W
        ISO_DURATION.find(t)?.let { m ->
            val (y, mo, w, d, h, mi, s) = m.destructured
            var total = 0.0
            total += (y.toIntOrNull() ?: 0) * 365.25 * 24 * 3600
            total += (mo.toIntOrNull() ?: 0) * 30.44 * 24 * 3600
            total += (w.toIntOrNull() ?: 0) * 7 * 24 * 3600
            total += (d.toIntOrNull() ?: 0) * 24 * 3600
            total += (h.toIntOrNull() ?: 0) * 3600
            total += (mi.toIntOrNull() ?: 0) * 60
            total += (s.toDoubleOrNull() ?: 0.0)
            if (total > 0) return (total * 1000).toLong()
        }

        // 2. HUD-style: "3D 2H", "48H", "5M"
        val hudMatches = HUD.findAll(t).toList()
        if (hudMatches.isNotEmpty()) {
            var totalSecs = 0L
            hudMatches.forEach { m ->
                val n = m.groupValues[1].toLongOrNull() ?: return@forEach
                totalSecs += when (m.groupValues[2].lowercase()) {
                    "w" -> n * 7 * 24 * 3600
                    "d" -> n * 24 * 3600
                    "h" -> n * 3600
                    "m" -> n * 60
                    "s" -> n
                    else -> 0L
                }
            }
            if (totalSecs > 0) return totalSecs * 1000
        }

        // 3. Wordy: "in 5 days", "2 hours left"
        val wordyMatches = WORDY.findAll(t).toList()
        if (wordyMatches.isNotEmpty()) {
            var totalSecs = 0L
            wordyMatches.forEach { m ->
                val n = m.groupValues[1].toLongOrNull() ?: return@forEach
                totalSecs += when (m.groupValues[2].lowercase().removeSuffix("s")) {
                    "week" -> n * 7 * 24 * 3600
                    "day" -> n * 24 * 3600
                    "hour" -> n * 3600
                    "minute" -> n * 60
                    "second" -> n
                    else -> 0L
                }
            }
            if (totalSecs > 0) return totalSecs * 1000
        }

        // 4. Tomorrow/tonight → 24h / 12h horizon
        if (TOMORROW.containsMatchIn(t)) {
            val hours = if (t.contains("tonight", ignoreCase = true)) 12L else 24L
            return hours * 3600 * 1000
        }

        return null
    }
}
