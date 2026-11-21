package com.streamify.app.data.discovery

import com.streamify.app.data.models.Track
import com.streamify.app.data.network.SwrCache
import com.streamify.app.data.network.YouTubeMusicRadioApi
import com.streamify.app.util.SLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Locale

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * DaylistScheduler — circadian time-bucket scheduler (Gap #20)
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * Maps the local device clock into 4 dynamic time buckets and scrapes YTM
 * mood-radio seeds combining [User Top Artist] x [Time Mood Keyword]:
 *
 *   Morning     (06:00 – 11:00)  Focus / Upbeat Acoustic / Morning Pop
 *   Afternoon   (11:00 – 17:00)  Workout / Energy / High Tempo Hits
 *   Evening     (17:00 – 22:00)  Chill R&B / Lo-Fi / Sunset Unwind
 *   Late Night  (22:00 – 06:00)  Ambient / Deep Sleep / Dream Lo-Fi
 *
 * The Daylist mutates through the day: every 4 hours the refresh slot
 * advances, rotating the hero adjective ("Acoustic Morning Chill Tuesday"
 * -> "Golden Morning Rise Tuesday") and regenerating the mood-seeded
 * tracklist. Successful builds persist in the SWR cache — an offline open
 * serves the cached shelf instantly while a background revalidation runs.
 *
 * All bucket math, mood-query mapping and hero-title generation are pure
 * functions in [DaylistTimeBuckets] — JVM-testable without a network.
 */

/** The four circadian daypart buckets. */
enum class DaylistBucket(
    val label: String,
    val titleCore: String,
    val moodKeywords: List<String>,
    val heroAdjectives: List<String>,
    val heroNoun: String
) {
    MORNING(
        label = "Morning",
        titleCore = "Morning",
        moodKeywords = listOf("Focus", "Upbeat Acoustic", "Morning Pop"),
        // Index 1 is the bucket's first slot (06:00–07:59) → the directive's
        // "Acoustic Morning Chill Tuesday" hero exactly.
        heroAdjectives = listOf("Golden", "Acoustic", "Fresh", "Sunlit"),
        heroNoun = "Chill"
    ),
    AFTERNOON(
        label = "Afternoon",
        titleCore = "Afternoon",
        moodKeywords = listOf("Workout", "Energy", "High Tempo Hits"),
        heroAdjectives = listOf("Electric", "Kinetic", "Upbeat", "Turbo"),
        heroNoun = "Drive"
    ),
    EVENING(
        label = "Evening",
        titleCore = "Evening",
        moodKeywords = listOf("Chill R&B", "Lo-Fi", "Sunset Unwind"),
        heroAdjectives = listOf("Sunset", "Velvet", "Amber", "Dusk"),
        heroNoun = "Unwind"
    ),
    LATE_NIGHT(
        label = "Late Night",
        titleCore = "Late Night",
        moodKeywords = listOf("Ambient", "Deep Sleep", "Dream Lo-Fi"),
        heroAdjectives = listOf("Midnight", "Dreaming", "Nocturne", "Lunar"),
        heroNoun = "Drift"
    );

    val gradientColors: Pair<Long, Long>
        get() = when (this) {
            MORNING -> 0xFF667EEA to 0xFFF6D365
            AFTERNOON -> 0xFFF97316 to 0xFFDC2626
            EVENING -> 0xFF7C3AED to 0xFFEC4899
            LATE_NIGHT -> 0xFF0EA5E9 to 0xFF1E1B4B
        }
}

/**
 * Pure circadian math — bucket edges, mood-query mapping, hero titles,
 * 4-hour refresh slots.
 */
object DaylistTimeBuckets {

    /** Bucket containing [hour] (0..23). Late Night wraps midnight. */
    fun bucketFor(hour: Int): DaylistBucket = when (hour) {
        in 6..10 -> DaylistBucket.MORNING
        in 11..16 -> DaylistBucket.AFTERNOON
        in 17..21 -> DaylistBucket.EVENING
        else -> DaylistBucket.LATE_NIGHT
    }

    /**
     * The 4-hour refresh slot (0..5 per day): the Daylist hero and
     * tracklist regenerate when the slot index advances.
     */
    fun refreshSlotFor(hour: Int): Int = (hour.coerceIn(0, 23) / 4)

    /**
     * Mood-radio seed queries: [User Top Artist] + [Time Mood Keyword].
     * Rotates keywords across artists so all three mood flavours of the
     * bucket appear, and falls back to pure mood keywords when the user
     * has no top artists yet (cold start).
     */
    fun moodQueries(bucket: DaylistBucket, topArtists: List<String>): List<String> {
        val artists = topArtists.map { it.trim() }.filter { it.isNotBlank() }.take(3)
        if (artists.isEmpty()) return bucket.moodKeywords
        return artists.mapIndexed { i, artist ->
            val keyword = bucket.moodKeywords[i % bucket.moodKeywords.size]
            "$artist $keyword"
        }
    }

    /**
     * Hero banner title, e.g. "Acoustic Morning Chill Tuesday". The
     * adjective rotates with the refresh slot so the title morphs every
     * 4 hours within the same bucket.
     */
    fun heroTitle(bucket: DaylistBucket, slot: Int, dayOfWeek: DayOfWeek): String {
        val adjective = bucket.heroAdjectives[slot % bucket.heroAdjectives.size]
        val day = dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH)
        return "$adjective ${bucket.titleCore} ${bucket.heroNoun} $day"
    }

    /** Descriptive sub-line under the hero title. */
    fun heroSubtitle(bucket: DaylistBucket, slot: Int): String =
        "${bucket.moodKeywords[slot % bucket.moodKeywords.size]} • Refreshes every 4 hours"
}

