package com.streamify.app.jam

import androidx.media3.common.Player
import com.streamify.app.util.SLog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout

/**
 * PHASE 3 — Event-Driven Readiness FSM (P9). Pure Kotlin, zero JNI.
 *
 * Destroys the `delay(300)`/`delay(350)` magic sleeps: callers prepare a
 * MediaItem, then suspend until Media3 actually reports STATE_READY before
 * pinning position and resuming. Seeks issued before READY can land on the
 * PREVIOUS item; this gate makes that race structurally impossible.
 *
 * Deliberately NOT bridged to Rust: ExoPlayer is Kotlin's domain. The only
 * native involvement in Jam sync is math (clock/tick/kalman/governor).
 */
object PlaybackReadyGate {

    private const val TAG = "ReadyGate"
    private const val DEFAULT_TIMEOUT_MS = 6_000L

    /**
     * Suspends until the player reaches STATE_READY (or times out), then
     * seeks to [positionMs] and optionally plays.
     *
     * @return true when the seek was applied post-ready; false on timeout /
     * cancellation — caller decides fallback (skip / re-handshake).
     */
    suspend fun awaitReadyThenSeek(
        player: Player,
        positionMs: Long,
        play: Boolean,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        tag: String? = null
    ): Boolean {
        // Fast path: already prepared and ready for THIS item.
        if (player.playbackState == Player.STATE_READY) {
            return applySeek(player, positionMs, play, tag)
        }

        val ready = CompletableDeferred<Unit>()
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) {
                    ready.complete(Unit)
                }
                // IDLE/ENDED intentionally ignored: registering while IDLE is
                // normal pre-prepare flow; errors surface via onPlayerError
                // and the timeout below is the ultimate guard.
            }
        }

        player.addListener(listener)
        try {
            withTimeout(timeoutMs) { ready.await() }
            return applySeek(player, positionMs, play, tag)
        } catch (e: Throwable) {
            SLog.e(TAG, "ready-gate timeout/failure${tag?.let { " [$it]" } ?: ""}", e)
            return false
        } finally {
            player.removeListener(listener)
        }
    }

    private fun applySeek(player: Player, positionMs: Long, play: Boolean, tag: String?): Boolean {
        return try {
            val duration = player.duration
            val safePos = if (duration > 0) positionMs.coerceIn(0L, duration - 250L) else positionMs
            if (safePos > 0L) player.seekTo(safePos)
            if (play && !player.playWhenReady) player.play() else if (!play && player.playWhenReady) player.pause()
            SLog.d(TAG, "post-ready seek=${safePos}ms play=$play${tag?.let { " [$it]" } ?: ""}")
            true
        } catch (e: Throwable) {
            SLog.e(TAG, "post-ready apply failed", e)
            false
        }
    }
}
