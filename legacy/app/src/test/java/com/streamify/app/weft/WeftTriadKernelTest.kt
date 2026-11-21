package com.streamify.app.weft

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * WEFT TRIAD KERNEL TESTS (pure JVM)
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * Verifies the vendored kernel's protocol invariants on the JVM shard:
 *  • single-atomic-exchange publish/claim roundtrip
 *  • envelope + canary integrity (frame identity under exchange)
 *  • the TIER4 §5 validation wall (payload bounds)
 *  • the I6 revoke handshake (DROPPED_REVOKED + epoch ACK)
 *  • SERIES-7 little-endian wire order through cursor slices
 *  • zero-allocation bulk reads (rLiveWords) parity with rLiveBuf
 *  • concurrent producer/reader latest-wins + no torn frames
 */
class WeftTriadKernelTest {

    // ── Publish / claim roundtrip ──────────────────────────────────────────

    @Test
    fun `publish and claim roundtrip returns freshest payload`() {
        val w = Weft(8)
        val cur = w.wBegin()
        cur.putInt(0, 0xCAFEBABE.toInt())
        assertEquals(PubResult.OK, w.publish(seq = 1, payloadLen = 8))

        assertEquals(1, w.claim())
        assertEquals(0xCAFEBABE.toInt(), w.rLiveBuf().getInt(0))
    }

    @Test
    fun `null frame is a valid initial state with seq zero`() {
        val w = Weft(16)
        w.claim()
        assertEquals(0, w.rSeq())
        assertEquals(WEFT_MAGIC, w.rMagic())
        assertEquals(16, w.rPayloadLen())
        assertEquals(0L, w.rCanary())
    }

    @Test
    fun `envelope carries magic version seq and payload length`() {
        val w = Weft(56)
        val cur = w.wBegin()
        cur.putFloat(0, 1.5f)
        w.publish(seq = 42, payloadLen = 56)
        w.claim()

        assertEquals(WEFT_MAGIC, w.rMagic())
        assertEquals(42, w.rSeq())
        assertEquals(56, w.rPayloadLen())
        // Version is exposed through the debug view's buffer snapshot.
        val live = w.debugState().bufs.first { it.slotIdx == w.debugState().rWork }
        assertEquals(WEFT_VERSION_1.toInt(), live.version)
    }

    @Test
    fun `canary equals seq at buffer tail`() {
        val w = Weft(56)
        w.publish(seq = 7, payloadLen = 56)
        w.claim()
        assertEquals(7L, w.rCanary())
    }

    // ── TIER4 §5 validation wall ───────────────────────────────────────────

    @Test
    fun `payloadMax zero is rejected`() {
        try {
            Weft(0)
            fail("expected IllegalArgumentException for payloadMax = 0")
        } catch (expected: IllegalArgumentException) { }
    }

    @Test
    fun `payloadMax above the one MiB wall is rejected`() {
        try {
            Weft(WEFT_PAYLOAD_MAX_LIMIT + 1)
            fail("expected IllegalArgumentException above the payload wall")
        } catch (expected: IllegalArgumentException) { }
    }

    @Test
    fun `negative payload length is refused whole and counted`() {
        val w = Weft(56)
        assertEquals(PubResult.INVALID, w.publish(seq = 1, payloadLen = -1))
        assertEquals(PubResult.INVALID, w.publish(seq = 1, payloadLen = 57))
        assertEquals(2L, w.tInvalidCount())
        // Nothing was published: the channel still serves the null frame.
        w.claim()
        assertEquals(0, w.rSeq())
    }

    // ── I6 revoke handshake ────────────────────────────────────────────────

    @Test
    fun `revoked publish drops and acks via the epoch handshake`() {
        val w = Weft(56)
        w.publish(seq = 1, payloadLen = 56)
        val epochBefore = w.epochVal()

        w.revoke()
        assertTrue(w.isRevoked())
        assertEquals(PubResult.DROPPED_REVOKED, w.publish(seq = 2, payloadLen = 56))
        assertEquals(PubResult.DROPPED_REVOKED, w.publish(seq = 3, payloadLen = 56))

        assertEquals(epochBefore + 2, w.epochVal())
        assertEquals(2L, w.tDropCount())
        // The already-published frame remains readable — revoke stops future
        // publishes, it does not poison the held frame.
        w.claim()
        assertEquals(1, w.rSeq())
    }

