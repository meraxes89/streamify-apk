package com.streamify.app.player

import com.streamify.app.data.models.Track

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * SmartShuffleEngine — algorithmic interleave into the active queue (Phase 3)
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * Spotify-Smart-Shuffle-style queue enrichment: user-chosen tracks stay in
 * relative order, while algorithmic recommendations are interleaved at
 * musical boundaries (after every [injectAfterEach] user tracks) so the
 * queue feels like a curated radio rather than a shuffled mess.
 *
 * Guarantees (all locked by unit tests):
 *  • Relative order of user tracks is NEVER permuted.
 *  • The currently-playing track and the immediate next track are never
 *    displaced — no surprise interruption mid-song.
 *  • Recommendations are deduped against the queue (by ytmVideoId, then by
 *    title+artist, then by id) and against each other.
 *  • Every injected item is reported in [Result.injectedIds] so the queue UI
 *    can render a distinct "Recommended" badge.
 *  • [interleave] is a PURE function — no player state, no coroutines.
 */
object SmartShuffleEngine {

    /** How many user tracks to keep between two injected recommendations. */
    const val DEFAULT_INJECT_AFTER_EACH = 2

    /** Never inject before this offset relative to the current index. */
    const val MIN_PROTECTED_AHEAD = 1

    data class Result(
        /** The new queue (user tracks in order + interleaved recommendations). */
        val queue: List<Track>,
        /** Synthetic ids of the injected tracks (badge source for the UI). */
        val injectedIds: Set<Int>,
        /** How many recommendations were actually injected (post-dedup). */
        val injectedCount: Int
    )

    /**
     * Interleaves [recommendations] into [queue] after the current track.
     *
     * @param queue the active queue (user tracks), order preserved
     * @param currentIndex index of the currently playing track in the queue
     * @param recommendations algorithmic candidates, best first
     * @param injectAfterEach user tracks to keep between injections
     * @param maxInjections hard cap on injected tracks per shuffle pass
     */
    fun interleave(
        queue: List<Track>,
        currentIndex: Int,
        recommendations: List<Track>,
        injectAfterEach: Int = DEFAULT_INJECT_AFTER_EACH,
        maxInjections: Int = 30
    ): Result {
        if (queue.isEmpty()) {
            return Result(queue, emptySet(), 0)
        }
        val safeIndex = currentIndex.coerceIn(-1, queue.size - 1)
        val head = queue.subList(0, safeIndex + 1)               // played/current — untouched
        val protectedNext = queue.subList(
            safeIndex + 1,
            (safeIndex + 1 + MIN_PROTECTED_AHEAD).coerceAtMost(queue.size)
        )
        val tail = queue.subList(
            (safeIndex + 1 + MIN_PROTECTED_AHEAD).coerceAtMost(queue.size),
            queue.size
        )

        // ── dedup: drop recommendations the queue already contains ──────────
        val existingVideoIds = queue.mapNotNull { it.ytmVideoId?.takeIf(String::isNotBlank) }.toHashSet()
        val existingKeys = queue.map { titleArtistKey(it) }.toHashSet()
        val existingIds = queue.map { it.id }.toHashSet()
        val seenRecIds = HashSet<Int>()

        val fresh = recommendations.filter { rec ->
            val recVid = rec.ytmVideoId?.takeIf(String::isNotBlank)
            if (recVid != null && recVid in existingVideoIds) return@filter false
            if (titleArtistKey(rec) in existingKeys) return@filter false
            if (rec.id != 0 && rec.id in existingIds) return@filter false
            if (rec.id != 0 && !seenRecIds.add(rec.id)) return@filter false
            true
        }
        if (fresh.isEmpty()) return Result(queue, emptySet(), 0)

        // ── interleave: [user × injectAfterEach] then one recommendation ────
        val out = ArrayList<Track>(queue.size + fresh.size)
        val injected = ArrayList<Track>(fresh.size.coerceAtMost(maxInjections))
        out.addAll(head)
        out.addAll(protectedNext)

        var recCursor = 0
        var sinceLastInject = 0
        for (track in tail) {
            out.add(track)
            sinceLastInject++
            if (sinceLastInject >= injectAfterEach && recCursor < fresh.size && injected.size < maxInjections) {
                val rec = fresh[recCursor++]
                out.add(rec)
                injected.add(rec)
                sinceLastInject = 0
            }
        }
        // Queue too short to hit a cadence boundary (or fewer boundaries
        // than picks): top the remainder up after the last user track —
        // Smart Shuffle must always enrich, never no-op on short queues.
        while (recCursor < fresh.size && injected.size < maxInjections) {
            val rec = fresh[recCursor++]
            out.add(rec)
            injected.add(rec)
        }

        return Result(
            queue = out,
            injectedIds = injected.map { it.id }.toSet(),
            injectedCount = injected.size
        )
    }

    /**
     * Reshuffle pass: strips previously injected recommendations out of the
     * queue, then interleaves a fresh recommendation batch. The currently
     * playing track is never removed mid-play.
     */
    fun reshuffle(
        queue: List<Track>,
        currentIndex: Int,
        previousInjectedIds: Set<Int>,
        freshRecommendations: List<Track>,
        injectAfterEach: Int = DEFAULT_INJECT_AFTER_EACH,
        maxInjections: Int = 30
    ): Result {
        if (previousInjectedIds.isEmpty()) {
            return interleave(queue, currentIndex, freshRecommendations, injectAfterEach, maxInjections)
        }
        val playing = queue.getOrNull(currentIndex)
        val userQueue = queue.filterIndexed { index, track ->
            // Never strip the playing track, even if it was injected.
            (index == currentIndex) || (track.id !in previousInjectedIds)
        }
        // The current track's new index after stripping.
        val newCurrentIndex = if (playing != null) userQueue.indexOfFirst { it.id == playing.id } else -1
        return interleave(
            queue = userQueue,
            currentIndex = newCurrentIndex,
            recommendations = freshRecommendations,
            injectAfterEach = injectAfterEach,
            maxInjections = maxInjections
        )
    }

    /** Stable dedup key — normalizes case/whitespace, ignores parentheticals. */
    internal fun titleArtistKey(track: Track): String {
        val title = track.title.lowercase()
            .substringBefore(" (").substringBefore(" [")
            .replace(Regex("\\s+"), " ").trim()
        val artist = track.artist.lowercase()
            .substringBefore(" - ").replace(Regex("\\s+"), " ").trim()
        return "$title|$artist"
    }
}
