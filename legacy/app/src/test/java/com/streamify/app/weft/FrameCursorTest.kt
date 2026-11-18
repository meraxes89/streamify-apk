package com.streamify.app.weft

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * FRAME CURSOR TESTS (RFC-0008 freshness telemetry, pure JVM)
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * The Triad drops intermediate frames by design (latest-wins IS the
 * semantics of display). The cursor makes those drops measurable:
 *     framesBehind = seq_now - seq_prev - 1
 */
class FrameCursorTest {

    private fun publishPose(w: Weft, seq: Int) {
        w.wBegin().putInt(0, seq)
        w.publish(seq, 8)
    }

    @Test
    fun `first claim reports null frame baseline with zero drops`() {
        val w = Weft(8)
        val cursor = FrameCursor()
        val claim = cursor.claim(w)
        assertTrue(claim.first)
        assertEquals(0, claim.seq)
        assertEquals(0, claim.framesBehind)
        assertEquals(1, cursor.claims)
        assertEquals(0, cursor.totalDropped)
    }

    @Test
    fun `frames behind counts publishes the reader never saw`() {
        val w = Weft(8)
        val cursor = FrameCursor()

        cursor.claim(w)          // baseline: null frame (seq 0)
        publishPose(w, 1)
        publishPose(w, 2)
        publishPose(w, 3)        // reader claims 3 having missed 2

        val claim = cursor.claim(w)
        assertFalse(claim.first)
        assertEquals(3, claim.seq)
        assertEquals(2, claim.framesBehind)
        assertEquals(2, cursor.totalDropped)
    }

    @Test
    fun `no drops when the reader keeps pace with the producer`() {
        val w = Weft(8)
        val cursor = FrameCursor()
        repeat(100) { i ->
            publishPose(w, i + 1)
            val claim = cursor.claim(w)
            assertEquals(0, claim.framesBehind)
        }
        assertEquals(0, cursor.totalDropped)
        assertEquals(100, cursor.claims)
    }

    @Test
    fun `total dropped accumulates across separated bursts`() {
        val w = Weft(8)
        val cursor = FrameCursor()
        cursor.claim(w)

        publishPose(w, 1); publishPose(w, 2); publishPose(w, 3)
        cursor.claim(w)                       // behind by 2
        publishPose(w, 4)
        cursor.claim(w)                       // behind by 0
        publishPose(w, 5); publishPose(w, 6); publishPose(w, 7); publishPose(w, 8)
        cursor.claim(w)                       // behind by 3

        assertEquals(5, cursor.totalDropped)
        assertEquals(4, cursor.claims)
    }

    @Test
    fun `writer reset backwards seq does not invent drops`() {
        val w = Weft(8)
        val cursor = FrameCursor()
        publishPose(w, 50)
        cursor.claim(w)          // lastSeq = 50
        publishPose(w, 10)       // writer reset: seq goes backwards
        val claim = cursor.claim(w)
        assertEquals(10, claim.seq)
        assertEquals(0, claim.framesBehind)   // no accounting across resets
    }

    @Test
    fun `claim exposes a live zero-copy view of the held payload`() {
        val w = Weft(8)
        val cursor = FrameCursor()
        publishPose(w, 7)
        val claim = cursor.claim(w)
        assertEquals(7, claim.buf.getInt(0))

        // A3: the view is LIVE over the reader-held buffer — a subsequent
        // publish (into a different slot) never mutates it retroactively.
        publishPose(w, 8)
        assertEquals(7, claim.buf.getInt(0))
    }
}
