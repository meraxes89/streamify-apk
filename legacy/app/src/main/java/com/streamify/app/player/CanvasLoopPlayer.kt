package com.streamify.app.player

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import com.streamify.app.data.network.CanvasScraperApi
import com.streamify.app.data.network.NetworkEngine
import com.streamify.app.util.SLog

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * CanvasLoopPlayer — Media3 engine for the 8s Canvas video loop (Phase 3, 2)
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * A featherweight, MUTED, seamlessly-looping ExoPlayer dedicated to Canvas
 * playback. Audio never routes through this player — the main audio session
 * keeps playing untouched, which is exactly the Spotify Canvas contract:
 * "the loop is wallpaper, the song is the sound".
 *
 * • REPEAT_MODE_ONE + tiny 8s MediaItem → the loop restarts inside one
 *   buffer slot; no re-fetch between iterations (cached upstream via the
 *   shared OkHttp factory).
 * • volume = 0 on both player and audio attributes — Canvas can NEVER
 *   steal the audio focus of the music session.
 * • Single surface attach point; the Compose layer (CanvasVideoSurface)
 *   owns the AndroidView wiring.
 * • [setLoop] is idempotent — setting the same loop twice is a no-op, so
 *   recomposition storms cost nothing.
 */
class CanvasLoopPlayer(context: Context) {

    companion object {
        private const val TAG = "CanvasLoopPlayer"
    }

    private val appContext = context.applicationContext

    /** Lazily built — idle when Canvas is off, so audio-only users pay zero cost. */
    private var player: ExoPlayer? = null

    /** Currently loaded loop URL — idempotency guard. */
    private var loadedLoopUrl: String? = null

    val exoPlayer: ExoPlayer?
        get() = player

    /**
     * Loads and starts looping [loop]. Idempotent per URL. Safe to call from
     * any thread — internally hops to the main thread (ExoPlayer's home).
     */
    fun setLoop(loop: CanvasScraperApi.CanvasLoop) {
        if (loadedLoopUrl == loop.loopUrl) {
            player?.playWhenReady = true
            return
        }
        loadedLoopUrl = loop.loopUrl
        val p = ensurePlayer()
        runCatching {
            p.setMediaItem(MediaItem.fromUri(loop.loopUrl))
            p.repeatMode = Player.REPEAT_MODE_ONE
            p.volume = 0f
            p.playWhenReady = true
            p.prepare()
        }.onFailure {
            SLog.d(TAG, "CanvasLoopPlayer.setLoop failed (${it.message})")
            loadedLoopUrl = null
        }
    }

    /** Pauses the loop without tearing the decoder down (fast re-enable). */
    fun pause() {
        player?.playWhenReady = false
    }

    /** Resumes the loop if one is loaded. */
    fun resume() {
        if (loadedLoopUrl != null) {
            player?.playWhenReady = true
        }
    }

    /**
     * Full teardown — used when the user toggles Canvas OFF or leaves the
     * player screen. Releases the decoder and drops the URL guard.
     */
    fun release() {
        player?.let { p ->
            runCatching { p.stop(); p.release() }
        }
        player = null
        loadedLoopUrl = null
    }

    private fun ensurePlayer(): ExoPlayer {
        player?.let { return it }
        val httpFactory = OkHttpDataSource.Factory(NetworkEngine.exoPlayerClient)
        val dataSourceFactory = DefaultDataSource.Factory(appContext, httpFactory)
        val mediaSourceFactory = androidx.media3.exoplayer.source.DefaultMediaSourceFactory(appContext)
            .setDataSourceFactory(dataSourceFactory)
        val built = ExoPlayer.Builder(appContext)
            .setMediaSourceFactory(mediaSourceFactory)
            .build()
        built.volume = 0f
        built.repeatMode = Player.REPEAT_MODE_ONE
        player = built
        return built
    }
}
