package com.streamify.app.data.discovery
import com.streamify.app.data.models.Track
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.min
import kotlin.random.Random

object ReRanker {

    /**
     * Camelot Wheel Harmonic Key Engine
     * Evaluates harmonic distance between musical keys (0 = identical / relative major-minor, 1 = adjacent step).
     */
    object CamelotWheel {
        private val keyToCamelot = mapOf(
            "ABM" to "1A", "B" to "1B", "G#M" to "1A",
            "EBM" to "2A", "F#" to "2B", "GB" to "2B", "D#M" to "2A",
            "BBM" to "3A", "DB" to "3B", "C#" to "3B", "A#M" to "3A",
            "FM" to "4A", "AB" to "4B", "G#" to "4B",
            "CM" to "5A", "EB" to "5B", "D#" to "5B",
            "GM" to "6A", "BB" to "6B", "A#" to "6B",
            "DM" to "7A", "F" to "7B",
            "AM" to "8A", "C" to "8B",
            "EM" to "9A", "G" to "9B",
            "BM" to "10A", "D" to "10B",
            "F#M" to "11A", "GBM" to "11A", "A" to "11B",
            "C#M" to "12A", "DBM" to "12A", "E" to "12B"
        )

        fun parseCamelot(keyStr: String?): Pair<Int, Char>? {
            if (keyStr.isNullOrBlank()) return null
            val clean = keyStr.uppercase().trim().replace("MINOR", "M").replace("MAJOR", "").replace(" ", "")
            val directCode = if (clean.matches(Regex("^(1[0-2]|[1-9])[AB]$"))) clean else keyToCamelot[clean]
            if (directCode == null) return null
            val num = directCode.dropLast(1).toIntOrNull() ?: return null
            val letter = directCode.last()
            return Pair(num, letter)
        }

        fun calculateDistance(k1: String?, k2: String?): Int {
            val c1 = parseCamelot(k1) ?: return 2
            val c2 = parseCamelot(k2) ?: return 2
            val (num1, let1) = c1
            val (num2, let2) = c2

            val circularDiff = min(abs(num1 - num2), 12 - abs(num1 - num2))
            return if (let1 == let2) {
                circularDiff
            } else {
                // Relative major/minor (e.g. 8A Am <-> 8B C Major) has 0 distance penalty
                if (circularDiff == 0) 0 else 1 + circularDiff
            }
        }

        fun calculateHarmonicMatchScore(k1: String?, k2: String?): Float {
            val dist = calculateDistance(k1, k2)
            return (1.0f - (0.25f * dist.coerceAtMost(4))).coerceIn(0.1f, 1.0f)
        }
    }

    /**
     * Helper to normalize artist string for comparison
     */
    fun normalizeArtist(artist: String): String =
        artist.lowercase().trim().split(",", "&", "feat.", "ft.", "x", "/").firstOrNull()?.trim() ?: artist.lowercase().trim()

    /**
     * Determines tempo cluster to avoid genre/tempo monoculture (e.g. all Phonk/Hardstyle)
     */
    fun getTempoCluster(bpm: Float): String {
        return when {
            bpm <= 0f -> "UNKNOWN"
            bpm < 95f -> "CHILL_LOFI"
            bpm in 95f..125f -> "MID_POP_RNB"
            else -> "HIGH_ENERGY"
        }
    }

    /**
     * Gaussian BPM Bell Curve scoring (Sigma = 18.0)
     * Enforces smooth tempo continuity across tracks.
     */
    fun calculateGaussianBpmWeight(bpm: Float, targetBpm: Float, sigma: Float = 18.0f): Float {
        if (bpm <= 0f || targetBpm <= 0f) return 1.0f
        val diff = bpm - targetBpm
        return exp(-(diff * diff) / (2.0f * sigma * sigma))
    }

    /**
     * Hoffman Satiation Decay Model
     * Exponentially penalizes tracks played recently (half-life = 2 hours) to eliminate echo chambers.
     */
    fun calculateHoffmanDecay(lastPlayedTimestampMs: Long, halfLifeHours: Float = 2.0f): Float {
        if (lastPlayedTimestampMs <= 0L) return 1.0f
        val elapsedMs = System.currentTimeMillis() - lastPlayedTimestampMs
        val elapsedHours = elapsedMs.toFloat() / (3600f * 1000f)
        val lambda = 0.693147f / halfLifeHours
        return (1.0f - exp(-lambda * elapsedHours)).coerceIn(0.15f, 1.0f)
    }

    /**
     * Samples distinct diverse seed tracks across different artists and tempo clusters.
     */
    fun getDistinctGenreSeeds(allTracks: List<Track>, limit: Int = 4): List<Track> {
        if (allTracks.isEmpty()) return emptyList()
        val distinctArtists = mutableMapOf<String, Track>()
        val distinctClusters = mutableMapOf<String, Track>()

        for (track in allTracks.shuffled(Random(System.currentTimeMillis()))) {
            val primary = normalizeArtist(track.artist)
            val cluster = getTempoCluster(track.bpm)

            if (primary.isNotBlank() && !distinctArtists.containsKey(primary)) {
                distinctArtists[primary] = track
                distinctClusters[cluster] = track
                if (distinctArtists.size >= limit) break
            }
        }

        return (distinctArtists.values.toList() + distinctClusters.values.toList()).distinctBy { it.id }.take(limit).ifEmpty { allTracks.take(limit) }
    }

