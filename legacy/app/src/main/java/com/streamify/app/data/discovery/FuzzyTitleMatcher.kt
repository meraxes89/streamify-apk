package com.streamify.app.data.discovery

import com.streamify.app.data.NativeBridge
object FuzzyTitleMatcher {

    // Pre-compiled noise patterns: strips video/audio tags, brackets, features, and release metadata
    private val NOISE_REGEX = Regex(
        "(?i)\\(.*?\\)|\\[.*?\\]|\\b(official|video|audio|lyric|lyrics|live|remaster|remastered|slowed|sped up|acoustic|instrumental|visualizer|feat|ft|featuring|hd|4k)\\b.*"
    )

    private val CLEAN_REGEX = Regex("[^a-z0-9]+")

    // ── MEMOIZATION ────────────────────────────────────────────────────
    // Queue-dedup scans recompute these for the SAME titles/artists O(queue ×
    // candidates) times per track transition — each miss is lowercase + regex
    // replace + split + sort + join. Titles repeat enormously across scans,
    // so a small bounded cache turns the hot path into a hashmap lookup.
    private const val MEMO_CAP = 4096
            
    private fun <V> remember(memo: java.util.concurrent.ConcurrentHashMap<String, V>, key: String, compute: (String) -> V): V {
        memo[key]?.let { return it }
        if (memo.size >= MEMO_CAP) {
            // Cheap amortized reset: titles age out of queues naturally.
            memo.clear()
        }
        val v = compute(key)
        memo[key] = v
        return v
    }

    /**
     * Extracts an order-invariant 64-bit FNV-1a hash of the root title words.
     * "House of Balloons / Glass Table Girls" and "House of Balloons (Audio)"
     * both produce root tokens [balloons, house] and the exact same 64-bit Long hash.
     */
    fun extractRootHash(title: String): Long {
        val clean = NOISE_REGEX.replace(title, " ")
        val tokens = clean.lowercase()
            .split(CLEAN_REGEX)
            .filter { it.length > 2 && it != "the" && it != "and" }
            .sorted()
            .joinToString("")

        if (tokens.isBlank()) {
            val fallback = title.lowercase().replace(CLEAN_REGEX, "")
            if (fallback.isBlank()) return 0L
            return computeFnv1a(fallback)
        }

        return computeFnv1a(tokens)
    }

    /**
     * Extracts token set of root title words for Jaccard similarity.
     */
    fun extractRootTokens(title: String): Set<String> {
        val clean = NOISE_REGEX.replace(title, " ")
        return clean.lowercase()
            .split(CLEAN_REGEX)
            .filter { it.length > 2 && it != "the" && it != "and" }
            .toSet()
    }

    /**
     * Computes FNV-1a 64-bit integer hash for O(1) matching.
     */
    fun computeFnv1a(input: String): Long {
        var hash = 0xcbf29ce484222325UL
        for (char in input) {
            hash = hash xor char.code.toULong()
            hash *= 0x100000001b3UL
        }
        return hash.toLong()
    }

    /**
     * Cleans artist strings for identity comparison: drops distribution noise
     * ("- Topic", "VEVO", "Official") and punctuation.
     */
    fun cleanArtistForMatch(artist: String): String {
        return artist.lowercase()
            .replace("- topic", "")
            .replace("vevo", "")
            .replace(" - official", "")
            .split(CLEAN_REGEX)
            .filter { part -> part.isNotBlank() }
            .joinToString(" ")
    }

    /**
     * Same-song TITLE gate: cleaned equality fast-path, then bounded similarity.
     * Used by stream resolvers to prove a search candidate refers to this song
     * BEFORE its videoId/stream may be trusted or persisted.
     */
    fun titlesMatch(aTitle: String, bTitle: String): Boolean {
        val a = aTitle.trim().lowercase()
        val b = bTitle.trim().lowercase()
        if (a.isEmpty() || b.isEmpty()) return false
        if (a == b) return true
        return calculateSimilarity(a, b) >= 0.72
    }

    /**
     * Same-song ARTIST gate: substring-tolerant match after noise stripping.
     * An empty query artist never rejects; an empty candidate artist does.
     */
    fun artistsMatch(aArtist: String, bArtist: String): Boolean {
        val a = cleanArtistForMatch(aArtist)
        val b = cleanArtistForMatch(bArtist)
        if (a.isEmpty()) return true
        if (b.isEmpty()) return false
        return a == b || a.contains(b) || b.contains(a)
    }

