package com.streamify.app.media.lyrics
import android.content.Context
import com.streamify.app.data.repository.TrackRepository

/**
 * Per-track lyric sync offsets, persisted process-wide.
 *
 * Fixes L2 (controller isolation): every LyricPlaybackController surface
 * (nav-route LyricsScreen, player landscape pane, portrait pager pane) binds
 * to the same track key, so a nudge made anywhere survives navigation and app
 * restarts. Keyed by "title|artist" identity — stable across id schemes.
 */
object LyricOffsetStore {

    private const val FILE = "lyric_user_offsets"

    fun keyOf(title: String?, artist: String?): String =
        "${title?.trim()?.lowercase() ?: ""}|${artist?.trim()?.lowercase() ?: ""}"

    fun keyOfTrack(track: com.streamify.app.data.models.Track?): String =
        keyOf(track?.title, track?.artist)

    private fun prefs(explicit: Context?): android.content.SharedPreferences? {
        val ctx = explicit ?: TrackRepository.appContext ?: return null
        return ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    }

    fun get(key: String?, context: Context? = null): Long {
        if (key.isNullOrBlank()) return 0L
        return prefs(context)?.getLong(key, 0L) ?: 0L
    }

    fun set(key: String, offsetMs: Long, context: Context? = null) {
        if (key.isBlank()) return
        prefs(context)?.edit()?.putLong(key, offsetMs)?.apply()
    }
}
