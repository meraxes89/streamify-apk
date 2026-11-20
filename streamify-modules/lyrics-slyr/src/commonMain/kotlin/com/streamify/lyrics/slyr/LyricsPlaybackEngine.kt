package com.streamify.lyrics.slyr

/**
 * Real-time lyric synchronization engine.
 * Fast binary search indexing and continuous syllable progress interpolation.
 */
object LyricsPlaybackEngine {

    /**
     * Binary search to find the active line index for a given playback timestamp.
     */
    fun findActiveLineIndex(lines: List<LyricsLine>, currentPositionMs: Long): Int {
        if (lines.isEmpty()) return -1
        if (currentPositionMs < lines.first().timeMs) return 0

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
     * Calculates the fill ratio [0.0f..1.0f] of an individual syllable
     * at the current presentation timestamp.
     */
    fun getSyllableProgress(syllable: Syllable, currentPositionMs: Long): Float {
        if (currentPositionMs <= syllable.startMs) return 0.0f
        if (currentPositionMs >= syllable.endMs) return 1.0f

        val totalDuration = (syllable.endMs - syllable.startMs).toFloat()
        if (totalDuration <= 0f) return 1.0f

        val elapsed = (currentPositionMs - syllable.startMs).toFloat()
        return (elapsed / totalDuration).coerceIn(0.0f, 1.0f)
    }
}
