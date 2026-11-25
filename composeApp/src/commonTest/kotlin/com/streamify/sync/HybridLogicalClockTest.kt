package com.streamify.sync

import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HybridLogicalClockTest {

    @BeforeTest
    fun setUp() {
        HybridLogicalClock.resetForTesting()
    }

    @Test
    fun generatesMonotonicallyIncreasingTimestampsForSamePhysicalTime() {
        val physical = 1700000000000L
        val t1 = HybridLogicalClock.now(physical)
        val t2 = HybridLogicalClock.now(physical)
        val t3 = HybridLogicalClock.now(physical)

        assertTrue(t2 > t1, "t2 should be greater than t1")
        assertTrue(t3 > t2, "t3 should be greater than t2")

        // Counter increments in lowest 22 bits
        assertEquals(HybridLogicalClock.unpackCounter(t1) + 1, HybridLogicalClock.unpackCounter(t2))
        assertEquals(HybridLogicalClock.unpackCounter(t2) + 1, HybridLogicalClock.unpackCounter(t3))

        // Physical time packed in upper 42 bits
        assertEquals(physical, HybridLogicalClock.unpackPhysical(t1))
        assertEquals(physical, HybridLogicalClock.unpackPhysical(t2))
        assertEquals(physical, HybridLogicalClock.unpackPhysical(t3))
    }

    @Test
    fun resetsLogicalCounterWhenPhysicalTimeAdvances() {
        val p1 = 1700000000000L
        val p2 = 1700000001000L

        val t1 = HybridLogicalClock.now(p1)
        val t2 = HybridLogicalClock.now(p1)
        assertEquals(1, HybridLogicalClock.unpackCounter(t2))

        val t3 = HybridLogicalClock.now(p2)
        assertEquals(p2, HybridLogicalClock.unpackPhysical(t3))
        assertEquals(0, HybridLogicalClock.unpackCounter(t3))
    }

    @Test
    fun generatesValidUuidV4Format() {
        val uuid = HybridLogicalClock.randomUuid()
        val regex = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")
        assertTrue(regex.matches(uuid), "Generated UUID '$uuid' should conform to RFC 4122 v4 format")
    }
}