    @Test
    fun `reclaim observes the writer ack`() {
        val w = Weft(56)
        w.publish(seq = 1, payloadLen = 56)
        val preRevokeEpoch = w.epochVal()
        w.revoke()

        // Simulate the late-writer ACK landing on another thread.
        Thread {
            Thread.sleep(20)
            w.publish(seq = 2, payloadLen = 56)
        }.start()

        assertTrue(w.reclaim(preRevokeEpoch, timeoutMs = 500))
    }

    @Test
    fun `reclaim timeout is counted not silent`() {
        val w = Weft(56)
        w.setMaxReclaimTimeout(50)
        val preRevokeEpoch = w.epochVal()
        w.revoke()
        val timeoutsBefore = w.tReclaimTimeoutsCount()
        assertFalse(w.reclaim(preRevokeEpoch, timeoutMs = 30))
        assertEquals(timeoutsBefore + 1, w.tReclaimTimeoutsCount())
    }

    // ── SERIES-7 wire order (little-endian through slices) ─────────────────

    @Test
    fun `float written through wBegin reads back identical through rLiveBuf`() {
        val w = Weft(56)
        val cur = w.wBegin()
        cur.putFloat(0, -1234.5678f)
        cur.putFloat(52, Float.MAX_VALUE)
        w.publish(seq = 1, payloadLen = 56)
        w.claim()

        val buf = w.rLiveBuf()
        assertEquals(-1234.5678f, buf.getFloat(0), 0f)
        assertEquals(Float.MAX_VALUE, buf.getFloat(52), 0f)
    }

    @Test
    fun `payload bytes are little-endian on the wire`() {
        val w = Weft(8)
        w.wBegin().putInt(0, 0x01020304)
        w.publish(seq = 1, payloadLen = 8)
        w.claim()

        // LE: least-significant byte first → 04 03 02 01
        assertEquals(0x04, w.rLiveBuf().get(0).toInt() and 0xFF)
        assertEquals(0x03, w.rLiveBuf().get(1).toInt() and 0xFF)
        assertEquals(0x02, w.rLiveBuf().get(2).toInt() and 0xFF)
        assertEquals(0x01, w.rLiveBuf().get(3).toInt() and 0xFF)
    }

    // ── Zero-allocation bulk reads (Law 2) ─────────────────────────────────

    @Test
    fun `rLiveWords parity with rLiveBuf for a full float pose`() {
        val w = Weft(56)
        val pose = floatArrayOf(
            1080.25f, 2400.5f, 1.125f, 0.875f, 0.70710678f,
            12.5f, -7.25f, 0.5f, 1f, 0f, 1f, 0f, 3.25f, 0f
        )
        val cur = w.wBegin()
        for (i in pose.indices) cur.putFloat(4 * i, pose[i])
        w.publish(seq = 9, payloadLen = 56)
        w.claim()

        val words = IntArray(14)
        assertEquals(14, w.rLiveWords(words))
        for (i in pose.indices) {
            assertEquals(pose[i], Float.fromBits(words[i]), 0f)
            assertEquals(pose[i], w.rLiveBuf().getFloat(4 * i), 0f)
        }
    }

    @Test
    fun `rLiveWords respects offset and word bound`() {
        val w = Weft(12)
        w.wBegin().putInt(4, 0xABAD1DEA.toInt())   // payload word 1
        w.publish(seq = 1, payloadLen = 12)
        w.claim()

        val words = IntArray(4)
        assertEquals(2, w.rLiveWords(words, offsetWords = 1))
        assertEquals(0xABAD1DEA.toInt(), words[0])
    }

    // ── Envelope decode + negotiate ────────────────────────────────────────

    @Test
    fun `envelope decode classifies short magic and header faults`() {
        val good = ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN)
        envelopeEncodeV1(good, 5, 8)
        assertEquals(DecodeResult.OK, envelopeDecode(good, 64))

        val short = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(DecodeResult.SHORT, envelopeDecode(short, 8))

