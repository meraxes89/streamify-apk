package com.streamify.app.data.discovery
import com.streamify.app.data.models.Track
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow
import com.streamify.app.data.NativeBridge

object AntiDriftScoringEngine {

    private const val MAX_TRACKS_PER_ARTIST = 2
    private const val WINDOW_SIZE = 20

    private val JUNK_KEYWORDS = listOf(
        "full album", "1 hour", "10 hours", "compilation", "greatest hits mix",
        "best songs mix", "non stop", "jukebox", "podcast", "audiobook", "asmr",
        "medley", "slowed + reverb mix", "workout mix"
    )

    /**
     * Filters out non-music compilations, eliminates duplicate song variations,
     * caps artist saturation, and ranks candidates by acoustic & harmonic affinity.
     * Accelerated via High-Performance Rust Vector Engine with pure Kotlin fallback.
     */
    fun filterAndRankCandidates(
        candidates: List<Track>,
        seedTrack: Track,
        activeQueue: List<Track>
    ): List<Track> {
        if (candidates.isEmpty()) return emptyList()

        // Tier 1: Zero-Allocation Rust Native Scoring Engine
        try {
            val candidateArray = org.json.JSONArray()
            for (c in candidates) {
                candidateArray.put(org.json.JSONObject().apply {
                    put("id", c.id)
                    put("title", c.title)
                    put("artist", c.artist)
                    put("album", c.album)
                    put("duration_sec", c.durationSec)
                    put("filepath", c.filepath)
                    put("cover_art_path", c.coverArtPath)
                    put("bpm", c.bpm.toDouble())
                    put("key", c.key)
                })
            }

            val queueArray = org.json.JSONArray()
            for (q in activeQueue.takeLast(WINDOW_SIZE)) {
                queueArray.put(org.json.JSONObject().apply {
                    put("id", q.id)
                    put("title", q.title)
                    put("artist", q.artist)
                    put("album", q.album)
                    put("duration_sec", q.durationSec)
                    put("filepath", q.filepath)
                    put("cover_art_path", q.coverArtPath)
                    put("bpm", q.bpm.toDouble())
                    put("key", q.key)
                })
            }

            val seedBpm = if (seedTrack.bpm > 0f) seedTrack.bpm else 120f
            val nativeResultJson = NativeBridge.rustScoreAndRankRadioCandidates(
                candidatesJson = candidateArray.toString(),
                seedBpm = seedBpm,
                seedKey = seedTrack.key.trim().uppercase(),
                seedDurSec = seedTrack.durationSec,
                seedSig = seedTrack.signature(),
                queueJson = queueArray.toString()
            )

            if (!nativeResultJson.isNullOrBlank()) {
                val parsedArray = org.json.JSONArray(nativeResultJson)
                if (parsedArray.length() > 0) {
                    val resultList = mutableListOf<Track>()
                    for (i in 0 until parsedArray.length()) {
                        val obj = parsedArray.getJSONObject(i)
                        resultList.add(
                            Track(
                                id = obj.optInt("id", 0),
                                title = obj.optString("title", ""),
                                artist = obj.optString("artist", ""),
                                album = obj.optString("album", ""),
                                durationSec = obj.optInt("duration_sec", 0),
                                filepath = obj.optString("filepath", ""),
                                coverArtPath = obj.optString("cover_art_path", ""),
                                bpm = obj.optDouble("bpm", 120.0).toFloat(),
                                key = obj.optString("key", "")
                            )
                        )
                    }
                    return resultList
                }
            }
        } catch (_: Throwable) {
            // Fallback to pure Kotlin implementation below
        }

        val seenSignatures = HashSet<String>()
        val artistCounts = mutableMapOf<String, Int>()
        val rankedList = mutableListOf<Pair<Track, Float>>()

        // 1. Prime historical artist saturation & seen tracks from existing queue window
        val queueWindow = activeQueue.takeLast(WINDOW_SIZE)
        queueWindow.forEach { track ->
            val normArtist = track.artist.trim().lowercase()
            artistCounts[normArtist] = (artistCounts[normArtist] ?: 0) + 1
            seenSignatures.add(track.signature())
        }

        // Also add seed track to seen signatures
        seenSignatures.add(seedTrack.signature())

        val seedBpm = if (seedTrack.bpm > 0f) seedTrack.bpm else 120f
        val seedKey = seedTrack.key.trim().uppercase()

        // 2. Filter and score candidate pool
        for (track in candidates) {
            val titleLower = track.title.trim().lowercase()
            val artistLower = track.artist.trim().lowercase()

            // A. Junk & Non-Music Compilation Filter
            if (titleLower.isBlank() || artistLower.isBlank()) continue
            if (JUNK_KEYWORDS.any { titleLower.contains(it) }) continue

            // Duration sanity check: filter out 2-hour long compilations unless seed is also long
            if (seedTrack.durationSec in 60..600 && (track.durationSec > 720 || track.durationSec < 35)) {
                continue
            }

            // B. Root Title & Fuzzy Duplicate Check
            val sig = track.signature()
            if (seenSignatures.contains(sig)) continue
            if (queueWindow.any { FuzzyTitleMatcher.isSameSongVariation(it.title, it.artist, track.title, track.artist) }) {
                continue
            }

            // C. Strict Artist Saturation Ceiling (Max 2 tracks per artist in window)
            val currentArtistCount = artistCounts[artistLower] ?: 0
            if (currentArtistCount >= MAX_TRACKS_PER_ARTIST) continue

            // D. Compute Composite Acoustic Score
            val score = computeCompositeScore(track, seedBpm, seedKey, currentArtistCount)
            rankedList.add(track to score)

            seenSignatures.add(sig)
            artistCounts[artistLower] = currentArtistCount + 1
        }

        // 3. Sort by highest affinity score
        return rankedList.sortedByDescending { it.second }.map { it.first }
    }

