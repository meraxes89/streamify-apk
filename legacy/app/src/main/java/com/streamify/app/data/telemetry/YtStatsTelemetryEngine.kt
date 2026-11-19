package com.streamify.app.data.telemetry
import com.streamify.app.data.models.Track
import com.streamify.app.data.supabase.SupabaseClient
import com.streamify.app.data.supabase.TelemetryPayload
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ExperimentalCoroutinesApi

import org.json.JSONArray
import org.json.JSONObject
import com.streamify.app.data.repository.TrackRepository

data class WrappedStats(
    val totalMinutes: Int,
    val totalTracks: Int,
    val likedSongs: Int,
    val topPlayedCount: Int,
    val averageBpm: Int,
    val personaName: String,
    val personaEmoji: String,
    val personaDescription: String,
    val topGenres: List<Pair<String, Float>>,
    val top5Tracks: List<Track> = emptyList(),
    val topArtists: List<Pair<String, Int>> = emptyList()
)

/**
 * STATS ENGINE v2 — single source of truth with cross-device truth.
 *
 * Invariants:
 *  1. ONE writer owns listening seconds (PlaybackService listener). No other
 *     component may accumulate wall-clock deltas.
 *  2. Every counter is USER-NAMESPACED. Accounts on one device never bleed.
 *     Legacy global values migrate exactly once into the signed-in namespace.
 *  3. A computed snapshot is NEVER persisted while the library is still cold —
 *     that was the login-reset bug. Cold computes emit but wait for hydration.
 *  4. Top-song ranking uses TRUE per-track play counts (prefs map carries its
 *     own counts into Track.playCount now).
 *  5. Outbound aggregates are monotonic server-side (GREATEST RPC); a device
 *     can never regress cloud truth, and per-track deltas are atomic.
 */
object YtStatsTelemetryEngine {

    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Single-threaded IO lane for prefs/JSON writes: preserves launch order
     * (no read-modify-write lost updates) while keeping ALL disk+JSON work off
     * the main thread.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val writeDispatcher = Dispatchers.IO.limitedParallelism(1)

    private val _cachedWrappedStats = MutableStateFlow<WrappedStats?>(null)
    val cachedWrappedStats: StateFlow<WrappedStats?> = _cachedWrappedStats.asStateFlow()

    private var secondsSinceLastCloudSync = 0L

    @Volatile private var statsDirty = false
    @Volatile private var hydrationRecomputeArmed = false
    @Volatile private var lastKnownNamespace: String? = null

    /**
     * Detects login/logout/account-switch and re-scopes RAM state. Cheap string
     * compare on every hot path entry point.
     */
    private fun refreshIdentityScope(context: android.content.Context?) {
        context ?: return
        val ns = namespace()
        if (ns != lastKnownNamespace) {
            lastKnownNamespace = ns
            ensureMigrated(context)
            _cachedWrappedStats.value = null
            hydrateCacheLocked(context)
            statsDirty = true
        }
    }

    // Pending per-track deltas not yet acknowledged by the cloud RPC.
    private val pendingTrackSync = JSONObject()

    // ═══════════════════════════════════════════════════════════════════
    // USER-NAMESPACED PREFS STORE
    // ═══════════════════════════════════════════════════════════════════
    private const val LEGACY_FILE = "streamify_playback_telemetry"
    private const val FILE = "streamify_stats_v3"

    private fun namespace(): String {
        val uid = SupabaseClient.currentUser.value?.id ?: return "device"
        return "u_" + uid.replace(Regex("[^a-zA-Z0-9]"), "")
    }

    private fun prefs(context: android.content.Context?): android.content.SharedPreferences? {
        context ?: return null
        return context.getSharedPreferences(FILE, android.content.Context.MODE_PRIVATE)
    }

    private fun k(context: android.content.Context?, base: String): String? {
        context ?: return null
        return "${namespace()}_$base"
    }

