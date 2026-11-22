package com.streamify.lyrics.slyr

/**
 * Real-time lyric synchronization engine.
 *
 * Implements amortized O(1) incremental cursor for sequential 120Hz playback,
 * falling back to O(log N) binary search on seeks/backward jumps.
 * Enforces strictly linear syllable ratio for temporal sync, alongside non-linear
 * visual envelope helpers (glow, micro-pulse scale, alpha).
 */
object LyricsPlaybackEngine {

    /**
     * Finds active line index using cached cursor for O(1) amortized forward playback,
     * falling back to binary search if a seek occurs.
     */
    fun findActiveLineIndexIncremental(
        lines: List<LyricsLine>,
        currentPositionMs: Long,
        lastKnownIndex: Int
    ): Int {
        if (lines.isEmpty()) return -1
        if (currentPositionMs < lines.first().timeMs) return -1

        // Fast path: sequentially advancing or still on the current line
        if (lastKnownIndex in lines.indices) {
            val curStart = lines[lastKnownIndex].timeMs
            val nextStart = if (lastKnownIndex + 1 < lines.size) lines[lastKnownIndex + 1].timeMs else Long.MAX_VALUE
            if (currentPositionMs in curStart until nextStart) {
                return lastKnownIndex
            }
            if (lastKnownIndex + 1 < lines.size) {
                val nextNextStart = if (lastKnownIndex + 2 < lines.size) lines[lastKnownIndex + 2].timeMs else Long.MAX_VALUE
                if (currentPositionMs in nextStart until nextNextStart) {
                    return lastKnownIndex + 1
                }
            }
        }

        // Discontinuity / seek: fallback to binary search
        return findActiveLineIndex(lines, currentPositionMs)
    }

    /**
     * Binary search to find the active line index for a given playback timestamp.
     */
    fun findActiveLineIndex(lines: List<LyricsLine>, currentPositionMs: Long): Int {
        if (lines.isEmpty()) return -1
        if (currentPositionMs < lines.first().timeMs) return -1

        var low = 0
        var high = lines.size - 1
        var candidate = 0

        while (low <= high) {
            val mid = (low + high) ushr 1
            val line = lines[mid]

            if (line.timeMs <= currentPositionMs) {
                candidate = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }

        return candidate
    }

    /**
     * Calculates the fill ratio [0.0f..1.0f] of an individual syllable.
     * MUST remain strictly linear in time to match vocal cords (comm18 Decision 3).
     */
    fun getSyllableProgress(syllable: Syllable, currentPositionMs: Long): Float {
        if (currentPositionMs <= syllable.startMs) return 0.0f
        if (currentPositionMs >= syllable.endMs) return 1.0f

        val totalDuration = (syllable.endMs - syllable.startMs).toFloat()
        if (totalDuration <= 0f) return 1.0f

        val elapsed = (currentPositionMs - syllable.startMs).toFloat()
        return (elapsed / totalDuration).coerceIn(0.0f, 1.0f)
    }

    /**
     * Cubic bezier easing helper for visual envelope effects (glow, opacity, scale).
     * Never applied to temporal sweep position.
     */
    fun evaluateVisualGlow(ratio: Float): Float {
        val clamped = ratio.coerceIn(0f, 1f)
        // Smooth sine-based bell envelope peaking around mid-duration
        return (kotlin.math.sin(clamped * kotlin.math.PI)).toFloat()
    }

    fun evaluateVisualScale(ratio: Float): Float {
        val clamped = ratio.coerceIn(0f, 1f)
        return 1.0f + 0.05f * (kotlin.math.sin(clamped * kotlin.math.PI)).toFloat()
    }
}