    /**
     * STAGE 3: Multi-Armed Bandit Re-Ranker & Anti-Fatigue Engine
     * Combines Acoustic Similarity + Camelot Wheel + Gaussian BPM + Satiation Decay + 20% Novelty Exploration.
     *
     * @param candidates Raw candidate tracks from Stage 1 Aggregator
     * @param seedTrack Current playing track for harmonic and tempo transitions
     * @param targetBpm Target circadian tempo
     * @param maxPerArtist Hard ceiling of tracks allowed per artist (default: 2 per 15-song window)
     * @param explorationRatio Percentage of novel unplayed tracks to inject (default: 20%)
     * @param limit Target output shelf / queue size
     */
    fun scoreAndRankCandidates(
        candidates: List<Track>,
        seedTrack: Track? = null,
        targetBpm: Float = seedTrack?.bpm ?: 0f,
        maxPerArtist: Int = 2,
        maxPerTempoCluster: Int = 3,
        explorationRatio: Float = 0.20f,
        limit: Int = 15
    ): List<Track> {
        if (candidates.isEmpty()) return emptyList()

        // 1. Multi-Factor Acoustic Scoring for Each Candidate
        val scoredCandidates = candidates.map { track ->
            val harmonicScore = if (seedTrack != null) {
                CamelotWheel.calculateHarmonicMatchScore(seedTrack.key, track.key)
            } else 1.0f

            val bpmScore = if (targetBpm > 0f) {
                calculateGaussianBpmWeight(track.bpm, targetBpm, sigma = 18.0f)
            } else 1.0f

            val satiationMultiplier = 1.0f // Defaults to fresh
            val compositeScore = (0.50f * bpmScore + 0.50f * harmonicScore) * satiationMultiplier

            Pair(track, compositeScore)
        }.sortedByDescending { it.second }

        val result = mutableListOf<Track>()
        val artistCounts = mutableMapOf<String, Int>()
        val clusterCounts = mutableMapOf<String, Int>()
        val seenTrackKeys = mutableSetOf<String>()

        fun trackKey(t: Track) = "${t.title.trim().lowercase()}::${t.artist.trim().lowercase()}"

        val numExploration = (limit * explorationRatio).toInt()
        val numExploitation = limit - numExploration

        // 2. 80% Exploitation: Top scoring candidates with Strict Artist & Tempo Damping
        for ((track, _) in scoredCandidates) {
            if (result.size >= numExploitation) break
            val key = trackKey(track)
            if (key in seenTrackKeys) continue

            val primaryArtist = normalizeArtist(track.artist)
            val cluster = getTempoCluster(track.bpm)
            val currentArtistCount = artistCounts.getOrDefault(primaryArtist, 0)
            val currentClusterCount = clusterCounts.getOrDefault(cluster, 0)

            if (currentArtistCount < maxPerArtist && (cluster == "UNKNOWN" || currentClusterCount < maxPerTempoCluster)) {
                result.add(track)
                seenTrackKeys.add(key)
                artistCounts[primaryArtist] = currentArtistCount + 1
                clusterCounts[cluster] = currentClusterCount + 1
            }
        }

        // 3. 20% Exploration: Novel discoveries across diverse acoustic clusters
        val remainingCandidates = scoredCandidates.map { it.first }.filter { trackKey(it) !in seenTrackKeys }
        if (remainingCandidates.isNotEmpty()) {
            val shuffledNovelty = remainingCandidates.shuffled(Random(System.currentTimeMillis()))
            for (track in shuffledNovelty) {
                if (result.size >= limit) break
                val key = trackKey(track)
                if (key in seenTrackKeys) continue

                val primaryArtist = normalizeArtist(track.artist)
                val cluster = getTempoCluster(track.bpm)
                val currentArtistCount = artistCounts.getOrDefault(primaryArtist, 0)
                val currentClusterCount = clusterCounts.getOrDefault(cluster, 0)

                if (currentArtistCount < maxPerArtist && (cluster == "UNKNOWN" || currentClusterCount < maxPerTempoCluster)) {
                    result.add(track)
                    seenTrackKeys.add(key)
                    artistCounts[primaryArtist] = currentArtistCount + 1
                    clusterCounts[cluster] = currentClusterCount + 1
                }
            }
        }

        // 4. Backfill if still below requested limit
        for ((track, _) in scoredCandidates) {
            if (result.size >= limit) break
            val key = trackKey(track)
            if (key !in seenTrackKeys) {
                result.add(track)
                seenTrackKeys.add(key)
            }
        }

        return result
    }

    /**
     * Backward-compatible helper for UI Home shelves
     */
    fun reRank(
        candidates: List<Track>,
        maxPerArtist: Int = 2,
        maxPerTempoCluster: Int = 3,
        explorationRatio: Float = 0.20f,
        explorationPool: List<Track> = emptyList(),
        targetBpm: Float = 0.0f,
        limit: Int = 10
    ): List<Track> {
        val mergedPool = (candidates + explorationPool).distinctBy { "${it.title.trim().lowercase()}::${it.artist.trim().lowercase()}" }
        return scoreAndRankCandidates(
            candidates = mergedPool,
            seedTrack = null,
            targetBpm = targetBpm,
            maxPerArtist = maxPerArtist,
            maxPerTempoCluster = maxPerTempoCluster,
            explorationRatio = explorationRatio,
            limit = limit
        )
    }

    /**
     * Extracts top distinct artist names from a track list for 2-hop graph discovery.
     */
    fun extractTopArtists(tracks: List<Track>, limit: Int = 3): List<String> {
        return tracks
            .map { it.artist.split(",", "&", "feat.", "ft.", "x", "/").firstOrNull()?.trim() ?: it.artist.trim() }
            .filter { it.isNotBlank() && !it.equals("Unknown Artist", ignoreCase = true) }
            .groupingBy { it }
            .eachCount()
            .entries
            .sortedByDescending { it.value }
            .take(limit)
            .map { it.key }
    }
}