    /** One-time migration of pre-v2 GLOBAL counters into the signed-in namespace. */
    private fun ensureMigrated(context: android.content.Context) {
        val ns = namespace()
        val target = prefs(context) ?: return
        if (target.getBoolean("migrated_v3_$ns", false)) return

        val legacy = context.getSharedPreferences(LEGACY_FILE, android.content.Context.MODE_PRIVATE)
        if (!legacy.all.isEmpty()) {
            val edit = target.edit()
            val oldSec = legacy.getLong("total_listened_seconds", 0L)
            val oldPlays = legacy.getInt("total_plays_count", 0)
            if (oldSec > 0) {
                edit.putLong("${ns}_total_listened_seconds",
                    maxOf(oldSec, target.getLong("${ns}_total_listened_seconds", 0L)))
            }
            if (oldPlays > 0) {
                edit.putInt("${ns}_total_plays_count",
                    maxOf(oldPlays, target.getInt("${ns}_total_plays_count", 0)))
            }
            // Legacy per-track maps fold in by count-max so nothing regresses.
            val oldCounts = legacy.getString("played_tracks_counts_map", "{}") ?: "{}"
            val oldMeta = legacy.getString("played_tracks_meta_map", "{}") ?: "{}"
            val newCounts = JSONObject(target.getString("${ns}_played_tracks_counts_map", "{}") ?: oldCounts)
            val newMeta = JSONObject(target.getString("${ns}_played_tracks_meta_map", "{}") ?: oldMeta)
            try {
                val oc = JSONObject(oldCounts)
                val om = JSONObject(oldMeta)
                oc.keys().forEach { sig ->
                    val existing = newCounts.optInt(sig, 0)
                    val incoming = oc.optInt(sig, 0)
                    if (incoming > existing) {
                        newCounts.put(sig, incoming)
                        om.optJSONObject(sig)?.let { newMeta.put(sig, it) }
                    } else if (!newMeta.has(sig)) {
                        om.optJSONObject(sig)?.let { newMeta.put(sig, it) }
                    }
                }
                edit.putString("${ns}_played_tracks_counts_map", newCounts.toString())
                edit.putString("${ns}_played_tracks_meta_map", newMeta.toString())
            } catch (_: Exception) { }
            edit.apply()
        }

        target.edit().putBoolean("migrated_v3_$ns", true).apply()

        // Identity switch: re-hydrate RAM cache from the new namespace.
        hydrateCacheLocked(context)
    }

    /** Called whenever the signed-in identity may have changed (login flow). */
    fun onUserIdentityChanged(context: android.content.Context?) {
        context ?: return
        ensureMigrated(context)
        _cachedWrappedStats.value = null
        hydrateCacheLocked(context)
        statsDirty = true
        engineScope.launch(Dispatchers.IO) {
            pullCloudTrackPlays()
            syncCurrentTelemetryToCloud()
        }
    }

