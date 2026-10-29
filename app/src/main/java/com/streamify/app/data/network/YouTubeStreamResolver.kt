package com.streamify.app.data.network

import com.streamify.app.util.SLog
import com.streamify.app.util.SLog as Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.zip.GZIPInputStream

data class ResolvedStream(
    val streamUrl: String,
    val mimeType: String,
    val bitrate: Int,
    val durationSec: Int,
    /** YouTube's own loudness measurement for this exact stream (dB, rel −14 LUFS ref). */
    val loudnessDb: Float? = null
)

data class ThumbnailDescriptor(
    val primary: String?,
    val secondary: String?,
    val fallbackColorSeed: Int
)

class UnresolvableTrackException(msg: String = "Unable to resolve playable audio stream") : Exception(msg)

object YouTubeStreamResolver {

    private const val INNERTUBE_PLAYER_URL = "https://www.youtube.com/youtubei/v1/player"

    // AUTHENTICATED RESOLUTION HOOK (2026 bot-wall): set once at app start.
    // Returns (sapisidhashHeader, rawCookies). When present, both player
    // executors attach Authorization/Cookie/X-Origin — the only combination
    // that still returns adaptiveFormats.
    @Volatile
    var ytSessionProvider: (() -> Pair<String, String>)? = null

    private fun attachYtSession(builder: Request.Builder) {
        val session = try { ytSessionProvider?.invoke() } catch (_: Throwable) { null } ?: return
        val (auth, cookies) = session
        if (!auth.isNullOrBlank()) {
            builder.header(
                "Authorization",
                if (auth.startsWith("SAPISIDHASH")) auth else "SAPISIDHASH $auth"
            )
            builder.header("X-Origin", "https://music.youtube.com")
        }
        if (!cookies.isNullOrBlank()) {
            builder.header("Cookie", cookies)
        }
    }

    object ResolverPolicy {
        @Volatile var useNegativeCache = false
        @Volatile var useCircuitBreaker = false
        @Volatile var strikeLockEnabled = false
    }

    /** Baked-in fallback; FleetConfig may override at runtime (release-free adaptation). */
    private const val DEFAULT_SIGNATURE_TIMESTAMP = 19850
    @Volatile
    var signatureTimestampOverride: Int? = null
    private fun signatureTimestamp(): Int =
        com.streamify.app.util.FleetConfig.signatureTimestamp(DEFAULT_SIGNATURE_TIMESTAMP)

    private const val USER_AGENT_ANDROID = "com.google.android.apps.youtube.music/6.42.52 (Linux; U; Android 14; en_US) gzip"

    /** Attached at app start; used by forensics for connectivity snapshots. */
    @Volatile
    var appContext: android.content.Context? = null
    private val JSON_MEDIA_TYPE = "application/json; charset=UTF-8".toMediaType()

    private data class ClientConfig(
        val clientName: String,
        val clientVersion: String,
        val clientNumber: String,
        val userAgent: String,
        val deviceMake: String? = null,
        val deviceModel: String? = null,
        val osName: String? = null,
        val osVersion: String? = null,
        val origin: String? = null,
        val referer: String? = null,
        // Zero-token fleet (live-probe verified 2026-08-25): bare ANDROID/IOS 21.x
        // return playable URLs while SAPISIDHASH+Cookie on these clients triggers
        // bot-walls and stale 19.x fingerprints are rejected with HTTP 400.
        // Session attach stays opt-in per-profile for diagnostics only.
        val attachSession: Boolean = false
    )

    private fun defaultAudioTargets() = listOf(
        // 1. Android App: probe-verified direct playback on official/topic music videos
        // Fingerprints restored from build-157 (6ffa6c4) — the last build solving
        // fast. Stale 19.x clients get HTTP 400 "Precondition check failed".
        ClientConfig(
            clientName = "ANDROID",
            clientVersion = "21.26.364",
            clientNumber = "3",
            userAgent = "com.google.android.youtube/21.26.364 (Linux; U; Android 11) gzip",
            osName = "Android",
            osVersion = "11"
        ),
        // 2. Meta Quest / Android VR: Verified unencrypted direct Opus & AAC CDN streams
        ClientConfig(
            clientName = "ANDROID_VR",
            clientVersion = "1.60.19",
            clientNumber = "28",
            userAgent = "Mozilla/5.0 (Linux; Android 12; Quest 3) AppleWebKit/537.36 (KHTML, like Gecko) OculusBrowser/33.0.0.19.46.568453472 SamsungBrowser/4.0 Chrome/122.0.6261.139 Mobile VR Safari/537.36",
            deviceMake = "Oculus",
            deviceModel = "Quest 3",
            osName = "Android",
            osVersion = "12"
        ),
        // 3. Native iOS YouTube App
        ClientConfig(
            clientName = "IOS",
            clientVersion = "21.26.4",
            clientNumber = "5",
            userAgent = "com.google.ios.youtube/21.26.4 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X;)",
            deviceMake = "Apple",
            deviceModel = "iPhone16,2",
            osName = "iPhone",
            osVersion = "18.3.2.22D82"
        )
    )

    // Release-free adaptation: FleetConfig (remote JSON, canary-maintained)
    // overrides the fleet when present; baked defaults remain the fallback.
    private fun audioTargets(): List<ClientConfig> =
        com.streamify.app.util.FleetConfig.audioTargets(defaultAudioTargets().map { it.toSpec() })
            .map { it.toClientConfig() }

    private fun videoTargets(): List<ClientConfig> =
        com.streamify.app.util.FleetConfig.videoTargets(defaultVideoTargets().map { it.toSpec() })
            .map { it.toClientConfig() }

