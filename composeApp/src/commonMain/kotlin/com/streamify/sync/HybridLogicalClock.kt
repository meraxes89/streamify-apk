package com.streamify.sync

import kotlin.concurrent.Volatile
import kotlin.math.min
import kotlin.time.Clock

/**
 * 64-bit Hybrid Logical Clock v2 per Phase 2 Sync Dataplane Audit (Item 1.1 / §3.2):
 * - 42 bits Unix milliseconds (valid through year 2109) packed above a 22-bit monotonic counter.
 * - Monotonic counter: 4,194,304 operations per millisecond without wrapping.
 * - Forward drift clamping: physical component is clamped against server time + MAX_SKEW_MS (10 min).
 * - Multi-device tie-breaking: [HlcKey] compares `(hlc, deviceId)` deterministically across all targets.
 * - Kulkarni HLC algorithm support: [merge] updates local clock based on pulled remote timestamps.
 */
object HybridLogicalClock {
    const val COUNTER_BITS = 22
    const val COUNTER_MASK = 0x3FFFFFL // (1 shl 22) - 1
    const val MAX_SKEW_MS = 10 * 60 * 1000L // 10 minutes

    private val lock = Any()

    @Volatile
    private var lastPhysicalMs: Long = 0L

    @Volatile
    private var logicalCounter: Int = 0

    @Volatile
    private var lastKnownServerTimeMs: Long = 0L

    /**
     * Updates recorded server time from Date/Timestamp headers.
     */
    fun updateServerTime(serverEpochMs: Long, localNowMs: Long = Clock.System.now().toEpochMilliseconds()) {
        lastKnownServerTimeMs = serverEpochMs
    }

    /**
     * Generates the next monotonic 64-bit HLC timestamp.
     */
    fun now(physicalMillis: Long = Clock.System.now().toEpochMilliseconds()): Long =
        synchronizedLock(lock) {
            val clampedPhysical = if (lastKnownServerTimeMs > 0L && physicalMillis > lastKnownServerTimeMs + MAX_SKEW_MS) {
                lastKnownServerTimeMs + MAX_SKEW_MS
            } else {
                physicalMillis
            }

            if (clampedPhysical > lastPhysicalMs) {
                lastPhysicalMs = clampedPhysical
                logicalCounter = 0
            } else {
                logicalCounter++
                if (logicalCounter > COUNTER_MASK) {
                    // Saturated counter: monotonically bump physical millisecond
                    lastPhysicalMs++
                    logicalCounter = 0
                }
            }
            pack(lastPhysicalMs, logicalCounter)
        }

    /**
     * Kulkarni merge algorithm: advance local clock upon receiving a remote HLC timestamp.
     */
    fun merge(remoteHlc: Long, physicalMillis: Long = Clock.System.now().toEpochMilliseconds()): Long =
        synchronizedLock(lock) {
            val remotePhysical = unpackPhysical(remoteHlc)
            val remoteCounter = unpackCounter(remoteHlc)

            val candidatePhysical = maxOf(physicalMillis, remotePhysical)
            if (candidatePhysical > lastPhysicalMs) {
                lastPhysicalMs = candidatePhysical
                logicalCounter = if (candidatePhysical == remotePhysical) remoteCounter + 1 else 0
            } else if (candidatePhysical == lastPhysicalMs) {
                logicalCounter = maxOf(logicalCounter, remoteCounter) + 1
            } else {
                logicalCounter++
            }

            if (logicalCounter > COUNTER_MASK) {
                lastPhysicalMs++
                logicalCounter = 0
            }
            pack(lastPhysicalMs, logicalCounter)
        }

    fun pack(physicalMs: Long, counter: Int): Long {
        return (physicalMs shl COUNTER_BITS) or (counter.toLong() and COUNTER_MASK)
    }

    fun unpackPhysical(hlc: Long): Long {
        return hlc ushr COUNTER_BITS
    }

    fun unpackCounter(hlc: Long): Int {
        return (hlc and COUNTER_MASK).toInt()
    }

    internal fun resetForTesting(lastMs: Long = 0L, counter: Int = 0, serverTime: Long = 0L) {
        lastPhysicalMs = lastMs
        logicalCounter = counter
        lastKnownServerTimeMs = serverTime
    }

    /**
     * Deterministic, zero-dependency UUIDv4 generator for Kotlin Multiplatform.
     */
    fun randomUuid(): String {
        val bytes = kotlin.random.Random.nextBytes(16)
        bytes[6] = ((bytes[6].toInt() and 0x0f) or 0x40).toByte() // version 4
        bytes[8] = ((bytes[8].toInt() and 0x3f) or 0x80).toByte() // variant
        return buildString(36) {
            for (i in 0 until 16) {
                if (i == 4 || i == 6 || i == 8 || i == 10) append('-')
                val b = bytes[i].toInt() and 0xff
                append(b.toString(16).padStart(2, '0'))
            }
        }
    }
}

/**
 * Deterministic multi-device comparison key. Breaks ties identically across all platforms.
 */
data class HlcKey(
    val hlc: Long,
    val deviceId: String,
) : Comparable<HlcKey> {
    override fun compareTo(other: HlcKey): Int {
        val hlcComp = this.hlc.compareTo(other.hlc)
        if (hlcComp != 0) return hlcComp
        return this.deviceId.compareTo(other.deviceId)
    }
}