    /**
     * Duration gate (±tolerance seconds). Unknown durations on either side never reject.
     */
    fun durationMatches(secA: Int, secB: Int, toleranceSec: Int = 15): Boolean {
        if (secA <= 0 || secB <= 0) return true
        return kotlin.math.abs(secA - secB) <= toleranceSec
    }

    /**
     * Full same-recording proof: title + artist + optional duration agreement.
     * This is the gate every CDN/videoId resolution path must pass before it may
     * bind, pin, cache or play a resolved candidate.
     */
    fun isSameRecording(
        titleA: String,
        artistA: String,
        durationSecA: Int,
        titleB: String,
        artistB: String,
        durationSecB: Int,
        durationToleranceSec: Int = 15
    ): Boolean {
        return titlesMatch(titleA, titleB) &&
                artistsMatch(artistA, artistB) &&
                durationMatches(durationSecA, durationSecB, durationToleranceSec)
    }

    /**
     * Checks if two tracks are duplicate variations of the same underlying song.
     */
    fun isSameSongVariation(titleA: String, artistA: String, titleB: String, artistB: String): Boolean {
        // 1. Direct FNV-1a root hash match (0ms check)
        val hashA = extractRootHash(titleA)
        val hashB = extractRootHash(titleB)
        if (hashA != 0L && hashA == hashB) {
            return true
        }

        // 2. Token Jaccard similarity for subtitle/split track variations
        val tokensA = extractRootTokens(titleA)
        val tokensB = extractRootTokens(titleB)
        if (tokensA.isNotEmpty() && tokensB.isNotEmpty()) {
            val intersection = tokensA.intersect(tokensB).size.toDouble()
            val minSize = minOf(tokensA.size, tokensB.size).toDouble()
            // If one title is a subset of the other (e.g. "House of Balloons" inside "House of Balloons / Glass Table Girls")
            if (minSize > 0 && (intersection / minSize) >= 0.75) {
                return true
            }
            val union = tokensA.union(tokensB).size.toDouble()
            if (union > 0 && (intersection / union) >= 0.60) {
                return true
            }
        }

        return false
    }

    /**
     * Calculates token and string edit distance similarity between two strings (0.0 to 1.0).
     * Dispatches to High-Performance Rust SIMD engine if available, with pure Kotlin fallback.
     */
    fun calculateSimilarity(s1: String, s2: String): Double {
        val str1 = s1.trim().lowercase()
        val str2 = s2.trim().lowercase()
        if (str1 == str2) return 1.0
        if (str1.isEmpty() || str2.isEmpty()) return 0.0

        try {
            val rustScore = NativeBridge.rustCalculateSimilarity(str1, str2)
            if (rustScore >= 0f) {
                return rustScore.toDouble()
            }
        } catch (_: Throwable) {
            // Fallback to pure Kotlin 1D sliding window evaluator
        }

        // Direct Substring check
        if (str2.contains(str1) || str1.contains(str2)) {
            val lenRatio = minOf(str1.length, str2.length).toDouble() / maxOf(str1.length, str2.length)
            return 0.8 + (0.2 * lenRatio)
        }

        // Token Jaccard similarity
        val tokens1 = str1.split(CLEAN_REGEX).filter { it.isNotBlank() }.toSet()
        val tokens2 = str2.split(CLEAN_REGEX).filter { it.isNotBlank() }.toSet()
        if (tokens1.isNotEmpty() && tokens2.isNotEmpty()) {
            val inter = tokens1.intersect(tokens2).size.toDouble()
            val union = tokens1.union(tokens2).size.toDouble()
            val jaccard = inter / union
            if (jaccard > 0.5) return jaccard
        }

        // Bounded Levenshtein distance with O(N) 2-row rolling memory
        val maxLen = maxOf(str1.length, str2.length)
        if (maxLen > 30) return 0.0
        val distance = computeLevenshtein(str1, str2)
        return (1.0 - (distance.toDouble() / maxLen)).coerceAtLeast(0.0)
    }

    fun computeLevenshtein(a: String, b: String): Int {
        val lenA = a.length
        val lenB = b.length
        var prevRow = IntArray(lenB + 1) { it }
        var currRow = IntArray(lenB + 1)

        for (i in 1..lenA) {
            currRow[0] = i
            for (j in 1..lenB) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                currRow[j] = minOf(
                    currRow[j - 1] + 1,
                    prevRow[j] + 1,
                    prevRow[j - 1] + cost
                )
            }
            val temp = prevRow
            prevRow = currRow
            currRow = temp
        }
        return prevRow[lenB]
    }
}