/** One Daylist track row (radio-scraped, playable). */
data class DaylistTrack(
    val videoId: String,
    val title: String,
    val artist: String,
    val durationSec: Int = 0,
    val thumbnailUrl: String = "",
    val seedQuery: String = ""
)

/** A generated Daylist shelf. */
data class Daylist(
    val bucket: DaylistBucket,
    val title: String,
    val subtitle: String,
    val tracks: List<DaylistTrack> = emptyList(),
    val generatedAtMs: Long = 0L,
    val fromCache: Boolean = false
)

/**
 * DaylistScheduler — orchestration + SWR persistence.
 */
object DaylistScheduler {

    private const val TAG = "Daylist"
    private const val CACHE_TTL_MS = 4L * 60 * 60 * 1000 // 4-hour morph cadence
    private const val MAX_PER_QUERY = 10
    private const val MAX_TRACKS = 30

    @Volatile var cacheDir: File? = null
    @Volatile var clock: () -> Long = { System.currentTimeMillis() }

    private val swr: SwrCache by lazy {
        SwrCache(
            dir = cacheDir,
            clock = clock,
            scope = kotlinx.coroutines.CoroutineScope(
                kotlinx.coroutines.SupervisorJob() + Dispatchers.IO
            )
        )
    }

    /**
     * Builds the Daylist for the current clock. SWR-cached per
     * (bucket, slot): offline / lossy-network opens serve the cached shelf
     * instantly while a background revalidation refreshes it.
     *
     * WS-D D6: when the cache is cold AND the network is down, the shelf
     * regenerates OFFLINE through the TasteEngine's circadian daylist
     * lane — the caller's local play history (no upload, no telemetry)
     * scored against the active daypart. [localHistory] is the caller's
     * own top-played snapshot; empty history degrades to the empty shelf.
     */
    suspend fun currentDaylist(
        hour: Int,
        dayOfWeek: DayOfWeek,
        topArtists: List<String>,
        maxTracks: Int = MAX_TRACKS,
        localHistory: List<Track> = emptyList()
    ): Daylist = withContext(Dispatchers.IO) {
        val bucket = DaylistTimeBuckets.bucketFor(hour)
        val slot = DaylistTimeBuckets.refreshSlotFor(hour)
        val key = "daylist_${bucket.name}_$slot"
        val canonical = swr.swr(
            key = key,
            ttlMs = CACHE_TTL_MS,
            fetch = {
                daylistToCanonicalJson(buildDaylistTracks(bucket, topArtists, maxTracks))
            }
        )
        if (canonical == null) {
            // Cold cache + network failure: the D6 offline lane — the
            // circadian engine re-ranks LOCAL history for the active
            // daypart, entirely on-device.
            Daylist(
                bucket = bucket,
                title = DaylistTimeBuckets.heroTitle(bucket, slot, dayOfWeek),
                subtitle = DaylistTimeBuckets.heroSubtitle(bucket, slot),
                tracks = buildOfflineDaylistTracks(localHistory, hour, maxTracks),
                generatedAtMs = clock()
            )
        } else {
            canonicalJsonToDaylist(canonical, bucket, slot, dayOfWeek)
        }
    }

