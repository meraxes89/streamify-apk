package com.streamify.app.media.lyrics
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import kotlin.math.abs

/**
 * 120 FPS Phase-Locked Lyric Loop with hardware VSYNC extrapolation and
 * per-track persisted sync offsets.
 *
 * - Continuous VSYNC frame-delta extrapolation (no rigid 250ms freeze).
 * - Proportional Phase-Locked Loop (PLL) clock drift compensator.
 * - Instant sub-frame snapping on manual nudges and seeks.
 */
class LyricPlaybackController {

    var targetPositionMs by mutableStateOf(0L)
    var userOffsetMs by mutableStateOf(0L)
        private set
    var isPlaying by mutableStateOf(false)
    var interpolatedPosMs by mutableStateOf(0L)
        private set

    /** Stable identity of the bound track (LyricOffsetStore.keyOf). */
    var trackKey: String? = null
        private set

    private var lastObservedTargetMs = -1L
    private var lastTargetTimeNanos = 0L
    private var lastFrameTimeNanos = 0L
    private var snapPending = false

    /**
     * Attaches the controller to a track: restores the user's saved offset and
     * routes every subsequent nudge into persistent storage.
     */
    fun bindTrack(key: String?) {
        trackKey = key
        userOffsetMs = LyricOffsetStore.get(key ?: "")
        snapPending = true // re-anchor rendering to the restored offset
    }

    fun adjustOffset(deltaMs: Long) {
        userOffsetMs += deltaMs
        snapPending = true
        val key = trackKey ?: return
        LyricOffsetStore.set(key, userOffsetMs)
    }

    fun resetOffset() {
        userOffsetMs = 0L
        snapPending = true
        val key = trackKey ?: return
        LyricOffsetStore.set(key, 0L)
    }

    suspend fun runFrameLoop() {
        while (true) {
            withFrameNanos { frameTimeNanos ->
                if (lastFrameTimeNanos == 0L) {
                    lastFrameTimeNanos = frameTimeNanos
                    lastTargetTimeNanos = frameTimeNanos
                    lastObservedTargetMs = targetPositionMs
                    interpolatedPosMs = targetPositionMs + userOffsetMs
                    return@withFrameNanos
                }

                val frameDeltaNanos = frameTimeNanos - lastFrameTimeNanos
                lastFrameTimeNanos = frameTimeNanos

                // When ExoPlayer reports a new position tick
                if (targetPositionMs != lastObservedTargetMs) {
                    val gap = abs(targetPositionMs - lastObservedTargetMs)
                    lastObservedTargetMs = targetPositionMs
                    lastTargetTimeNanos = frameTimeNanos
                    if (gap > 400L) {
                        snapPending = true
                    }
                }

                if (!isPlaying) {
                    interpolatedPosMs = (lastObservedTargetMs + userOffsetMs).coerceAtLeast(0L)
                    snapPending = false
                    return@withFrameNanos
                }

                // Phase-Locked Loop: extrapolate continuous real-time advancement using hardware VSYNC
                val elapsedSinceTargetMs = if (lastTargetTimeNanos > 0L) {
                    (frameTimeNanos - lastTargetTimeNanos) / 1_000_000.0
                } else {
                    (frameDeltaNanos / 1_000_000.0)
                }

                val exactTarget = (lastObservedTargetMs.toDouble() + elapsedSinceTargetMs + userOffsetMs.toDouble()).toLong()
                val diff = exactTarget - interpolatedPosMs

                if (snapPending || abs(diff) > 500L || interpolatedPosMs <= 0L) {
                    // Instant snap on seek, initial load, or user nudge
                    interpolatedPosMs = exactTarget
                    snapPending = false
                } else {
                    // Smooth Phase-Locked 120 FPS tracking
                    val step = (diff * 0.25).toLong()
                    interpolatedPosMs += if (step != 0L) step else (if (diff > 0) 1L else if (diff < 0) -1L else 0L)
                }
            }
        }
    }
}