    private fun ClientConfig.toSpec() = com.streamify.app.util.FleetConfig.ClientSpec(
        clientName, clientVersion, clientNumber, userAgent,
        deviceMake, deviceModel, osName, osVersion
    )

    private fun com.streamify.app.util.FleetConfig.ClientSpec.toClientConfig() = ClientConfig(
        clientName = clientName,
        clientVersion = clientVersion,
        clientNumber = clientNumber,
        userAgent = userAgent,
        deviceMake = deviceMake,
        deviceModel = deviceModel,
        osName = osName,
        osVersion = osVersion
    )

    // ========================================================================
    // INVARIANT 1: STORAGE GATEKEEPER & IDENTITY SANITIZATION
    // ========================================================================
    fun sanitizeForStorage(rawIdentifier: String, title: String, artist: String, fallbackCover: String? = null): String {
        val trimmed = rawIdentifier.trim()
        if (trimmed.startsWith("/") || trimmed.startsWith("file://")) {
            return trimmed
        }

        val videoId = extractVideoId(trimmed, fallbackCover)
        if (videoId != null) {
            return "https://www.youtube.com/watch?v=$videoId"
        }

        if (trimmed.contains("googlevideo.com") || trimmed.contains("search_query=") || trimmed.isBlank()) {
            return "ytsearch:${title.trim()} ${artist.trim()}".trim()
        }

        return trimmed
    }


    fun sanitizeCoverUrl(rawUrl: String?, videoId: String?): String? {
        val effectiveVid = videoId ?: extractVideoId(rawUrl ?: "")
        if (rawUrl.isNullOrBlank()) {
            return effectiveVid?.let { "https://i.ytimg.com/vi/$it/maxresdefault.jpg" }
        }
        val trimmed = rawUrl.trim()
        return when {
            trimmed.contains("mzstatic.com") -> trimmed.replace(Regex("\\d+x\\d+bb"), "1400x1400bb")
            trimmed.contains("googleusercontent.com") || trimmed.contains("ggpht.com") -> {
                val cleanBase = trimmed.substringBefore("=")
                "$cleanBase=w1200-h1200-l90-rj"
            }
            trimmed.contains("googlevideo.com") -> effectiveVid?.let { "https://i.ytimg.com/vi/$it/maxresdefault.jpg" }
            trimmed.contains("ytimg.com") || trimmed.contains("vi_webp") -> {
                val vid = Regex("(?<=/vi/|/vi_webp/)[a-zA-Z0-9_-]{11}").find(trimmed)?.value ?: effectiveVid
                if (vid != null) {
                    "https://i.ytimg.com/vi/$vid/maxresdefault.jpg"
                } else {
                    trimmed
                }
            }
            else -> trimmed
        }
    }



    // ========================================================================
    // INVARIANT 4: 3-TIER IMAGE DEGRADATION DESCRIPTOR
    // ========================================================================
    fun buildThumbnailPipeline(
        rawUrl: String?,
        videoId: String?,
        title: String,
        artist: String
    ): ThumbnailDescriptor {
        val sanitizedPrimary = sanitizeCoverUrl(rawUrl, videoId)
        val vid = videoId ?: extractVideoId(rawUrl ?: "")
        val secondaryUrl = vid?.let { "https://i.ytimg.com/vi/$it/hq720.jpg" }
        val proceduralSeed = (title.trim().lowercase() + artist.trim().lowercase()).hashCode()

        return ThumbnailDescriptor(
            primary = sanitizedPrimary,
            secondary = secondaryUrl,
            fallbackColorSeed = proceduralSeed
        )
    }

    private val YT_ID_REGEX = Regex("^[a-zA-Z0-9_-]{11}$")
    private val YT_URL_REGEX = Regex("(?:[?&]v=|/v/|youtu\\.be/|/embed/|/shorts/|/live/|^)([a-zA-Z0-9_-]{11})(?:[&?#/]|$)")
    private val YT_THUMBNAIL_REGEX = Regex("(?:vi|vi_webp)/([a-zA-Z0-9_-]{11})")

    fun extractIdFromThumbnail(thumbnailUrl: String?): String? {
        if (thumbnailUrl.isNullOrBlank()) return null
        return YT_THUMBNAIL_REGEX.find(thumbnailUrl)?.groupValues?.getOrNull(1)
    }

    fun extractVideoId(urlOrId: String?, fallbackThumbnail: String? = null): String? {
        if (urlOrId.isNullOrBlank()) return extractIdFromThumbnail(fallbackThumbnail)
        val cleanInput = urlOrId.trim()
        if (cleanInput.startsWith("ytsearch:") || cleanInput.startsWith("online://")) {
            return extractIdFromThumbnail(fallbackThumbnail)
        }
        // 1. Direct 11-character video ID
        if (YT_ID_REGEX.matches(cleanInput)) {
            return cleanInput
        }
        // 2. Standard YouTube URL patterns
        val match = YT_URL_REGEX.find(cleanInput)?.groupValues?.getOrNull(1)
        if (match != null && YT_ID_REGEX.matches(match)) {
            return match
        }
        // 3. Fallback to thumbnail URL if input is a CDN URL (googlevideo.com) or custom scheme
        return extractIdFromThumbnail(fallbackThumbnail)
    }

    fun getCanonicalWatchUrl(urlOrId: String, fallbackThumbnail: String? = null): String? {
        val videoId = extractVideoId(urlOrId, fallbackThumbnail) ?: return null
        return "https://www.youtube.com/watch?v=$videoId"
    }

    fun parseExpiry(url: String): Long {
        if (url.isBlank()) return 0L
        val expireEpochSec = Regex("[?&]expire=([0-9]+)").find(url)?.groupValues?.getOrNull(1)?.toLongOrNull() ?: return 0L
        return expireEpochSec * 1000L
    }

