package com.streamify.app.data.discovery

import android.content.Context
import com.streamify.app.data.models.Track
import com.streamify.app.util.SLog
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicReference

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * TasteProfileGuard — "Exclude from Taste Profile" (Gap #27)
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * The user steers the algorithm: tracks and playlists flagged
 * excludedFromTaste stop feeding the radio seed pool and telemetry
 * logging — play events, skip events, engagement/hook telemetry, session
 * vectors, and top-played / recommendation surfaces all consult this
 * guard first.
 *
 * [TasteExclusionIndex] is a pure immutable snapshot (JVM-testable);
 * [TasteProfileGuard] owns the atomic mirror + SharedPreferences
 * persistence ("taste_profile" JSON: {tracks:[…], playlists:[…]}).
 */
class TasteExclusionIndex(
    val excludedTrackKeys: Set<String> = emptySet(),
    val excludedPlaylistNames: Set<String> = emptySet()
) {

    fun isTrackExcluded(title: String, artist: String): Boolean =
        trackKey(title, artist) in excludedTrackKeys

    fun isTrackExcluded(track: Track): Boolean =
        isTrackExcluded(track.title, track.artist)

    fun isPlaylistExcluded(playlistName: String): Boolean =
        normalize(playlistName) in excludedPlaylistNames

    /** True when ANY of the ids resolve to an excluded track. */
    fun anyTrackIdExcluded(trackIds: List<Int>, catalogById: Map<Int, Track>): Boolean =
        trackIds.any { id -> catalogById[id]?.let { isTrackExcluded(it) } ?: false }

    fun withTrackExcluded(title: String, artist: String, excluded: Boolean): TasteExclusionIndex {
        val key = trackKey(title, artist)
        val next = if (excluded) excludedTrackKeys + key else excludedTrackKeys - key
        return TasteExclusionIndex(next, excludedPlaylistNames)
    }

    fun withPlaylistExcluded(playlistName: String, excluded: Boolean): TasteExclusionIndex {
        val key = normalize(playlistName)
        val next = if (excluded) excludedPlaylistNames + key else excludedPlaylistNames - key
        return TasteExclusionIndex(excludedTrackKeys, next)
    }

    companion object {
        /** "Title|||Artist" normalized — stable across local/online ids. */
        fun trackKey(title: String, artist: String): String =
            "${normalize(title)}|||${normalize(artist)}"

        fun normalize(raw: String): String =
            raw.trim().lowercase().replace(Regex("\\s+"), " ")
    }
}

object TasteProfileGuard {

    private const val TAG = "TasteProfile"
    private const val PREFS_NAME = "taste_profile"
    private const val KEY_JSON = "exclusions_json"

    private val indexRef = AtomicReference(TasteExclusionIndex())
    private var prefs: android.content.SharedPreferences? = null

    /** Current immutable snapshot — lock-free reads on the hot paths. */
    fun index(): TasteExclusionIndex = indexRef.get()

    fun initialize(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs?.getString(KEY_JSON, null)
        if (raw != null) {
            runCatching { decode(raw) }.onSuccess { indexRef.set(it) }
                .onFailure { SLog.w(TAG, "corrupt exclusions store: ${it.message}") }
        }
    }

    // ─────────────────────────────────────────────── mutation + persistence

    fun setTrackExcluded(track: Track, excluded: Boolean) {
        update { it.withTrackExcluded(track.title, track.artist, excluded) }
    }

    fun setTrackExcluded(title: String, artist: String, excluded: Boolean) {
        update { it.withTrackExcluded(title, artist, excluded) }
    }

    fun setPlaylistExcluded(playlistName: String, excluded: Boolean) {
        update { it.withPlaylistExcluded(playlistName, excluded) }
    }

    private fun update(mutation: (TasteExclusionIndex) -> TasteExclusionIndex) {
        var written = false
        while (!written) {
            val current = indexRef.get()
            written = indexRef.compareAndSet(current, mutation(current))
        }
        persist()
    }

    private fun persist() {
        val snapshot = indexRef.get()
        val store = prefs ?: return
        runCatching { store.edit().putString(KEY_JSON, encode(snapshot)).apply() }
            .onFailure { SLog.w(TAG, "exclusions persist failed: ${it.message}") }
    }

    private fun encode(index: TasteExclusionIndex): String {
        val tracks = JSONArray()
        index.excludedTrackKeys.forEach { tracks.put(it) }
        val playlists = JSONArray()
        index.excludedPlaylistNames.forEach { playlists.put(it) }
        return JSONObject()
            .put("tracks", tracks)
            .put("playlists", playlists)
            .toString()
    }

    private fun decode(raw: String): TasteExclusionIndex {
        val root = JSONObject(raw)
        val tracks = HashSet<String>()
        val trackArr = root.optJSONArray("tracks") ?: JSONArray()
        for (i in 0 until trackArr.length()) {
            trackArr.optString(i, "").takeIf { it.isNotBlank() }?.let { tracks.add(it) }
        }
        val playlists = HashSet<String>()
        val playlistArr = root.optJSONArray("playlists") ?: JSONArray()
        for (i in 0 until playlistArr.length()) {
            playlistArr.optString(i, "").takeIf { it.isNotBlank() }?.let { playlists.add(it) }
        }
        return TasteExclusionIndex(tracks, playlists)
    }
}
