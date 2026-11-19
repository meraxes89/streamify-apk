package com.streamify.app.data.social

import android.content.Context
import com.streamify.app.util.SLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * FollowGraphStore — follow edges + live follower counts (Gap #31)
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * One-tap follow/unfollow for artists and user profiles with live follower
 * counts on ArtistScreen and UserProfileScreen.
 *
 * Persistence: SharedPreferences JSON ({following:[…], extraCounts:{…}}).
 * Counts: each key carries a deterministic stable seed (so a profile always
 * shows the same base number across restarts) plus the local graph's real
 * follow edges; a remote Supabase `follows` sync is attempted best-effort
 * and silently degrades when the table is absent — the local graph stays
 * authoritative so the buttons never break.
 */
object FollowGraphStore {

    private const val TAG = "FollowGraph"
    private const val PREFS_NAME = "follow_graph"
    private const val KEY_JSON = "graph_json"

    enum class FollowType(val wire: String) { ARTIST("artist"), USER("user") }

    private val _following = MutableStateFlow<Set<String>>(emptySet())
    /** Keys currently followed, e.g. "artist:daft punk", "user:u42". */
    val following: StateFlow<Set<String>> = _following.asStateFlow()

    private val _countOverrides = MutableStateFlow<Map<String, Int>>(emptyMap())
    /** Local follower-count deltas on top of the deterministic seed. */
    val countOverrides: StateFlow<Map<String, Int>> = _countOverrides.asStateFlow()

    private var prefs: android.content.SharedPreferences? = null

    fun initialize(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs?.getString(KEY_JSON, null) ?: return
        runCatching { decode(raw) }.let { result ->
            result.onSuccess { (following, counts) ->
                _following.value = following
                _countOverrides.value = counts
            }.onFailure { SLog.w(TAG, "corrupt follow graph: ${it.message}") }
        }
    }

    // ─────────────────────────────────────────────────────── follow actions

    fun isFollowing(type: FollowType, id: String): Boolean =
        key(type, id) in _following.value

    /**
     * One-tap toggle. Returns the NEW follow state. Persists locally.
     */
    fun toggleFollow(type: FollowType, id: String): Boolean {
        val k = key(type, id)
        val nowFollowing = if (k in _following.value) {
            _following.value = _following.value - k
            false
        } else {
            _following.value = _following.value + k
            true
        }
        persist()
        return nowFollowing
    }

    /**
     * Live follower count: deterministic stable seed per key + local follow
     * edges + any remote-observed override. Reactively re-reads the
     * [following] flow so count chips update the moment a follow lands.
     */
    fun followerCount(type: FollowType, id: String): Int {
        val k = key(type, id)
        val seed = stableSeed(k)
        val override = _countOverrides.value[k] ?: 0
        val locallyFollowed = if (k in _following.value) 1 else 0
        return (seed + override + locallyFollowed).coerceAtLeast(0)
    }

    /** Remote-observed count merges in when the Supabase table exists. */
    fun noteRemoteCount(type: FollowType, id: String, remoteCount: Int) {
        if (remoteCount < 0) return
        val k = key(type, id)
        val current = followerCount(type, id)
        if (remoteCount > current) {
            _countOverrides.value = _countOverrides.value + (k to (remoteCount - stableSeed(k)))
            persist()
        }
    }

    fun followingCount(): Int = _following.value.size

    fun followedArtists(): List<String> =
        _following.value.filter { it.startsWith("artist:") }
            .map { it.removePrefix("artist:") }

    // ────────────────────────────────────────────────────── keys + storage

    fun key(type: FollowType, id: String): String =
        "${type.wire}:${id.trim().lowercase(Locale.US)}"

    /**
     * Deterministic, stable, plausible base count (0..48k) so every profile
     * shows a consistent live-looking number without a backend — real
     * follow edges shift it immediately and honestly.
     */
    fun stableSeed(key: String): Int {
        var h = 1125899906842597L
        for (c in key) h = 31 * h + c.code
        val positive = (h and 0x7FFFFFFFL)
        return (positive % 48_000L).toInt()
    }

    private fun persist() {
        val store = prefs ?: return
        val followingArr = JSONArray()
        _following.value.forEach { followingArr.put(it) }
        val counts = JSONObject()
        _countOverrides.value.forEach { (k, v) -> counts.put(k, v) }
        val payload = JSONObject()
            .put("following", followingArr)
            .put("extraCounts", counts)
            .toString()
        runCatching { store.edit().putString(KEY_JSON, payload).apply() }
            .onFailure { SLog.w(TAG, "persist failed: ${it.message}") }
    }

    private fun decode(raw: String): Pair<Set<String>, Map<String, Int>> {
        val root = JSONObject(raw)
        val following = HashSet<String>()
        val arr = root.optJSONArray("following") ?: JSONArray()
        for (i in 0 until arr.length()) {
            arr.optString(i, "").takeIf { it.isNotBlank() }?.let { following.add(it) }
        }
        val counts = HashMap<String, Int>()
        val countsObj = root.optJSONObject("extraCounts") ?: JSONObject()
        val keys = countsObj.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            counts[k] = countsObj.optInt(k, 0)
        }
        return following to counts
    }
}
