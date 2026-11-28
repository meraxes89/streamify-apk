package com.streamify.sync.v5

import kotlin.random.Random

/**
 * Collision-resistant Base-62 jittered fractional indexing for playlist track ordering.
 *
 * Parity invariant: bytewise ASCII ordering (`COLLATE "C"` in Postgres, `BINARY` in SQLite,
 * `String.compareTo` in Kotlin) produces identical ordering across all 4 KMP targets + DB.
 */
object FractionalIndex {
    private const val ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
    private const val BASE = 62
    private const val MAX_DIGIT = 61
    const val DEFAULT_ANCHOR = "V"
    const val REBALANCE_THRESHOLD_LENGTH = 64

    fun between(
        prev: String?,
        next: String?,
        jitterRng: () -> Int = { Random.nextInt() },
    ): String {
        require(prev == null || next == null || prev < next) {
            "Invalid bounds: prev ($prev) must be strictly < next ($next)"
        }
        val baseMid = computeStrictMidpoint(prev ?: "", next)
        val jitter = buildJitter(3, jitterRng)
        return baseMid + jitter
    }

    /**
     * Generates [count] evenly spaced valid keys in strict ascending order,
     * used when a collision run (`prev == next`) or key length > [REBALANCE_THRESHOLD_LENGTH] occurs.
     */
    fun rebalance(
        count: Int,
        jitterRng: () -> Int = { Random.nextInt() },
    ): List<String> {
        if (count <= 0) return emptyList()
        val keys = ArrayList<String>(count)
        var prev: String? = null
        repeat(count) {
            val k = between(prev, null, jitterRng)
            keys.add(k)
            prev = k
        }
        return keys
    }

    fun isValid(key: String): Boolean =
        key.length in 1..128 &&
            key.last() != '0' &&
            key.all { it in '0'..'9' || it in 'A'..'Z' || it in 'a'..'z' }

    private fun computeStrictMidpoint(prev: String, next: String?): String {
        val sb = StringBuilder()
        var i = 0
        while (true) {
            val dPrev = if (i < prev.length) charToDigit(prev[i]) else 0
            val dNext = if (next == null) {
                BASE
            } else if (i < next.length) {
                charToDigit(next[i])
            } else {
                error("Unreachable: next exhausted before divergence")
            }

            if (dNext - dPrev >= 2) {
                val mid = (dPrev + dNext) / 2
                sb.append(ALPHABET[mid])
                return sb.toString()
            }

            sb.append(ALPHABET[dPrev])
            if (dNext - dPrev == 1) {
                return appendAbovePrev(sb, prev, i + 1)
            }
            i++
        }
    }

    private fun appendAbovePrev(sb: StringBuilder, prev: String, startIndex: Int): String {
        var i = startIndex
        while (true) {
            val dPrev = if (i < prev.length) charToDigit(prev[i]) else 0
            if (dPrev < MAX_DIGIT) {
                val mid = (dPrev + BASE) / 2
                sb.append(ALPHABET[mid])
                return sb.toString()
            }
            sb.append(ALPHABET[MAX_DIGIT])
            i++
        }
    }

    private fun buildJitter(length: Int, rng: () -> Int): String {
        val sb = StringBuilder(length)
        for (idx in 0 until length) {
            val digit = if (idx == length - 1) {
                1 + (rng().and(0x7FFFFFFF) % (BASE - 1)) // Last char never '0'
            } else {
                rng().and(0x7FFFFFFF) % BASE
            }
            sb.append(ALPHABET[digit])
        }
        return sb.toString()
    }

    private fun charToDigit(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'A'..'Z' -> c - 'A' + 10
        in 'a'..'z' -> c - 'a' + 36
        else -> error("Invalid fractional index char: $c")
    }
}
