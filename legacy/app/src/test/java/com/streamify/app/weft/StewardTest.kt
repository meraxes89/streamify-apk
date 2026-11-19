package com.streamify.app.weft

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * STEWARD LIFECYCLE TESTS (pure JVM)
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * The Steward owns every Weft channel and enforces the I6 revocation
 * ordering on scope exit: a late producer publish after teardown is a
 * DROPPED_REVOKED no-op, never a write into an unowned buffer.
 */
class StewardTest {

    @Test
    fun `weftSized allocates independent live channels`() {
        val steward = Steward()
        val a = steward.weftSized(56)
        val b = steward.weftSized(56)
        a.wBegin().putInt(0, 111)
        a.publish(1, 56)
        b.wBegin().putInt(0, 222)
        b.publish(1, 56)
        a.claim(); b.claim()
        assertEquals(111, a.rLiveBuf().getInt(0))
        assertEquals(222, b.rLiveBuf().getInt(0))
        assertEquals(2, steward.stats().weftCount)
    }

    @Test
    fun `reified weft factory sizes primitive element layouts`() {
        val steward = Steward()
        assertEquals(56, steward.weft<FloatArray>(14).payloadMax)
        assertEquals(56, steward.weft<IntArray>(14).payloadMax)
        assertEquals(112, steward.weft<DoubleArray>(14).payloadMax)
        assertEquals(28, steward.weft<ShortArray>(14).payloadMax)
        assertEquals(14, steward.weft<ByteArray>(14).payloadMax)
    }

    @Test
    fun `release revokes a single channel with I6 ordering`() {
        val steward = Steward()
        val w = steward.weftSized(56)
        steward.release(w)
        assertTrue(w.isRevoked())
        assertEquals(PubResult.DROPPED_REVOKED, w.publish(1, 56))
        // The steward no longer tracks the released channel.
        assertEquals(0, steward.stats().weftCount)
    }

    @Test
    fun `releaseAll is idempotent and gates future allocation`() {
        val steward = Steward()
        val a = steward.weftSized(56)
        val b = steward.weftSized(56)
        steward.releaseAll()
        steward.releaseAll()   // idempotent

        assertTrue(a.isRevoked())
        assertTrue(b.isRevoked())
        try {
            steward.weftSized(56)
            throw AssertionError("expected IllegalStateException after releaseAll")
        } catch (expected: IllegalStateException) { }
    }

    @Test
    fun `stats aggregate publishes and reads across channels`() {
        val steward = Steward()
        val a = steward.weftSized(56)
        val b = steward.weftSized(56)
        a.publish(1, 56); a.publish(2, 56)
        b.publish(1, 56)
        a.claim(); a.claim(); b.claim()
        val stats = steward.stats()
        assertEquals(2, stats.weftCount)
        assertEquals(3L, stats.totalPublishes)
        assertEquals(3L, stats.totalReads)
    }

    @Test
    fun `dumpLeaks reports channels still bound to the scope`() {
        val steward = Steward()
        steward.weftSized(56)
        steward.weftSized(56)
        assertEquals(2, steward.dumpLeaks().size)
        steward.releaseAll()
        assertEquals(0, steward.dumpLeaks().size)
    }

    @Test
    fun `onCleared releases every bound channel`() {
        // The Steward is OPEN precisely so tests can drive onCleared()
        // (the ViewModel testing idiom — see the vendored source note).
        class TestSteward : Steward() {
            fun simulateScopeExit() = onCleared()
        }
        val steward = TestSteward()
        val w = steward.weftSized(56)
        steward.simulateScopeExit()
        assertTrue(w.isRevoked())
    }
}
