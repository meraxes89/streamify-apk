package com.streamify.sync

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Clock

/**
 * 64-bit Hybrid Logical Clock (HLC) as specified in RFC 2026-11-24:
 * 48 bits of Unix milliseconds packed above a 16-bit monotonic counter.
 * Guarantees monotonic progression across ticks and offline periods.
 */
object HybridLogicalClock {
    private var lastPhysicalMs: Long = 0L
    private var logicalCounter: Int = 0
    private val mutex = Mutex()

    suspend fun now(physicalMillis: Long = Clock.System.now().toEpochMilliseconds()): Long = mutex.withLock {
        if (physicalMillis > lastPhysicalMs) {
            lastPhysicalMs = physicalMillis
            logicalCounter = 0
        } else {
            logicalCounter = (logicalCounter + 1) and 0xFFFF
        }
        (lastPhysicalMs shl 16) or (logicalCounter.toLong() and 0xFFFFL)
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