    // 4-Hour Rule: Refreshes CDN streams before expiration with a 2-hour safety window
    fun isCdnExpired(url: String, safetyMarginMs: Long = 7_200_000L): Boolean {
        if (url.isBlank() || !url.startsWith("http")) return true
        val expireEpochMs = parseExpiry(url)
        if (expireEpochMs > 0L) {
            return System.currentTimeMillis() >= (expireEpochMs - safetyMarginMs)
        }
        return false
    }

    // ========================================================================
    // INVARIANT 2: UNIFIED JIT STREAM RESOLUTION CASCADE (sol1.2.3)
    // ========================================================================
    suspend fun resolveStreamJit(track: com.streamify.app.data.models.Track, forceFresh: Boolean = false): Result<ResolvedStream> = withContext(Dispatchers.IO) {
        // Tier 0: Offline Local File Exists
        if (track.filepath.startsWith("/") || track.filepath.startsWith("file://")) {
            val localFile = java.io.File(track.filepath.removePrefix("file://"))
            if (localFile.exists()) {
                return@withContext Result.success(
                    ResolvedStream(
                        streamUrl = track.filepath,
                        mimeType = "audio/mpeg",
                        bitrate = 320000,
                        durationSec = track.durationSec
                    )
                )
            }
        }

        // 1. Determine Video ID (or search online if missing/unresolved)
        var videoId = track.ytmVideoId?.takeIf { YT_ID_REGEX.matches(it) }
            ?: extractVideoId(track.filepath, track.coverArtPath)
        var wasUnpinnedSearch = false
        if (videoId == null) {
            val cleanQuery = if (track.filepath.startsWith("ytsearch:")) {
                wasUnpinnedSearch = true
                track.filepath.removePrefix("ytsearch:").trim()
            } else {
                "${track.title} ${track.artist}".trim()
            }

            if (cleanQuery.isNotBlank()) {
                val searchMatches = YouTubeMusicSearchApi.search(cleanQuery, maxResults = 5)
                val topMatch = searchMatches.firstOrNull {
                    it.type == com.streamify.app.viewmodel.SearchResultType.SONG || it.type == com.streamify.app.viewmodel.SearchResultType.VIDEO
                } ?: searchMatches.firstOrNull()
                if (topMatch != null) {
                    videoId = extractVideoId(topMatch.url, topMatch.thumbnail)
                }
            }
        }

        if (videoId == null) {
            SLog.e("LadderTrace", "${com.streamify.app.util.Trace.pfx()}❌ RESOLUTION FAILED for ${track.title} (No videoId found)")
            dumpForensics("no-videoId for '${track.title}'")
            return@withContext Result.failure(UnresolvableTrackException("No video ID could be found for ${track.title}"))
        }
        com.streamify.app.util.SLog.d(
            "LadderTrace",
            "${com.streamify.app.util.Trace.pfx()}resolve start vid=$videoId src=${if (wasUnpinnedSearch) "search" else "pinned"} title='${track.title}' dur=${track.durationSec}"
        )

        // Canonical Pinning: Lock resolved immutable Video ID in DB
        if (wasUnpinnedSearch && track.id > 0) {
            val canonicalWatchUrl = "https://www.youtube.com/watch?v=$videoId"
            kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
                try {
                    com.streamify.app.data.repository.TrackRepository.upsertStreamedTrack(
                        track.copy(filepath = canonicalWatchUrl, ytmVideoId = videoId)
                    )
                } catch (_: Exception) {}
            }
        }

        // 2. In-Memory LRU Cache with 4-Hour safety margin
        if (!forceFresh) {
            val cached = StreamEdgeCache.getStream(videoId)
            if (cached != null && !isCdnExpired(cached.streamUrl, safetyMarginMs = 600_000L)) {
                ConnectionWarmer.preWarmCDN(cached.streamUrl)
                return@withContext Result.success(cached)
            }
        } else {
            StreamEdgeCache.evictStream(videoId)
        }

        // 3. Tier 1: Native HTTP/2 Innertube Multi-Client Race (<80ms)
        val nativeResolved = raceClientEndpoints(videoId)
        if (nativeResolved != null && nativeResolved.streamUrl.isNotBlank()) {
            StreamEdgeCache.putStream(videoId, nativeResolved)
            ConnectionWarmer.preWarmCDN(nativeResolved.streamUrl)
            SLog.d(
                "LadderTrace",
                "${com.streamify.app.util.Trace.pfx()}R1 HIT vid=$videoId mime=${nativeResolved.mimeType.substringBefore(';')} br=${nativeResolved.bitrate}"
            )
            return@withContext Result.success(nativeResolved)
        }
        SLog.w("LadderTrace", "${com.streamify.app.util.Trace.pfx()}R1 MISS vid=$videoId — entering R-RP (RustyPipe pure-Rust extraction)")

        // 3.5 Tier R-RP: Pure-Rust RustyPipe extraction engine.
        // In-process QuickJS cipher deciphering + botguard PO tokens;
        // provides identical native resolution across Android, Desktop, and iOS.
        val rpResolved = try {
            resolveViaRustyPipe(videoId)
        } catch (t: Throwable) {
            SLog.e("LadderTrace", "${com.streamify.app.util.Trace.pfx()}R-RP threw for $videoId: ${t.message}")
            null
        }
        if (rpResolved != null && rpResolved.streamUrl.isNotBlank()) {
            StreamEdgeCache.putStream(videoId, rpResolved)
            ConnectionWarmer.preWarmCDN(rpResolved.streamUrl)
            SLog.i(
                "LadderTrace",
                "${com.streamify.app.util.Trace.pfx()}R-RP HIT vid=$videoId mime=${rpResolved.mimeType.substringBefore(';')} br=${rpResolved.bitrate}"
            )
            return@withContext Result.success(rpResolved)
        }
        SLog.w("LadderTrace", "${com.streamify.app.util.Trace.pfx()}R-RP MISS vid=$videoId — entering R2 alternate-upload search")