    private fun computeCompositeScore(
        candidate: Track,
        seedBpm: Float,
        seedKey: String,
        artistFrequency: Int
    ): Float {
        var score = 100.0f

        // 1. Gaussian BPM Proximity (Sigma = 25 BPM)
        if (candidate.bpm > 0f && seedBpm > 0f) {
            val bpmDiff = abs(candidate.bpm - seedBpm)
            val bpmFactor = exp(-((bpmDiff.toDouble().pow(2.0)) / (2.0 * 25.0 * 25.0))).toFloat()
            score += bpmFactor * 30.0f
        } else {
            // Neutral baseline affinity for un-analyzed or stream candidates
            score += 25.0f
        }

        // 2. Camelot Key Harmonic Compatibility
        if (candidate.key.isNotBlank() && seedKey.isNotBlank()) {
            val keyDistance = calculateCamelotDistance(candidate.key.trim().uppercase(), seedKey)
            when (keyDistance) {
                0 -> score += 25.0f // Exact harmonic match
                1 -> score += 15.0f // Harmonic neighbor
                else -> score -= 5.0f
            }
        }

        // 3. Artist Diversity Penalty (mild damping for 2nd track by same artist)
        if (artistFrequency > 0) {
            score -= 12.0f * artistFrequency
        }

        return score
    }

    private fun calculateCamelotDistance(keyA: String, keyB: String): Int {
        val numA = keyA.filter { it.isDigit() }.toIntOrNull() ?: return 2
        val letterA = keyA.filter { it.isLetter() }
        val numB = keyB.filter { it.isDigit() }.toIntOrNull() ?: return 2
        val letterB = keyB.filter { it.isLetter() }

        if (letterA == letterB) {
            val diff = abs(numA - numB)
            return if (diff > 6) 12 - diff else diff
        }
        if (numA == numB) {
            return 1 // Relative major/minor modulation
        }
        return 2
    }

    private fun Track.signature(): String =
        "${title.trim().lowercase()}:::${artist.trim().lowercase()}"
}