        val badMagic = ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN)
        badMagic.putInt(0, 0xDEADBEEF.toInt())
        assertEquals(DecodeResult.BAD_MAGIC, envelopeDecode(badMagic, 64))

        val badHeader = ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN)
        badHeader.putInt(0, WEFT_MAGIC)
        badHeader.putShort(6, 8.toShort()) // headerSize < 16
        assertEquals(DecodeResult.BAD_HEADER, envelopeDecode(badHeader, 64))
    }

    @Test
    fun `negotiate picks the highest mutually supported version`() {
        assertEquals(1, negotiate(WEFT_VERSION_1, shortArrayOf(1)).toInt())
        assertEquals(0, negotiate(WEFT_VERSION_1, shortArrayOf(2, 3)).toInt())
        assertEquals(2, negotiate(3, shortArrayOf(1, 2)).toInt())
    }

    // ── Ownership + debug view ──────────────────────────────────────────────

    @Test
    fun `triad slots stay distinct through publish claim cycles`() {
        val w = Weft(56)
        repeat(50) { i ->
            w.publish(seq = i, payloadLen = 56)
            w.claim()
            val v = w.debugState()
            assertTrue(v.latest in 0..2)
            assertTrue(v.wWork in 0..2)
            assertTrue(v.rWork in 0..2)
        }
    }

    @Test
    fun `successive publishes are visible to successive claims`() {
        val w = Weft(56)
        var seen = 0
        repeat(25) { i ->
            w.wBegin().putInt(0, i)
            w.publish(seq = i + 1, payloadLen = 56)
            w.claim()
            assertEquals(i + 1, w.rSeq())
            assertEquals(i, w.rLiveBuf().getInt(0))
            seen++
        }
        assertEquals(25, seen)
        assertEquals(25L, w.tPublishCount())
        assertEquals(25L, w.tClaimCount())
    }

    // ── Concurrency: latest-wins, no torn frames ───────────────────────────

    @Test(timeout = 30_000)
    fun `concurrent producer and reader never tears a frame`() {
        val w = Weft(56)
        val stop = AtomicBoolean(false)
        val producerErrors = AtomicInteger(0)

        val producer = Thread {
            try {
                var seq = 0
                while (!stop.get()) {
                    val cur = w.wBegin()
                    val s = ++seq
                    cur.putInt(0, s)
                    cur.putInt(4, s * 2)
                    cur.putInt(52, -s)
                    w.publish(s, 56)
                }
            } catch (t: Throwable) {
                producerErrors.incrementAndGet()
            }
        }
        producer.start()

        // The reader hammers claims and validates every observed frame
        // end-to-end: envelope seq, canary, and all payload words must be
        // mutually consistent (ownership by exchange makes tearing
        // impossible — this test would catch any regression of that).
        //
        // NOTE on latest-wins semantics: a reader that claims faster than
        // the producer publishes will legitimately re-acquire its own
        // previously-held buffer (the exchange ping-pongs r_work ↔ latest).
        // Seq monotonicity per claim is therefore NOT an invariant; frame
        // INTEGRITY and observed PROGRESS are.
        var maxSeq = 0
        var claims = 0
        try {
            while (claims < 100_000) {
                w.claim()
                val seq = w.rSeq()
                if (seq > maxSeq) maxSeq = seq
                if (seq > 0) {
                    if (w.rCanary() != seq.toLong()) {
                        fail("canary torn at seq $seq")
                    }
                    val buf = w.rLiveBuf()
                    if (buf.getInt(0) != seq || buf.getInt(4) != seq * 2 || buf.getInt(52) != -seq) {
                        fail("payload torn at seq $seq")
                    }
                } else {
                    // The null frame (seq 0) must remain internally consistent.
                    assertEquals(0L, w.rCanary())
                }
                claims++
            }
        } finally {
            stop.set(true)
            producer.join(5_000)
        }

        assertEquals(0, producerErrors.get())
        assertTrue("reader must observe producer progress", maxSeq > 0)
        assertTrue(w.tPublishCount() > 0)
        assertTrue(w.tClaimCount() >= 100_000)
    }

    @Test
    fun `two wefts are fully independent channels`() {
        val a = Weft(8)
        val b = Weft(8)
        assertNotEquals(a, b)
        a.wBegin().putInt(0, 111)
        a.publish(1, 8)
        b.wBegin().putInt(0, 222)
        b.publish(1, 8)
        a.claim(); b.claim()
        assertEquals(111, a.rLiveBuf().getInt(0))
        assertEquals(222, b.rLiveBuf().getInt(0))
    }
}
