package com.streamify.app.data.discovery

import com.streamify.app.data.models.Track
import com.streamify.app.data.network.YouTubeMusicRadioApi
import com.streamify.app.util.SLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * BlendEngine — group-taste cross-pollination over radio streams (Gap #25)
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * When a Blend is opened with a friend (or inside a Jam):
 *
 *  1. The top 3 seed artists/tracks of every member are extracted.
 *  2. YTM radio streams are queried CONCURRENTLY on Dispatchers.IO for all
 *     seeds (multi-client resilient [YouTubeMusicRadioApi], SWR-cached).
 *  3. Tracks are interleaved round-robin across members, common-artist
 *     overlap is detected, and candidates are scored into a ranking.
 *
 * The result carries the animated-header data the Blend UI renders: match
 * percentage ("88% Taste Match"), mutual artists, and per-track
 * "Both of you love this" badges.
 *
 * Fallback chains (never fail, never block the UI):
 *  - A seed whose radio comes back empty (already search-fallback inside
 *    the radio layer) degrades to the member's local candidate pool.
 *  - A fully offline member contributes their local pool only.
 *  - Zero candidates anywhere yields an empty result — the UI shows the
 *    local-library shelf instead of an error.
 *
 * [BlendInterleaver] is pure and JVM-testable; [BlendEngine] owns only the
 * concurrent orchestration and returns immutable lists.
 */

/** Unified playable candidate flowing through the blend pipeline. */
data class BlendCandidate(
    val videoId: String,
    val title: String,
    val artist: String,
    val durationSec: Int = 0,
    val thumbnailUrl: String = ""
) {
    val artistKey: String get() = artist.trim().lowercase()
    val titleKey: String get() = title.trim().lowercase()
}

/** One blend participant: display identity + taste seeds + offline pool. */
data class BlendMember(
    val name: String,
    val avatarUrl: String = "",
    /** Top artists (first 3 used as radio seeds). */
    val seedArtists: List<String> = emptyList(),
    /** Optional track queries ("Artist - Title") mixed into the seed set. */
    val seedTracks: List<String> = emptyList(),
    /** Local-library fallback pool when radio fails for this member. */
    val localPool: List<BlendCandidate> = emptyList()
) {
    val seedQueries: List<String>
        get() = (seedArtists.take(3) + seedTracks.take(2)).take(3)
}

/** A ranked blend row with cross-pollination provenance. */
data class BlendTrack(
    val candidate: BlendCandidate,
    val memberIndex: Int,
    val mutual: Boolean,
    val score: Int
) {
    val videoId: String get() = candidate.videoId
    val title: String get() = candidate.title
    val artist: String get() = candidate.artist
    val durationSec: Int get() = candidate.durationSec
    val thumbnailUrl: String get() = candidate.thumbnailUrl

    fun toTrack(): Track = Track(
        id = -(videoId.hashCode()),
        title = title,
        artist = artist,
        album = "Blend",
        durationSec = durationSec,
        filepath = "https://www.youtube.com/watch?v=$videoId",
        coverArtPath = thumbnailUrl.ifBlank { "https://i.ytimg.com/vi/$videoId/hqdefault.jpg" },
        source = "online_stream",
        ytmVideoId = videoId
    )
}

data class BlendResult(
    val tracks: List<BlendTrack> = emptyList(),
    val matchPercent: Int = 0,
    val mutualArtists: List<String> = emptyList(),
    val memberNames: List<String> = emptyList(),
    val offlineFallbackUsed: Boolean = false
)

/**
 * Pure scoring/interleaving core — no IO, no state, fully JVM-testable.
 */
object BlendInterleaver {

    /**
     * Round-robin interleaves per-member candidate pools so the playlist
     * alternates provenance (Spotify Blend behaviour), dedups by videoId
     * and fuzzy title+artist, flags mutual artists, and produces the final
     * score-ranked order:
     *
     *   score = 60 (base)
     *         + 40 if the artist is loved by 2+ members (mutual)
     *         + positional decay so earlier radio picks rank higher
     *         - repetition penalty for the same artist appearing again
     */
    fun interleave(
        pools: List<List<BlendCandidate>>,
        seedArtistsPerMember: List<Set<String>>,
        limit: Int = 30
    ): List<BlendTrack> {
        if (pools.isEmpty()) return emptyList()
        val seedKeys = seedArtistsPerMember.map { set -> set.map { it.trim().lowercase() }.toSet() }

        val seenVideo = HashSet<String>()
        val seenTitleArtist = HashSet<String>()
        val artistCount = HashMap<String, Int>()
        val rows = mutableListOf<BlendTrack>()

        val maxLength = pools.maxOf { it.size }
        for (depth in 0 until maxLength) {
            for (member in pools.indices) {
                if (depth >= pools[member].size) continue
                val c = pools[member][depth]
                if (c.videoId.length != 11 && c.videoId.isNotBlank()) continue
                if (c.videoId.isNotEmpty() && !seenVideo.add(c.videoId)) continue
                if (!seenTitleArtist.add("${c.titleKey}::${c.artistKey}")) continue

                val membersLovingArtist = seedKeys.count { keys -> c.artistKey in keys } +
                    pools.count { pool -> pool.any { it.artistKey == c.artistKey && it.videoId != c.videoId } }
                val mutual = membersLovingArtist >= 2 || seedKeys.count { keys -> c.artistKey in keys } >= 2

                val priorPlays = artistCount.getOrDefault(c.artistKey, 0)
                artistCount[c.artistKey] = priorPlays + 1

                val score = 60 +
                    (if (mutual) 40 else 0) +
                    (maxLength - depth).coerceAtLeast(0) -
                    (priorPlays * 12)

                rows.add(
                    BlendTrack(
                        candidate = c,
                        memberIndex = member,
                        mutual = mutual,
                        score = score
                    )
                )
                if (rows.size >= limit * 2) break
            }
        }

        // Mutual rows bubble to the top; within equal bands the round-robin
        // provenance order is preserved (stable sort).
        return rows
            .sortedWith(compareByDescending<BlendTrack> { it.score }.thenBy { it.memberIndex })
            .take(limit)
    }

    /**
     * Taste-match percentage over the union/intersection of distinct
     * artists across the member pools — mapped onto a friendly 35..99
     * window (a blend never reads as "0% match" even for disjoint
     * tastes, matching the product behaviour of the reference app).
     */
    fun tasteMatchPercent(pools: List<List<BlendCandidate>>): Int {
        if (pools.size < 2) return 0
        val artistSets = pools.map { pool ->
            pool.map { it.artistKey }.filter { it.isNotBlank() }.toSet()
        }.filter { it.isNotEmpty() }
        if (artistSets.size < 2) return 0
        val union = artistSets.reduce { acc, set -> acc + set }
        if (union.isEmpty()) return 0
        val intersection = artistSets.reduce { acc, set -> acc intersect set }
        val jaccard = intersection.size.toDouble() / union.size.toDouble()
        return (35 + (jaccard * 64).toInt()).coerceIn(35, 99)
    }

    /** Distinct artists appearing in 2+ members' seeds or pools. */
    fun mutualArtists(
        pools: List<List<BlendCandidate>>,
        seedArtistsPerMember: List<Set<String>>
    ): List<String> {
        val memberships = HashMap<String, MutableSet<Int>>()
        seedArtistsPerMember.forEachIndexed { member, seeds ->
            seeds.forEach { raw ->
                memberships.getOrPut(raw.trim().lowercase()) { mutableSetOf() }.add(member)
            }
        }
        pools.forEachIndexed { member, pool ->
            pool.forEach { memberships.getOrPut(it.artistKey) { mutableSetOf() }.add(member) }
        }
        return memberships.entries
            .filter { it.value.size >= 2 && it.key.isNotBlank() }
            .map { it.key }
            .sorted()
    }
}

/**
 * BlendEngine — concurrent orchestration over the radio scraper.
 */
object BlendEngine {

    private const val TAG = "BlendEngine"
    private const val MAX_PER_SEED = 12

    /**
     * Generates a blend for 2..10 members. Radio streams for all seeds of
     * all members are queried concurrently; every failure degrades to the
     * member's [BlendMember.localPool] instead of surfacing an error.
     */
    suspend fun generateBlend(members: List<BlendMember>, limit: Int = 30): BlendResult =
        withContext(Dispatchers.IO) {
            if (members.size < 2) return@withContext BlendResult(memberNames = members.map { it.name })
            val clamped = members.take(10)

            val pools = coroutineScope {
                clamped.map { member ->
                    async { radioPoolFor(member) }
                }.awaitAll()
            }

            val seedSets = clamped.map { it.seedArtists.map { a -> a.trim().lowercase() }.toSet() }
            val usedFallback = pools.any { it.usedLocalFallback }
            val candidates = pools.map { it.candidates }

            val tracks = BlendInterleaver.interleave(candidates, seedSets, limit)
            BlendResult(
                tracks = tracks,
                matchPercent = BlendInterleaver.tasteMatchPercent(candidates),
                mutualArtists = BlendInterleaver.mutualArtists(candidates, seedSets),
                memberNames = clamped.map { it.name },
                offlineFallbackUsed = usedFallback
            )
        }

    /** One member's resolved candidate pool + how it was obtained. */
    private class MemberPool(
        val candidates: List<BlendCandidate>,
        val usedLocalFallback: Boolean
    )

    /** Queries radio for each seed concurrently; local pool as fallback. */
    private suspend fun radioPoolFor(member: BlendMember): MemberPool = coroutineScope {
        val seeds = member.seedQueries
        if (seeds.isEmpty()) {
            return@coroutineScope MemberPool(member.localPool.take(MAX_PER_SEED), true)
        }

        val streams = seeds.map { seed ->
            async(Dispatchers.IO) {
                runCatching { YouTubeMusicRadioApi.radioForQuery(seed, maxTracks = MAX_PER_SEED) }
                    .getOrDefault(YouTubeMusicRadioApi.RadioPage())
                    .tracks
                    .map { t ->
                        BlendCandidate(
                            videoId = t.videoId,
                            title = t.title,
                            artist = t.artist,
                            durationSec = t.durationSec,
                            thumbnailUrl = t.thumbnailUrl
                        )
                    }
                    .filter { it.title.isNotBlank() && it.artist.isNotBlank() }
            }
        }.awaitAll().flatten()

        val distinct = streams.distinctBy { "${it.titleKey}::${it.artistKey}" }
        if (distinct.isEmpty()) {
            SLog.d(TAG, "blend radio empty for ${member.name} — using local pool")
            MemberPool(member.localPool.take(MAX_PER_SEED), true)
        } else {
            MemberPool(distinct, false)
        }
    }
}
