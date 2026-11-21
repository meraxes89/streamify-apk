package com.streamify.app.wear

import com.streamify.app.data.models.Track
import java.io.File

/**
 * WearRunSyncCoordinator (Gap #55) — offline run bundle planner.
 *
 * A run bundle is a compact, watch-local audio pack: the user's most
 * athletic (highest-BPM) tracks that are ALREADY on-device (local files or
 * completed downloads) packed under a byte budget so a workout never
 * depends on the phone's network.
 *
 * Selection is PURE and JVM-tested; transmission rides
 * [WearSessionManager.pushRunBundleFile].
 */
object WearRunSyncCoordinator {

    /** Watch-side budget for audio bundles (32 MB per run pack). */
    const val DEFAULT_BUDGET_BYTES: Long = 32L * 1024 * 1024

    /** Minimum pack size — smaller packs are not worth a channel open. */
    const val MIN_TRACKS_FOR_SYNC = 3

    /**
     * Plan the bundle: local/downloaded tracks only, ranked by a
     * run-friendliness score (BPM proximity to [targetBpm], then play
     * count), packed greedily until the byte budget is exhausted.
     * Tracks without a readable local file are never selected.
     */
    fun planRunBundle(
        candidates: List<Track>,
        targetBpm: Float = 165f,
        budgetBytes: Long = DEFAULT_BUDGET_BYTES,
        fileSizeOf: (Track) -> Long = { DEFAULT_FILE_BYTES }
    ): List<Track> {
        val eligible = candidates.filter { track ->
            track.source == "local" || track.source == "download" || track.filepath.startsWith("/")
        }
        if (eligible.size < MIN_TRACKS_FOR_SYNC) return emptyList()

        val ranked = eligible.sortedWith(
            compareBy<Track> { bpmDistance(it.bpm, targetBpm) }.thenByDescending { it.playCount }
        )

        val packed = mutableListOf<Track>()
        var used = 0L
        for (track in ranked) {
            if (packed.size >= MAX_TRACKS_PER_BUNDLE) break
            val size = fileSizeOf(track).coerceAtLeast(0L)
            if (size <= 0L) continue
            if (used + size > budgetBytes) continue
            used += size
            packed.add(track)
        }
        return packed
    }

    /**
     * Verify a prepared bundle directory still fits the budget before the
     * channel opens (files may have grown since planning).
     */
    fun bundleFitsBudget(bundleDir: File, budgetBytes: Long = DEFAULT_BUDGET_BYTES): Boolean {
        if (!bundleDir.isDirectory) return false
        val total = bundleDir.listFiles()?.sumOf { it.length() } ?: 0L
        return total in 1..budgetBytes
    }

    private fun bpmDistance(bpm: Float, target: Float): Float {
        if (bpm <= 0f) return 999f
        return kotlin.math.abs(bpm - target)
    }

    private const val MAX_TRACKS_PER_BUNDLE = 40

    /** Fallback size estimate when the caller cannot stat files. */
    private const val DEFAULT_FILE_BYTES = 4L * 1024 * 1024
}