        // 4. Tier 2: Alternate-upload search fallback
        try {
            val altSearch = YouTubeMusicSearchApi.search("${track.title} ${track.artist}", maxResults = 5)
            var rejects = 0
            val candidates = altSearch.mapNotNull { c ->
                val cid = extractVideoId(c.url, c.thumbnail) ?: return@mapNotNull null
                if (cid == videoId) return@mapNotNull null
                val durationOk = track.durationSec <= 0 || c.duration <= 0 || kotlin.math.abs(track.durationSec - c.duration) <= 8
                if (!durationOk) {
                    if (rejects < 5) SLog.d("LadderTrace", "${com.streamify.app.util.Trace.pfx()}R2 reject vid=$cid reason=duration(${c.duration}s vs ${track.durationSec}s)")
                    rejects++
                    return@mapNotNull null
                }
                val titleSim = com.streamify.app.data.discovery.FuzzyTitleMatcher.calculateSimilarity(track.title.lowercase(), c.title.lowercase())
                if (titleSim < 0.25f) {
                    if (rejects < 5) SLog.d("LadderTrace", "${com.streamify.app.util.Trace.pfx()}R2 reject vid=$cid reason=titleSim=${"%.2f".format(titleSim)}")
                    rejects++
                    return@mapNotNull null
                }
                cid to titleSim
            }.sortedByDescending { it.second }.take(3)

            SLog.d("LadderTrace", "${com.streamify.app.util.Trace.pfx()}R2 candidates=${candidates.size} (from ${altSearch.size} results)")

            for ((candVideoId, _) in candidates) {
                val retryResolved = raceClientEndpoints(candVideoId)
                if (retryResolved != null && retryResolved.streamUrl.isNotBlank()) {
                    StreamEdgeCache.putStream(candVideoId, retryResolved)
                    ConnectionWarmer.preWarmCDN(retryResolved.streamUrl)
                    SLog.d("StreamifyResolver", "Resolved Tier 2 fallback for ${track.title} ($candVideoId)")

                    if (track.id > 0) {
                        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
                            try {
                                com.streamify.app.data.repository.TrackRepository.upsertStreamedTrack(
                                    track.copy(
                                        filepath = "https://www.youtube.com/watch?v=$candVideoId",
                                        ytmVideoId = candVideoId
                                    )
                                )
                            } catch (_: Exception) {}
                        }
                    }
                    return@withContext Result.success(retryResolved)
                }
            }
        } catch (e: Exception) {
            // R2 search fallback failed
        }

        SLog.e("LadderTrace", "${com.streamify.app.util.Trace.pfx()}EXHAUSTED vid=$videoId '${track.title}' — all tiers failed")
        dumpForensics("exhaustion for '${track.title}' (lastVid=$videoId)")
        return@withContext Result.failure(UnresolvableTrackException("Stream exhaustion for ${track.title} - ${track.artist}"))
    }

    /**
     * Failure bundle: environment snapshot + the most diagnostic recent lines.
     * One greppable block the admin can copy straight from the terminal.
     */
    private fun dumpForensics(reason: String) {
        try {
            val relevantTags = listOf("LadderTrace", "ResolveGate", "HTTP", "ExoEvent", "SearchVM")
            val recent = SLog.snapshotLines(400)
                .filter { line -> relevantTags.any { t -> "/$t:" in line } }
                .takeLast(40)
            val sb = StringBuilder()
            sb.appendLine("${com.streamify.app.util.Trace.pfx()}=== FORENSICS: $reason ===")
            sb.appendLine("${com.streamify.app.util.Trace.pfx()}env=android${android.os.Build.VERSION.SDK_INT} net=${networkOnline()}")
            for (line in recent) sb.appendLine("${com.streamify.app.util.Trace.pfx()}| ${line.take(240)}")
            sb.appendLine("${com.streamify.app.util.Trace.pfx()}=== END FORENSICS ===")
            SLog.e("FORENSICS", sb.toString())
        } catch (_: Throwable) {
        }
    }

    private fun networkOnline(): String =
        try {
            val nm = appContext?.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
            val net = nm?.activeNetwork
            val caps = net?.let { nm.getNetworkCapabilities(it) }
            when {
                caps == null -> "offline?"
                caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) -> "online"
                else -> "captive?"
            }
        } catch (_: Throwable) { "unknown" }

    suspend fun resolveStreamUrl(urlOrId: String, fallbackThumbnail: String? = null, forceFresh: Boolean = false): ResolvedStream? = withContext(Dispatchers.IO) {
        val dummyTrack = com.streamify.app.data.models.Track(
            id = 0,
            title = "",
            artist = "",
            album = "",
            durationSec = 0,
            filepath = urlOrId,
            coverArtPath = fallbackThumbnail
        )
        resolveStreamJit(dummyTrack, forceFresh = forceFresh).getOrNull()
    }

    suspend fun resolveTrackStream(track: com.streamify.app.data.models.Track, forceFresh: Boolean = false): ResolvedStream? = withContext(Dispatchers.IO) {
        resolveStreamJit(track, forceFresh = forceFresh).getOrNull()
    }

    /**
     * Tier R-RP: resolve through pure-Rust RustyPipe engine (in-process QuickJS
     * cipher deciphering + botguard PO token extraction, cross-platform parity).
     */
    private suspend fun resolveViaRustyPipe(videoId: String): ResolvedStream? =
        withContext(Dispatchers.IO) {
            val dbPath = appContext?.getDatabasePath("streamify_universal.db")?.absolutePath ?: ""
            SLog.d("ResolveGate", "${com.streamify.app.util.Trace.pfx()}R-RP NativeBridge.resolveStreamFormats($videoId)")

            val formats = com.streamify.app.data.NativeBridge.resolveStreamFormats(videoId, dbPath)
            if (formats.isNullOrEmpty()) {
                SLog.w("ResolveGate", "${com.streamify.app.util.Trace.pfx()}R-RP returned zero formats for $videoId")
                return@withContext null
            }

            // Prefer highest bitrate audio-only format (Opus/m4a), fallback to any audio
            val best = formats
                .filter { it.mimeType.startsWith("audio/") }
                .maxByOrNull { stream ->
                    val family = when {
                        stream.mimeType.contains("mp4") || stream.mimeType.contains("m4a") -> 2000
                        stream.mimeType.contains("webm") || stream.mimeType.contains("opus") -> 1500
                        else -> 500
                    }
                    family + stream.bitrate / 1000
                } ?: formats.firstOrNull()

            if (best == null || best.streamUrl.isBlank()) {
                SLog.w("ResolveGate", "${com.streamify.app.util.Trace.pfx()}R-RP zero usable stream URLs in format set")
                return@withContext null
            }

            best
        }

    private suspend fun raceClientEndpoints(videoId: String): ResolvedStream? = coroutineScope {
        val winnerDeferred = CompletableDeferred<ResolvedStream?>()

        val jobs = audioTargets().map { config ->
            async(Dispatchers.IO) {
                try {
                    val stream = executePlayerRequest(videoId, config)
                    if (stream != null && stream.streamUrl.isNotBlank()) {
                        winnerDeferred.complete(stream)
                    }
                } catch (e: Exception) {
                    // Ignore single client failure in race
                }
            }
        }

        jobs.forEach { job ->
            job.invokeOnCompletion {
                if (jobs.all { it.isCompleted } && !winnerDeferred.isCompleted) {
                    winnerDeferred.complete(null)
                }
            }
        }

        val winner = winnerDeferred.await()
        jobs.forEach { if (!it.isCompleted) it.cancel() }
        return@coroutineScope winner
    }

    private fun executePlayerRequest(
        videoId: String,
        config: ClientConfig,
        statuses: MutableCollection<String>? = null
    ): ResolvedStream? {
        try {
            SLog.d("ResolveGate", "${com.streamify.app.util.Trace.pfx()}race ${config.clientName}/${config.clientVersion} -> $videoId")
            val clientJson = JSONObject().apply {
                put("clientName", config.clientName)
                put("clientVersion", config.clientVersion)
                put("hl", "en")
                put("gl", "US")
                config.deviceMake?.let { put("deviceMake", it) }
                config.deviceModel?.let { put("deviceModel", it) }
                config.osName?.let { put("osName", it) }
                config.osVersion?.let { put("osVersion", it) }
                if (config.clientName.contains("ANDROID", ignoreCase = true)) {
                    put("androidSdkVersion", 34)
                }
            }

            val requestJson = JSONObject().apply {
                put("context", JSONObject().apply {
                    put("client", clientJson)
                })
                put("videoId", videoId)
                put("contentCheckOk", true)
                put("racyCheckOk", true)
                put("playbackContext", JSONObject().apply {
                    put("contentPlaybackContext", JSONObject().apply {
                        // STATIC, client-coupled STS -- do NOT make this dynamic.
                        // Control experiment (2026-08-24, same phone/network):
                        // legacy build sending 19850 resolves everything while HEAD
                        // sending the live web STS (20683) was uniformly rejected --
                        // an STS far newer than the client fingerprint's era reads
                        // as a bot signal to the player endpoint.
                        put("signatureTimestamp", signatureTimestamp())
                        put("html5Preference", "HTML5_PREF_WANTS")
                    })
                })
            }

            val reqBuilder = Request.Builder()
                .url(INNERTUBE_PLAYER_URL)
                .header("Content-Type", "application/json; charset=UTF-8")
                .header("User-Agent", config.userAgent)
                .header("Accept", "*/*")
                .header("X-YouTube-Client-Name", config.clientNumber)
                .header("X-YouTube-Client-Version", config.clientVersion)
                .also { if (config.attachSession) attachYtSession(it) }
                .post(requestJson.toString().toRequestBody(JSON_MEDIA_TYPE))

            if (!config.origin.isNullOrBlank()) {
                reqBuilder.header("Origin", config.origin)
            }
            if (!config.referer.isNullOrBlank()) {
                reqBuilder.header("Referer", config.referer)
            }

            val request = reqBuilder.build()

            // OkHttp Network Transport (Shared Connection Pool + Android OS DNS/IPv6/VPN)
            NetworkEngine.client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    SLog.w("ResolveGate", "${com.streamify.app.util.Trace.pfx()}race ${config.clientName} HTTP ${response.code}")
                    return null
                }

                val rawBytes = response.body?.bytes() ?: return null
                if (rawBytes.isEmpty()) return null

                val root = JSONObject(String(rawBytes, Charsets.UTF_8))
                val parsed = parsePlayerResponse(root)
                SLog.d(
                    "ResolveGate",
                    "${com.streamify.app.util.Trace.pfx()}race ${config.clientName} -> " +
                        (parsed?.let { "WIN mime=${it.mimeType.substringBefore(';')} br=${it.bitrate}" } ?: "null")
                )
                return parsed
            }
        } catch (e: Exception) {
            SLog.w("ResolveGate", "${com.streamify.app.util.Trace.pfx()}race ${config.clientName} EXC ${e.javaClass.simpleName}: ${e.message ?: ""}")
            return null
        }
    }

    private fun parsePlayerResponse(root: JSONObject): ResolvedStream? {
        try {
            val playabilityStatus = root.optJSONObject("playabilityStatus")
            val status = playabilityStatus?.optString("status", "")
            if (status != null && !status.equals("OK", ignoreCase = true)) {
                val reason = playabilityStatus.optString("reason", "")
                SLog.w("ResolveGate", "${com.streamify.app.util.Trace.pfx()}gated status=$status reason='$reason'")
                return null
            }

            val streamingData = root.optJSONObject("streamingData") ?: run {
                SLog.w("ResolveGate", "${com.streamify.app.util.Trace.pfx()}gated status=OK but NO streamingData")
                return null
            }
            val durationSec = root.optJSONObject("videoDetails")?.optString("lengthSeconds", "0")?.toIntOrNull() ?: 0

            val candidateFormats = mutableListOf<JSONObject>()

            // 1. Collect adaptive formats (pure audio streams)
            var directAudio = 0
            var urllessAudio = 0
            val adaptiveFormats = streamingData.optJSONArray("adaptiveFormats")
            if (adaptiveFormats != null) {
                for (i in 0 until adaptiveFormats.length()) {
                    val f = adaptiveFormats.getJSONObject(i)
                    val mime = f.optString("mimeType", "")
                    val streamUrl = extractUrlFromFormat(f)
                    if (mime.startsWith("audio/") && streamUrl.isNotBlank()) {
                        f.put("extractedUrl", streamUrl)
                        candidateFormats.add(f)
                        directAudio++
                    } else if (mime.startsWith("audio/")) {
                        urllessAudio++
                    }
                }
            }
            SLog.d(
                "ResolveGate",
                "${com.streamify.app.util.Trace.pfx()}parse status=OK adf=${adaptiveFormats?.length() ?: 0} directAudio=$directAudio urllessAudio=$urllessAudio"
            )

            // 2. Fallback to standard progressive formats (itag 18 / 22 carrying stereo AAC audio)
            if (candidateFormats.isEmpty()) {
                val formats = streamingData.optJSONArray("formats")
                if (formats != null) {
                    for (i in 0 until formats.length()) {
                        val f = formats.getJSONObject(i)
                        val streamUrl = extractUrlFromFormat(f)
                        if (streamUrl.isNotBlank()) {
                            f.put("extractedUrl", streamUrl)
                            candidateFormats.add(f)
                        }
                    }
                }
                SLog.d(
                    "ResolveGate",
                    "${com.streamify.app.util.Trace.pfx()}MUXED_FALLBACK triggered — progressive=${candidateFormats.size} (SABR stripped every adaptive URL)"
                )
            }

            if (candidateFormats.isEmpty()) return null

            // 3. Perceptual Codec Scoring Matrix (Opus 160k > AAC 128k > Low Bitrate > Progressive Containers)
            val bestFormat = candidateFormats.maxByOrNull { format ->
                val itag = format.optInt("itag", 0)
                val bitrate = format.optInt("bitrate", format.optInt("averageBitrate", 0))
                val mime = format.optString("mimeType", "")

                when (itag) {
                    251 -> 1000 + (bitrate / 1000) // WebM Opus (160kbps) - Studio Transparent
                    140 -> 850 + (bitrate / 1000)  // MP4 AAC (128kbps) - Universal Compatibility
                    250 -> 800 + (bitrate / 1000)  // WebM Opus (70kbps)
                    249 -> 750 + (bitrate / 1000)  // WebM Opus (50kbps)
                    139 -> 600 + (bitrate / 1000)  // MP4 AAC (48kbps)
                    22  -> 500 + (bitrate / 1000)  // 720p HD MP4 (AAC 192kbps)
                    18  -> 400 + (bitrate / 1000)  // 360p MP4 (AAC 96kbps)
                    else -> {
                        if (mime.contains("audio/webm") || mime.contains("opus")) 700 + (bitrate / 1000)
                        else if (mime.contains("audio/mp4") || mime.contains("m4a")) 650 + (bitrate / 1000)
                        else bitrate / 1000
                    }
                }
            } ?: candidateFormats.first()

            val streamUrl = bestFormat.optString("extractedUrl", bestFormat.optString("url", ""))
            val mimeType = bestFormat.optString("mimeType", "audio/webm")
            val bitrate = bestFormat.optInt("bitrate", bestFormat.optInt("averageBitrate", 160000))

            fun fmtLoud(obj: JSONObject): Float? {
                obj.optDouble("loudnessDb", Double.NaN).takeIf { !it.isNaN() }?.let { return it.toFloat() }
                obj.optJSONObject("volumeNormalizationInfo")?.optDouble("loudnessDb", Double.NaN)?.takeIf { !it.isNaN() }?.let { return it.toFloat() }
                return null
            }
            val loudness = fmtLoud(bestFormat)
                ?: root.optJSONObject("playerConfig")?.optJSONObject("audioConfig")
                    ?.optDouble("loudnessDb", Double.NaN)?.takeIf { !it.isNaN() }?.toFloat()

            if (streamUrl.isNotBlank()) {
                return ResolvedStream(
                    streamUrl = streamUrl,
                    mimeType = mimeType,
                    bitrate = bitrate,
                    durationSec = durationSec,
                    loudnessDb = loudness
                )
            }
            return null
        } catch (e: Exception) {
            return null
        }
    }

    private fun extractUrlFromFormat(format: JSONObject): String {
        val directUrl = format.optString("url", "")
        if (directUrl.isNotBlank()) {
            return directUrl
        }

        // Check for signatureCipher or cipher
        val cipher = format.optString("signatureCipher", format.optString("cipher", ""))
        if (cipher.isNotBlank()) {
            try {
                val params = cipher.split("&").associate { param ->
                    val pair = param.split("=", limit = 2)
                    if (pair.size == 2) {
                        pair[0] to java.net.URLDecoder.decode(pair[1], "UTF-8")
                    } else {
                        "" to ""
                    }
                }
                val rawUrl = params["url"]
                if (!rawUrl.isNullOrBlank()) {
                    val sig = params["s"]
                    val sp = params["sp"] ?: "sig"
                    return if (!sig.isNullOrBlank()) {
                        if (rawUrl.contains("?")) "$rawUrl&$sp=$sig" else "$rawUrl?$sp=$sig"
                    } else {
                        rawUrl
                    }
                }
            } catch (e: Exception) {
                // Ignore cipher parsing error
            }
        }
        return ""
    }


    private fun defaultVideoTargets() = listOf(
        ClientConfig(
            clientName = "ANDROID",
            clientVersion = "21.26.364",
            clientNumber = "3",
            userAgent = "com.google.android.youtube/21.26.364 (Linux; U; Android 11) gzip",
            osName = "Android",
            osVersion = "11"
        ),
        ClientConfig(
            clientName = "ANDROID_VR",
            clientVersion = "1.60.19",
            clientNumber = "28",
            userAgent = "Mozilla/5.0 (Linux; Android 12; Quest 3) AppleWebKit/537.36 (KHTML, like Gecko) OculusBrowser/33.0.0.19.46.568453472 SamsungBrowser/4.0 Chrome/122.0.6261.139 Mobile VR Safari/537.36",
            deviceMake = "Oculus",
            deviceModel = "Quest 3",
            osName = "Android",
            osVersion = "12"
        ),
        ClientConfig(
            clientName = "IOS",
            clientVersion = "21.26.4",
            clientNumber = "5",
            userAgent = "com.google.ios.youtube/21.26.4 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X;)",
            deviceMake = "Apple",
            deviceModel = "iPhone16,2",
            osName = "iPhone",
            osVersion = "18.3.2.22D82"
        )
    )

    suspend fun resolveVideoStreamUrl(track: com.streamify.app.data.models.Track): ResolvedStream? = withContext(Dispatchers.IO) {
        // 1. Strict Primary: Use track.ytmVideoId if valid, then extract from filepath/coverArt
        val directId = track.ytmVideoId?.takeIf { YT_ID_REGEX.matches(it) }
            ?: extractVideoId(track.filepath, track.coverArtPath)

        val videoId = if (!directId.isNullOrBlank() && YT_ID_REGEX.matches(directId)) {
            directId
        } else {
            CanonicalSeedResolver.resolveToCanonicalId(track).takeIf { it.matches(YT_ID_REGEX) }
        } ?: return@withContext null

        // 1. Zero-RTT Edge Cache Check
        val cached = StreamEdgeCache.getVideoStream(videoId)
        if (cached != null) {
            return@withContext cached
        }


        // 2. Parallel Standard Client Video Stream Racing (ANDROID_VR / IOS / TV)
        val resolved = raceClientVideoEndpoints(videoId)
        if (resolved != null && resolved.streamUrl.isNotBlank()) {
            StreamEdgeCache.putVideoStream(videoId, resolved)
            return@withContext resolved
        }

        return@withContext null
    }

    suspend fun resolveVideoStreamUrl(urlOrId: String, fallbackThumbnail: String? = null): ResolvedStream? = withContext(Dispatchers.IO) {
        val videoId = extractVideoId(urlOrId, fallbackThumbnail) ?: return@withContext null

        // 1. Zero-RTT Edge Cache Check
        val cached = StreamEdgeCache.getVideoStream(videoId)
        if (cached != null) {
            return@withContext cached
        }

        // 2. Parallel Client Video Stream Racing
        val resolved = raceClientVideoEndpoints(videoId)
        if (resolved != null) {
            StreamEdgeCache.putVideoStream(videoId, resolved)
        }
        return@withContext resolved
    }

    private suspend fun raceClientVideoEndpoints(videoId: String): ResolvedStream? = coroutineScope {
        val winnerDeferred = CompletableDeferred<ResolvedStream?>()

        val jobs = videoTargets().map { config ->
            async(Dispatchers.IO) {
                try {
                    val stream = executeVideoPlayerRequest(videoId, config)
                    if (stream != null && stream.streamUrl.isNotBlank()) {
                        winnerDeferred.complete(stream)
                    }
                } catch (e: Exception) {
                    // Ignore single client failure in race
                }
            }
        }

        jobs.forEach { job ->
            job.invokeOnCompletion {
                if (jobs.all { it.isCompleted } && !winnerDeferred.isCompleted) {
                    winnerDeferred.complete(null)
                }
            }
        }

        val winner = winnerDeferred.await()
        jobs.forEach { if (!it.isCompleted) it.cancel() }
        return@coroutineScope winner
    }

    private fun executeVideoPlayerRequest(videoId: String, config: ClientConfig): ResolvedStream? {
        try {
            val clientJson = JSONObject().apply {
                put("clientName", config.clientName)
                put("clientVersion", config.clientVersion)
                put("hl", "en")
                put("gl", "US")
                config.deviceMake?.let { put("deviceMake", it) }
                config.deviceModel?.let { put("deviceModel", it) }
                config.osName?.let { put("osName", it) }
                config.osVersion?.let { put("osVersion", it) }
                if (config.clientName.contains("ANDROID", ignoreCase = true)) {
                    put("androidSdkVersion", 34)
                }
            }

            val requestJson = JSONObject().apply {
                put("context", JSONObject().apply {
                    put("client", clientJson)
                })
                put("videoId", videoId)
                put("contentCheckOk", true)
                put("racyCheckOk", true)
                put("playbackContext", JSONObject().apply {
                    put("contentPlaybackContext", JSONObject().apply {
                        put("signatureTimestamp", signatureTimestamp())
                        put("html5Preference", "HTML5_PREF_WANTS")
                    })
                })
            }

            val reqBuilder = Request.Builder()
                .url(INNERTUBE_PLAYER_URL)
                .header("Content-Type", "application/json; charset=UTF-8")
                .header("User-Agent", config.userAgent)
                .header("Accept", "*/*")
                .header("X-YouTube-Client-Name", config.clientNumber)
                .header("X-YouTube-Client-Version", config.clientVersion)
                .also { if (config.attachSession) attachYtSession(it) }
                .post(requestJson.toString().toRequestBody(JSON_MEDIA_TYPE))

            if (!config.origin.isNullOrBlank()) {
                reqBuilder.header("Origin", config.origin)
            }
            if (!config.referer.isNullOrBlank()) {
                reqBuilder.header("Referer", config.referer)
            }

            val request = reqBuilder.build()

            NetworkEngine.client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body ?: return null
                val responseBody = body.string()

                if (responseBody.isBlank()) return null
                val root = JSONObject(responseBody)
                return parseVideoPlayerResponse(root)
            }
        } catch (e: Exception) {
            return null
        }
    }

    private fun parseVideoPlayerResponse(root: JSONObject): ResolvedStream? {
        try {
            val playabilityStatus = root.optJSONObject("playabilityStatus")
            val status = playabilityStatus?.optString("status", "")
            if (status != null && !status.equals("OK", ignoreCase = true)) {
                return null
            }

            val streamingData = root.optJSONObject("streamingData") ?: return null
            val durationSec = root.optJSONObject("videoDetails")?.optString("lengthSeconds", "0")?.toIntOrNull() ?: 0

            val formats = streamingData.optJSONArray("formats")
            val progressiveList = mutableListOf<JSONObject>()
            if (formats != null) {
                for (i in 0 until formats.length()) {
                    val f = formats.getJSONObject(i)
                    val url = extractUrlFromFormat(f)
                    if (url.isNotBlank()) {
                        f.put("extractedUrl", url)
                        progressiveList.add(f)
                    }
                }
            }

            // 1. Check progressive formats (itag 22 = 720p HD MP4 with AAC, itag 18 = 360p)
            val bestProgressive = progressiveList.firstOrNull { it.optInt("itag") == 22 }
                ?: progressiveList.firstOrNull { it.optInt("itag") == 18 }
                ?: progressiveList.firstOrNull()

            if (bestProgressive != null && bestProgressive.optInt("itag") == 22) {
                val streamUrl = bestProgressive.optString("extractedUrl", bestProgressive.optString("url", ""))
                val mimeType = bestProgressive.optString("mimeType", "video/mp4")
                val bitrate = bestProgressive.optInt("bitrate", 2500000)
                if (streamUrl.isNotBlank()) {
                    return ResolvedStream(
                        streamUrl = streamUrl,
                        mimeType = mimeType,
                        bitrate = bitrate,
                        durationSec = durationSec
                    )
                }
            }

            // 2. Check adaptive video streams for 1080p Full HD (itag 137/248) or 720p HD (itag 136/247)
            val adaptiveFormats = streamingData.optJSONArray("adaptiveFormats")
            val adaptiveVideoList = mutableListOf<JSONObject>()
            if (adaptiveFormats != null) {
                for (i in 0 until adaptiveFormats.length()) {
                    val f = adaptiveFormats.getJSONObject(i)
                    val mime = f.optString("mimeType", "")
                    if (mime.startsWith("video/")) {
                        val url = extractUrlFromFormat(f)
                        if (url.isNotBlank()) {
                            f.put("extractedUrl", url)
                            adaptiveVideoList.add(f)
                        }
                    }
                }
            }

            val bestAdaptiveVideo = adaptiveVideoList.firstOrNull { it.optInt("itag") == 137 } // 1080p MP4
                ?: adaptiveVideoList.firstOrNull { it.optInt("itag") == 248 } // 1080p WebM
                ?: adaptiveVideoList.firstOrNull { it.optInt("itag") == 136 } // 720p MP4
                ?: adaptiveVideoList.firstOrNull { it.optInt("itag") == 247 } // 720p WebM
                ?: adaptiveVideoList.maxByOrNull { it.optInt("bitrate", 0) }

            if (bestAdaptiveVideo != null) {
                val streamUrl = bestAdaptiveVideo.optString("extractedUrl", bestAdaptiveVideo.optString("url", ""))
                val mime = bestAdaptiveVideo.optString("mimeType", "video/mp4")
                val bitrate = bestAdaptiveVideo.optInt("bitrate", 4500000)
                if (streamUrl.isNotBlank()) {
                    return ResolvedStream(
                        streamUrl = streamUrl,
                        mimeType = mime,
                        bitrate = bitrate,
                        durationSec = durationSec
                    )
                }
            }

            // 3. Fallback to progressive MP4
            if (bestProgressive != null) {
                val streamUrl = bestProgressive.optString("extractedUrl", bestProgressive.optString("url", ""))
                val mimeType = bestProgressive.optString("mimeType", "video/mp4")
                val bitrate = bestProgressive.optInt("bitrate", 1200000)
                if (streamUrl.isNotBlank()) {
                    return ResolvedStream(
                        streamUrl = streamUrl,
                        mimeType = mimeType,
                        bitrate = bitrate,
                        durationSec = durationSec
                    )
                }
            }

            return null
        } catch (e: Exception) {
            return null
        }
    }
}
