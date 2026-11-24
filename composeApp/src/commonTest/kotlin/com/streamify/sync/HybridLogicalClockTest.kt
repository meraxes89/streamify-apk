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

        // Counter increments in lowest 16 bits
        assertEquals((t1 and 0xFFFFL) + 1L, t2 and 0xFFFFL)
        assertEquals((t2 and 0xFFFFL) + 1L, t3 and 0xFFFFL)

        // Physical time packed in upper 48 bits
        assertEquals(physical, t1 ushr 16)
        assertEquals(physical, t2 ushr 16)
        assertEquals(physical, t3 ushr 16)
    }

    @Test
    fun resetsLogicalCounterWhenPhysicalTimeAdvances() {
        val p1 = 1700000000000L
        val p2 = 1700000001000L

        val t1 = HybridLogicalClock.now(p1)
        val t2 = HybridLogicalClock.now(p1)
        assertEquals(1L, t2 and 0xFFFFL)

        val t3 = HybridLogicalClock.now(p2)
        assertEquals(p2, t3 ushr 16)
        assertEquals(0L, t3 and 0xFFFFL)
    }

    @Test
    fun generatesValidUuidV4Format() {
        val uuid = HybridLogicalClock.randomUuid()
        val regex = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")
        assertTrue(regex.matches(uuid), "Generated UUID '$uuid' should conform to RFC 4122 v4 format")
    }
}