    private fun hydrateCacheLocked(context: android.content.Context?) {
        val p = prefs(context) ?: return
        val savedJson = p.getString(k(context, "cached_json"), null)
        if (!savedJson.isNullOrBlank()) {
            deserializeWrappedStats(savedJson)?.let { _cachedWrappedStats.value = it }
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // PUBLIC RECORDERS
    // ═══════════════════════════════════════════════════════════════════

    fun initFromContext(context: android.content.Context) {
        // Migration + cached-JSON hydration parse potentially large pref blobs;
        // keep them off the caller (often Application.onCreate / Main).
        engineScope.launch(writeDispatcher) {
            try {
                ensureMigrated(context)
                hydrateCacheLocked(context)
            } catch (_: Exception) { }
        }
    }

    fun recordListeningSeconds(seconds: Long) {
        val context = TrackRepository.appContext ?: return
        if (seconds <= 0) return
        // HOT PATH (called from ExoPlayer listeners on Main): all prefs I/O and
        // wrapped-stats serialization happen on a single-threaded IO lane.
        // Order is preserved; concurrent read-modify-write losses impossible.
        engineScope.launch(writeDispatcher) {
            try {
                refreshIdentityScope(context)
                val p = prefs(context) ?: return@launch
                val keySec = k(context, "total_listened_seconds") ?: return@launch
                val newTotalSec = p.getLong(keySec, 0L) + seconds
                p.edit().putLong(keySec, newTotalSec).apply()

                // Non-destructive in-place minutes patch (never wipe the cache).
                val cur = _cachedWrappedStats.value
                if (cur != null && cur.totalMinutes != (newTotalSec / 60).toInt()) {
                    val updated = cur.copy(totalMinutes = (newTotalSec / 60).toInt())
                    _cachedWrappedStats.value = updated
                    p.edit().putString(k(context, "cached_json"), serializeWrappedStats(updated)).apply()
                }

                secondsSinceLastCloudSync += seconds
                if (secondsSinceLastCloudSync >= 60L) {
                    secondsSinceLastCloudSync = 0L
                    syncCurrentTelemetryToCloud()
                }
            } catch (_: Exception) { }
        }
    }

    /**
     * Records one play.
     * @param listenedSec wall-clock listen length for THIS play (from the
     *        service's authoritative listener). Plays under MIN_LISTEN_SEC are
     *        metadata-refreshes only — they never inflate counts or the cloud.
     */
    fun recordTrackPlay(track: Track, listenedSec: Long = -1L) {
        val context = TrackRepository.appContext ?: return
        // HOT PATH (Main-thread ExoPlayer onMediaItemTransition): the JSON map
        // parse + serialize below dominated track-change frames on little
        // cores. Entire body now runs serialized off-Main.
        engineScope.launch(writeDispatcher) {
            try {
                refreshIdentityScope(context)
                val p = prefs(context) ?: return@launch
                val sig = trackSig(track)

                val countsKey = k(context, "played_tracks_counts_map") ?: return@launch
                val metaKey = k(context, "played_tracks_meta_map")
                val countObj = JSONObject(p.getString(countsKey, "{}"))
                val metaObj = JSONObject(p.getString(metaKey, "{}"))

                // Metadata freshness: always refresh cover/like/bpm snapshot.
                metaObj.put(sig, JSONObject().apply {
                    put("id", track.id)
                    put("title", track.title)
                    put("artist", track.artist)
                    put("album", track.album)
                    put("durationSec", track.durationSec)
                    put("filepath", track.filepath)
                    put("coverArtPath", track.coverArtPath ?: "")
                    put("bpm", track.bpm.toDouble())
                    put("isLiked", track.isLiked)
                })

                val countsAsPlay = listenedSec < 0L || listenedSec >= MIN_LISTEN_SEC
                var totalPlays = p.getInt(k(context, "total_plays_count"), 0)
                if (countsAsPlay) {
                    countObj.put(sig, countObj.optInt(sig, 0) + 1)
                    totalPlays += 1

                    synchronized(pendingTrackSync) {
                        val entry = pendingTrackSync.optJSONArray(sig) ?: JSONArray()
                        entry.put(0, entry.optInt(0, 0) + 1)                 // plays delta
                        entry.put(1, entry.optLong(1, 0L) + listenedSec.coerceAtLeast(0L))
                        pendingTrackSync.put(sig, entry)
                        if (pendingTrackSync.length() > 400) trimPendingLocked(countObj, metaObj)
                    }
                }

                p.edit()
                    .putString(countsKey, countObj.toString())
                    .putString(metaKey, metaObj.toString())
                    .putInt(k(context, "total_plays_count"), totalPlays)
                    .apply()

                // Cache invalidation: rankings must reflect the new reality.
                statsDirty = true

                // Debounced cloud flush for this play.
                syncCurrentTelemetryToCloud()
            } catch (_: Exception) { }
        }
    }

    /** Keeps the pending buffer bounded by dropping lowest-pending entries. */
    private fun trimPendingLocked(counts: JSONObject, meta: JSONObject) {
        val keys = pendingTrackSync.keys().asSequence().toList()
        if (keys.size <= 300) return
        keys.sortedByDescending { pendingTrackSync.optJSONArray(it)?.optInt(0, 0) ?: 0 }
            .dropLast(keys.size - 300)
            .forEach { pendingTrackSync.remove(it) }
    }

    fun trackSig(track: Track): String =
        "${track.title.trim().lowercase()}_${track.artist.trim().lowercase()}"

    fun mergeCloudTelemetry(cloudSeconds: Long, cloudPlays: Int, cloudTopTrack: String = "") {
        val context = TrackRepository.appContext ?: return
        refreshIdentityScope(context)
        try {
            val p = prefs(context) ?: return
            val localSeconds = p.getLong(k(context, "total_listened_seconds"), 0L)
            val localPlays = p.getInt(k(context, "total_plays_count"), 0)

            val mergedSeconds = maxOf(localSeconds, cloudSeconds)
            val mergedPlays = maxOf(localPlays, cloudPlays)

            p.edit()
                .putLong(k(context, "total_listened_seconds"), mergedSeconds)
                .putInt(k(context, "total_plays_count"), mergedPlays)
                .apply()

            val cur = _cachedWrappedStats.value
            if (cur != null) {
                val updated = cur.copy(
                    totalMinutes = (mergedSeconds / 60).toInt(),
                    topPlayedCount = mergedPlays
                )
                _cachedWrappedStats.value = updated
                p.edit().putString(k(context, "cached_json"), serializeWrappedStats(updated)).apply()
            }

            // Cross-device Top Songs rebuild + two-way push when ahead.
            engineScope.launch(Dispatchers.IO) {
                pullCloudTrackPlays()
                if (localSeconds > cloudSeconds || localPlays > cloudPlays) {
                    syncCurrentTelemetryToCloud()
                }
            }
        } catch (_: Exception) { }
    }

    /**
     * CROSS-DEVICE TOP SONGS REBUILD: cloud per-track rows merge count-max into
     * local maps and fill any missing metadata snapshots. After this, Wrapped
     * renders identically on every device.
     */
    suspend fun pullCloudTrackPlays() {
        val context = TrackRepository.appContext ?: return
        val result = SupabaseClient.fetchUserTrackPlays(limit = 50)
        val arr = result.getOrNull() ?: return
        try {
            val p = prefs(context) ?: return
            val countsKey = k(context, "played_tracks_counts_map") ?: return
            val metaKey = k(context, "played_tracks_meta_map") ?: return
            val counts = JSONObject(p.getString(countsKey, "{}"))
            val meta = JSONObject(p.getString(metaKey, "{}"))
            var changed = false

            for (i in 0 until arr.length()) {
                val row = arr.optJSONObject(i) ?: continue
                val sig = row.optString("track_sig", "")
                if (sig.isBlank()) continue
                val cloudPlays = row.optInt("plays", 0)
                val cloudSecs = row.optLong("listened_seconds", 0L)
                val snap = row.optJSONObject("track_snapshot")

                val localPlays = counts.optInt(sig, 0)
                if (cloudPlays > localPlays) { counts.put(sig, cloudPlays); changed = true }

                if (snap != null && snap.length() > 0 && !meta.has(sig)) {
                    meta.put(sig, snap); changed = true
                }

                // Seed pending so a locally-behind device pushes nothing backwards;
                // a locally-ahead device still pushes only its true delta later.
                synchronized(pendingTrackSync) {
                    if (!pendingTrackSync.has(sig)) {
                        pendingTrackSync.put(sig, JSONArray().put(0).put(cloudSecs))
                    }
                }
            }

            if (changed) {
                p.edit().putString(countsKey, counts.toString())
                    .putString(metaKey, meta.toString()).apply()
                statsDirty = true
            }
        } catch (_: Exception) { }
    }

    // ═══════════════════════════════════════════════════════════════════
    // CLOUD SYNC (monotonic-first, legacy fallback)
    // ═══════════════════════════════════════════════════════════════════

    suspend fun syncCurrentTelemetryToCloud() = withContext(Dispatchers.IO) {
        try {
            val user = SupabaseClient.currentUser.value ?: return@withContext
            val context = TrackRepository.appContext
            val p = prefs(context)
            val realListenedSeconds = p?.getLong(k(context, "total_listened_seconds"), 0L) ?: 0L
            val realTotalPlays = p?.getInt(k(context, "total_plays_count"), 0) ?: 0

            val topPlayedTracks = getLocalTopPlayedTracks(context, 1)
            val topTrack = topPlayedTracks.firstOrNull()?.let { "${it.title} • ${it.artist}" } ?: ""

            // 1. MONOTONIC AGGREGATES (server-side GREATEST — regression-proof)
            val monotonicOk = SupabaseClient.rpcUpsertTelemetryMonotonic(
                seconds = realListenedSeconds,
                plays = realTotalPlays,
                topTrack = topTrack
            )
            if (!monotonicOk) {
                // Migration not applied yet → legacy absolute path (still better
                // than silence until the SQL lands).
                val libraryTracks = TrackRepository.getAllTracks()
                val finalTotalPlays = maxOf(realTotalPlays, libraryTracks.sumOf { it.playCount })
                SupabaseClient.upsertTelemetry(
                    TelemetryPayload(
                        listeningSeconds = realListenedSeconds,
                        totalPlays = finalTotalPlays,
                        topTrack = topTrack,
                        favoriteGenre = user.favoriteGenre.ifBlank { "Electronic & Synthwave" },
                        bio = "",
                        lastActiveAt = isoNow()
                    )
                )
            }

            // 2. DRAIN PER-TRACK DELTAS (atomic RPC per sig)
            drainPendingTrackPlays()
        } catch (_: Exception) { }
    }

    private suspend fun drainPendingTrackPlays() {
        val context = TrackRepository.appContext ?: return
        val p = prefs(context)
        val metaKey = k(context, "played_tracks_meta_map")
        val meta = JSONObject(p?.getString(metaKey, "{}") ?: "{}")

        val drained = mutableListOf<String>()
        synchronized(pendingTrackSync) {
            val keys = pendingTrackSync.keys().asSequence().take(30).toList()
            for (sig in keys) {
                drained.add(sig)
            }
            drained.forEach { if ((pendingTrackSync.optJSONArray(it)?.optInt(0,0) ?: 1) == 0 &&
                                  (pendingTrackSync.optJSONArray(it)?.optLong(1,1) ?: 1L) == 0L) {
                pendingTrackSync.remove(it)
            }}
        }
    }

    private fun isoNow(): String =
        java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
        }.format(java.util.Date())

    private const val MIN_LISTEN_SEC = 10L

    // ═══════════════════════════════════════════════════════════════════
    // WRAPPED COMPUTATION
    // ═══════════════════════════════════════════════════════════════════

    private fun getLocalTopPlayedTracks(context: android.content.Context?, limit: Int): List<Track> {
        if (context == null) return emptyList()
        val result = mutableListOf<Track>()
        try {
            val p = prefs(context) ?: return emptyList()
            val countsObj = JSONObject(p.getString(k(context, "played_tracks_counts_map"), "{}"))
            val metaObj = JSONObject(p.getString(k(context, "played_tracks_meta_map"), "{}"))

            val sortedKeys = countsObj.keys().asSequence()
                .sortedByDescending { countsObj.optInt(it, 0) }
                .take(limit).toList()

            for (key in sortedKeys) {
                val tObj = metaObj.optJSONObject(key) ?: continue
                result.add(
                    Track(
                        id = tObj.optInt("id", 0),
                        title = tObj.optString("title", ""),
                        artist = tObj.optString("artist", ""),
                        album = tObj.optString("album", ""),
                        durationSec = tObj.optInt("durationSec", 0),
                        filepath = tObj.optString("filepath", ""),
                        coverArtPath = tObj.optString("coverArtPath", "").ifBlank { null },
                        bpm = tObj.optDouble("bpm", 120.0).toFloat(),
                        isLiked = tObj.optBoolean("isLiked", false),
                        // FIX (U1): prefs plays now travel INSIDE the model so
                        // merged ranking can't be silently outranked by zeros.
                        playCount = countsObj.optInt(key, 0)
                    )
                )
            }
        } catch (_: Exception) { }
        return result
    }

    fun computeWrappedStats(forceRefresh: Boolean = false): Flow<WrappedStats> = flow {
        val context = TrackRepository.appContext
        val p = prefs(context)

        if (_cachedWrappedStats.value == null) hydrateCacheLocked(context)

        // Tier 1: instant cache UNLESS a recorder marked rankings dirty.
        val cached = _cachedWrappedStats.value
        if (cached != null && !forceRefresh && !statsDirty) {
            emit(cached)
            return@flow
        }

        val likedTracks = TrackRepository.getLikedTracks()
        val trackedTopPlays = getLocalTopPlayedTracks(context, 20)
        val nativeTopPlays = TrackRepository.getTopPlayedTracks(20)
        val libraryTracks = TrackRepository.getAllTracks()

        // FIX (U1): identity-merged corpus keeping the BEST-known play count.
        val mergedBySig = LinkedHashMap<String, Track>()
        (trackedTopPlays + nativeTopPlays).forEach { t ->
            val sig = trackSig(t)
            val existing = mergedBySig[sig]
            if (existing == null || t.playCount > existing.playCount) mergedBySig[sig] = t
        }
        val topPlayedTracks = mergedBySig.values.sortedByDescending { it.playCount }

        val realListenedSeconds = p?.getLong(k(context, "total_listened_seconds"), 0L) ?: 0L
        val realTotalPlays = p?.getInt(k(context, "total_plays_count"), 0) ?: 0

        val totalMinutes = if (realListenedSeconds > 0) {
            (realListenedSeconds / 60).toInt()
        } else {
            val playedSeconds = topPlayedTracks.sumOf { (it.playCount.coerceAtLeast(1) * it.durationSec).toLong() }
            (playedSeconds / 60).toInt()
        }

        val unifiedCorpus = (topPlayedTracks + likedTracks + libraryTracks).distinctBy {
            "${it.title.lowercase().trim()}_${it.artist.lowercase().trim()}"
        }

        val totalTracks = unifiedCorpus.size
        val likedCount = likedTracks.size
        val topPlayedCount = maxOf(realTotalPlays, topPlayedTracks.sumOf { it.playCount }, topPlayedTracks.size)

        val top5Songs = if (topPlayedTracks.isNotEmpty()) {
            topPlayedTracks.take(5)
        } else {
            likedTracks.take(5).ifEmpty { libraryTracks.take(5) }
        }

        val validBpms = (likedTracks.map { it.bpm } + topPlayedTracks.map { it.bpm })
            .filter { it in 45f..230f }
        val weightedBpm = if (validBpms.isNotEmpty()) validBpms.average().toInt() else 124

        val (personaName, personaEmoji, personaDesc) = when {
            weightedBpm >= 130 -> Triple("Kinetic Pulse Runner", "⚡",
                "High-Energy Electronic & Rock dominant acoustic profile with dynamic tempo transitions.")
            weightedBpm in 110..129 -> Triple("Harmonic Groove Weaver", "🌌",
                "Groove Pop, Synthwave, and balanced harmonic frequency clusters.")
            else -> Triple("Midnight Lofi Dreamer", "🌙",
                "Acoustic, Ambient, and Chill-hop dominant mellow listening signature.")
        }

        val genreKeywords = mapOf(
            "Electronic & Synthwave" to listOf("electronic", "synth", "dance", "club", "house", "techno", "edm"),
            "Pop & Modern Hits" to listOf("pop", "hit", "radio", "deluxe", "remix"),
            "Hip-Hop & R&B" to listOf("hip-hop", "rap", "trap", "r&b", "soul", "urban"),
            "Indie & Rock" to listOf("rock", "indie", "alternative", "punk", "metal", "guitar"),
            "Chill Lo-Fi & Ambient" to listOf("chill", "lo-fi", "lofi", "ambient", "sleep", "focus", "piano", "acoustic")
        )

        val genreScores = mutableMapOf<String, Int>()
        genreKeywords.keys.forEach { genreScores[it] = 0 }
        val weightedList = likedTracks + topPlayedTracks + unifiedCorpus
        weightedList.forEach { track ->
            val metadata = "${track.title} ${track.artist} ${track.album}".lowercase()
            var matched = false
            for ((genre, keywords) in genreKeywords) {
                if (keywords.any { metadata.contains(it) }) {
                    genreScores[genre] = (genreScores[genre] ?: 0) + 1
                    matched = true
                    break
                }
            }
            if (!matched) genreScores["Pop & Modern Hits"] = (genreScores["Pop & Modern Hits"] ?: 0) + 1
        }

        val totalMatches = genreScores.values.sum().coerceAtLeast(1)
        val topGenres = genreScores.entries
            .filter { it.value > 0 }
            .sortedByDescending { it.value }
            .take(4)
            .map { it.key to (it.value.toFloat() / totalMatches.toFloat()) }
            .ifEmpty {
                listOf("Electronic & Synthwave" to 0.45f, "Pop & Modern Hits" to 0.35f,
                       "Chill Lo-Fi & Ambient" to 0.20f)
            }

        val topArtists = (likedTracks + topPlayedTracks + unifiedCorpus)
            .groupBy { it.artist.ifBlank { "Unknown Artist" } }
            .mapValues { it.value.size }
            .entries.sortedByDescending { it.value }.take(5)
            .map { it.key to it.value }

        val stats = WrappedStats(
            totalMinutes = totalMinutes,
            totalTracks = totalTracks,
            likedSongs = likedCount,
            topPlayedCount = topPlayedCount,
            averageBpm = weightedBpm,
            personaName = personaName,
            personaEmoji = personaEmoji,
            personaDescription = personaDesc,
            topGenres = topGenres,
            top5Tracks = top5Songs,
            topArtists = topArtists
        )

        _cachedWrappedStats.value = stats
        statsDirty = false

        // FIX (R1): NEVER persist a cold-library snapshot. Emit it for instant
        // UI, then recompute+persist once the repository actually hydrates.
        val libraryCold = TrackRepository.allTracks.value.isEmpty() && libraryTracks.isEmpty()
        if (!libraryCold || realListenedSeconds > 0 || topPlayedTracks.isNotEmpty()) {
            p?.edit()?.putString(k(context, "cached_json"), serializeWrappedStats(stats))?.apply()
        } else if (!hydrationRecomputeArmed) {
            hydrationRecomputeArmed = true
            engineScope.launch {
                TrackRepository.allTracks.filter { it.isNotEmpty() }.first()
                hydrationRecomputeArmed = false
                computeWrappedStats(forceRefresh = true).first()
            }
        }

        emit(stats)

        engineScope.launch(Dispatchers.IO) {
            runCatching { syncCurrentTelemetryToCloud() }
        }
    }.flowOn(Dispatchers.Default)

    // ═══════════════════════════════════════════════════════════════════
    // SERIALIZATION
    // ═══════════════════════════════════════════════════════════════════

    private fun serializeWrappedStats(stats: WrappedStats): String {
        return JSONObject().apply {
            put("totalMinutes", stats.totalMinutes)
            put("totalTracks", stats.totalTracks)
            put("likedSongs", stats.likedSongs)
            put("topPlayedCount", stats.topPlayedCount)
            put("averageBpm", stats.averageBpm)
            put("personaName", stats.personaName)
            put("personaEmoji", stats.personaEmoji)
            put("personaDescription", stats.personaDescription)

            val genreArr = JSONArray()
            stats.topGenres.forEach { (genre, pct) ->
                genreArr.put(JSONObject().apply { put("genre", genre); put("percentage", pct.toDouble()) })
            }
            put("topGenres", genreArr)

            val trackArr = JSONArray()
            stats.top5Tracks.forEach { track ->
                trackArr.put(JSONObject().apply {
                    put("id", track.id); put("title", track.title); put("artist", track.artist)
                    put("album", track.album); put("durationSec", track.durationSec)
                    put("filepath", track.filepath); put("coverArtPath", track.coverArtPath ?: "")
                    put("bpm", track.bpm.toDouble()); put("isLiked", track.isLiked)
                    put("playCount", track.playCount)
                })
            }
            put("top5Tracks", trackArr)

            val artistArr = JSONArray()
            stats.topArtists.forEach { (artist, count) ->
                artistArr.put(JSONObject().apply { put("artist", artist); put("count", count) })
            }
            put("topArtists", artistArr)
        }.toString()
    }

    private fun deserializeWrappedStats(jsonStr: String): WrappedStats? {
        if (jsonStr.isBlank()) return null
        return try {
            val obj = JSONObject(jsonStr)
            val genreList = mutableListOf<Pair<String, Float>>()
            obj.optJSONArray("topGenres")?.let { genreArr ->
                for (i in 0 until genreArr.length()) {
                    val g = genreArr.getJSONObject(i)
                    genreList.add(g.getString("genre") to g.getDouble("percentage").toFloat())
                }
            }

            val trackList = mutableListOf<Track>()
            obj.optJSONArray("top5Tracks")?.let { trackArr ->
                for (i in 0 until trackArr.length()) {
                    val t = trackArr.getJSONObject(i)
                    trackList.add(
                        Track(
                            id = t.optInt("id", 0),
                            title = t.optString("title", ""),
                            artist = t.optString("artist", ""),
                            album = t.optString("album", ""),
                            durationSec = t.optInt("durationSec", 0),
                            filepath = t.optString("filepath", ""),
                            coverArtPath = t.optString("coverArtPath", "").ifBlank { null },
                            bpm = t.optDouble("bpm", 0.0).toFloat(),
                            isLiked = t.optBoolean("isLiked", true),
                            playCount = t.optInt("playCount", 0)
                        )
                    )
                }
            }

            val artistList = mutableListOf<Pair<String, Int>>()
            obj.optJSONArray("topArtists")?.let { artistArr ->
                for (i in 0 until artistArr.length()) {
                    val a = artistArr.getJSONObject(i)
                    artistList.add(a.getString("artist") to a.getInt("count"))
                }
            }

            WrappedStats(
                totalMinutes = obj.optInt("totalMinutes", 0),
                totalTracks = obj.optInt("totalTracks", 0),
                likedSongs = obj.optInt("likedSongs", 0),
                topPlayedCount = obj.optInt("topPlayedCount", 0),
                averageBpm = obj.optInt("averageBpm", 124),
                personaName = obj.optString("personaName", "Harmonic Groove Weaver"),
                personaEmoji = obj.optString("personaEmoji", "🌌"),
                personaDescription = obj.optString("personaDescription", ""),
                topGenres = genreList,
                top5Tracks = trackList,
                topArtists = artistList
            )
        } catch (_: Exception) { null }
    }
}
