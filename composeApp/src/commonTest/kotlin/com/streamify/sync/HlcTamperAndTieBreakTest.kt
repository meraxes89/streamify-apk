package com.streamify.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HlcTamperAndTieBreakTest {

    @Test
    fun testMonotonicityUnderEqualPhysicalTime() {
        HybridLogicalClock.resetForTesting(lastMs = 0L, counter = 0)
        val hlc1 = HybridLogicalClock.now(1000L)
        val hlc2 = HybridLogicalClock.now(1000L)
        val hlc3 = HybridLogicalClock.now(1000L)

        assertEquals(0, HybridLogicalClock.unpackCounter(hlc1))
        assertEquals(1, HybridLogicalClock.unpackCounter(hlc2))
        assertEquals(2, HybridLogicalClock.unpackCounter(hlc3))
        assertEquals(1000L, HybridLogicalClock.unpackPhysical(hlc1))
        assertTrue(hlc2 > hlc1)
        assertTrue(hlc3 > hlc2)
    }

    @Test
    fun testClockRollbackNeverDecreasesHlc() {
        HybridLogicalClock.resetForTesting(lastMs = 5000L, counter = 10)
        val hlcPast = HybridLogicalClock.now(2000L) // wall clock rolled backwards
        assertEquals(5000L, HybridLogicalClock.unpackPhysical(hlcPast))
        assertEquals(11, HybridLogicalClock.unpackCounter(hlcPast))
    }

    @Test
    fun testServerTimeClamping() {
        HybridLogicalClock.resetForTesting(lastMs = 0L, counter = 0)
        HybridLogicalClock.updateServerTime(serverEpochMs = 1_000_000L, localNowMs = 1_000_000L)

        // Device clock jumps 5 years into future: 1_000_000 + 5 * 365 * 86400 * 1000
        val jumpedMs = 1_000_000L + 157_680_000_000L
        val hlc = HybridLogicalClock.now(jumpedMs)

        val physical = HybridLogicalClock.unpackPhysical(hlc)
        // Must be clamped to serverNow + MAX_SKEW_MS (1_000_000 + 600_000 = 1_600_000)
        assertEquals(1_600_000L, physical)
    }

    @Test
    fun testKulkarniMergeAdvancesPhysicalAndCounter() {
        HybridLogicalClock.resetForTesting(lastMs = 1000L, counter = 5)
        val remoteHlc = HybridLogicalClock.pack(2000L, 42)

        val merged = HybridLogicalClock.merge(remoteHlc, physicalMillis = 1500L)
        assertEquals(2000L, HybridLogicalClock.unpackPhysical(merged))
        assertEquals(43, HybridLogicalClock.unpackCounter(merged))
    }

    @Test
    fun testDeterministicTieBreakByDeviceId() {
        val sameHlc = 123456789L
        val keyA = HlcKey(sameHlc, "device_aaa")
        val keyB = HlcKey(sameHlc, "device_bbb")

        assertTrue(keyA < keyB)
        assertTrue(keyB > keyA)
        assertEquals(0, keyA.compareTo(HlcKey(sameHlc, "device_aaa")))
    }
}