    /**
     * WS-D D6: the offline circadian lane — local history through the
     * TasteEngine's daylist scoring (daypart tempo/energy/brightness
     * targets + freshness bias), each row carrying its D4 reason in the
     * seedQuery field. Pure function of (history, clock); no network.
     */
    internal fun buildOfflineDaylistTracks(
        localHistory: List<Track>,
        hour: Int,
        maxTracks: Int
    ): List<DaylistTrack> {
        if (localHistory.isEmpty()) return emptyList()
        val nowSec = clock() / 1000L
        // D7: excluded tracks never feed the daylist profile either.
        val guard = TasteProfileGuard.index()
        val candidates = localHistory.map { it.toTasteTrack(source = 1, guard = guard) }
        val rows = TasteLane.generateDaylist(
            candidates = candidates,
            nowSec = nowSec,
            hourOfDay = hour,
            targetCount = maxTracks,
            profile = TasteLane.CircadianProfileKt.classic()
        )
        return rows.map { row ->
            DaylistTrack(
                videoId = "",
                title = row.track.title,
                artist = row.track.artist,
                seedQuery = row.reason
            )
        }
    }

    /** Live track harvest: concurrent mood-query radios, interleaved. */
    internal suspend fun buildDaylistTracks(
        bucket: DaylistBucket,
        topArtists: List<String>,
        maxTracks: Int
    ): List<DaylistTrack> = coroutineScope {
        val queries = DaylistTimeBuckets.moodQueries(bucket, topArtists)
        val pools = queries.map { query ->
            async(Dispatchers.IO) {
                runCatching { YouTubeMusicRadioApi.radioForQuery(query, maxTracks = MAX_PER_QUERY) }
                    .getOrDefault(YouTubeMusicRadioApi.RadioPage())
                    .tracks
                    .filter { it.title.isNotBlank() && it.artist.isNotBlank() }
                    .map { t ->
                        DaylistTrack(
                            videoId = t.videoId,
                            title = t.title,
                            artist = t.artist,
                            durationSec = t.durationSec,
                            thumbnailUrl = t.thumbnailUrl,
                            seedQuery = query
                        )
                    }
            }
        }.awaitAll()

        interleave(pools, maxTracks)
    }

    /**
     * Round-robin interleave across the mood-query pools with videoId +
     * fuzzy title/artist dedup — pure and directly unit-testable.
     */
    fun interleave(pools: List<List<DaylistTrack>>, limit: Int): List<DaylistTrack> {
        if (pools.isEmpty()) return emptyList()
        val seenVideo = HashSet<String>()
        val seenSong = HashSet<String>()
        val out = mutableListOf<DaylistTrack>()
        val maxLength = pools.maxOfOrNull { it.size } ?: 0
        for (depth in 0 until maxLength) {
            for (pool in pools) {
                if (depth >= pool.size) continue
                val t = pool[depth]
                if (t.videoId.isNotEmpty() && !seenVideo.add(t.videoId)) continue
                if (!seenSong.add("${t.title.trim().lowercase()}::${t.artist.trim().lowercase()}")) continue
                out.add(t)
                if (out.size >= limit) return out
            }
        }
        return out
    }

    // ─────────────────────────────────────────── canonical cache serialization

    private fun daylistToCanonicalJson(tracks: List<DaylistTrack>): String {
        val arr = JSONArray()
        tracks.forEach { t ->
            arr.put(
                JSONObject()
                    .put("v", t.videoId)
                    .put("t", t.title)
                    .put("a", t.artist)
                    .put("d", t.durationSec)
                    .put("th", t.thumbnailUrl)
                    .put("q", t.seedQuery)
            )
        }
        return JSONObject()
            .put("tracks", arr)
            .put("at", clock())
            .toString()
    }

    private fun canonicalJsonToDaylist(
        json: String,
        bucket: DaylistBucket,
        slot: Int,
        dayOfWeek: DayOfWeek
    ): Daylist = runCatching {
        val root = JSONObject(json)
        val arr = root.optJSONArray("tracks") ?: JSONArray()
        val tracks = ArrayList<DaylistTrack>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val videoId = o.optString("v", "")
            if (videoId.length != 11) continue
            tracks.add(
                DaylistTrack(
                    videoId = videoId,
                    title = o.optString("t", ""),
                    artist = o.optString("a", ""),
                    durationSec = o.optInt("d", 0),
                    thumbnailUrl = o.optString("th", ""),
                    seedQuery = o.optString("q", "")
                )
            )
        }
        Daylist(
            bucket = bucket,
            title = DaylistTimeBuckets.heroTitle(bucket, slot, dayOfWeek),
            subtitle = DaylistTimeBuckets.heroSubtitle(bucket, slot),
            tracks = tracks,
            generatedAtMs = root.optLong("at", 0L),
            fromCache = true
        )
    }.getOrElse {
        SLog.w(TAG, "daylist cache decode failed: ${it.message}")
        Daylist(
            bucket = bucket,
            title = DaylistTimeBuckets.heroTitle(bucket, slot, dayOfWeek),
            subtitle = DaylistTimeBuckets.heroSubtitle(bucket, slot),
            generatedAtMs = clock()
        )
    }
}
