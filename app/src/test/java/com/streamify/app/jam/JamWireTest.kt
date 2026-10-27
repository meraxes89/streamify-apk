package com.streamify.app.jam

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JamWire v4 binary frame grammar: every message type round-trips, every
 * malformed frame dies at the codec boundary (never inside the engine).
 */
class JamWireTest {

    private val sender = "a1b2c3d4"

    private fun roundTrip(bytes: ByteArray): JamWire.Frame? = JamWire.decode(bytes)

    // ── Header contract ────────────────────────────────────────────────────

    @Test
    fun `header carries magic, version, type, sender and epoch`() {
        val f = roundTrip(JamWire.encodeSeek(sender, 77L, 12_345L))
        assertNotNull(f)
        f!!
        assertEquals(JamWire.Msg.SEEK, f.msgType)
        assertEquals(sender, f.senderNonce)
        assertEquals(77L, f.epoch)
        assertEquals(8, f.senderNonce.length)
    }

    @Test
    fun `short sender nonces are space-padded and trimmed back`() {
        val f = roundTrip(JamWire.encodeSeek("ab", 1L, 2L))
        assertNotNull(f)
        assertEquals("ab", f!!.senderNonce)
    }

    @Test
    fun `corrupt magic rejected`() {
        val good = JamWire.encodeSeek(sender, 1L, 2L)
        good[0] = 'X'.code.toByte()
        assertNull(roundTrip(good))
    }

    @Test
    fun `future version rejected`() {
        val good = JamWire.encodeSeek(sender, 1L, 2L)
        good[4] = 5 // version u16 LE low byte
        assertNull(roundTrip(good))
    }

    @Test
    fun `truncated and oversize frames rejected`() {
        assertNull(roundTrip(ByteArray(10)))
        assertNull(roundTrip(ByteArray(JamWire.MAX_FRAME_BYTES + 1)))
        val good = JamWire.encodeSeek(sender, 1L, 2L)
        assertNull(roundTrip(good.copyOf(good.size - 1)))
    }

    // ── Per-type round-trips ───────────────────────────────────────────────

    @Test
    fun `tick round-trip`() {
        val f = roundTrip(
            JamWire.encodeTick(sender, 5L, 99L, 1_234_567_890L, 65_000L, 210_000L, true, 1)
        )!!
        val t = f.asTick
        assertNotNull(t)
        t!!
        assertEquals(99L, t.seq)
        assertEquals(1_234_567_890L, t.hostSyncedNanos)
        assertEquals(65_000L, t.positionMs)
        assertEquals(210_000L, t.durationMs)
        assertTrue(t.playing)
        assertEquals(1, t.policy)
    }

    @Test
    fun `presence round-trip with avatar`() {
        val f = roundTrip(
            JamWire.encodePresence(
                sender, 0L, 555L, isHost = true,
                linkType = JamWire.LinkType.WEBRTC,
                name = "RILEY ☕", avatarUrl = "https://img.example/α.png"
            )
        )!!
        val p = JamWire.parsePresence(f)
        assertNotNull(p)
        p!!
        assertEquals(555L, p.syncedNanos)
        assertTrue(p.isHost)
        assertEquals(JamWire.LinkType.WEBRTC, p.linkType)
        assertEquals("RILEY ☕", p.name)
        assertEquals("https://img.example/α.png", p.avatarUrl)
    }

    @Test
    fun `presence round-trip without avatar`() {
        val f = roundTrip(
            JamWire.encodePresence(sender, 0L, 1L, false, JamWire.LinkType.LAN_5GHZ_UDP, "Jo", null)
        )!!
        val p = JamWire.parsePresence(f)!!
        assertEquals("Jo", p.name)
        assertNull(p.avatarUrl)
    }

    @Test
    fun `op frame embeds the sealed 48-byte op verbatim`() {
        val op = JamOpWire(7L, 8L, JamOpWire.OP_ADD, 0, 9L, 0.25, 0L)
        val f = roundTrip(JamWire.encodeOp(sender, 3L, op))!!
        assertEquals(JamOpWire.WIRE_SIZE, f.body.size)
        assertEquals(op, f.asOp)
        assertArrayEquals(JamOpWire.pack(op), f.body)
    }

    @Test
    fun `continuation round-trip`() {
        val f = roundTrip(
            JamWire.encodeContinuation(
                sender, 0L, newEpoch = 12L, successorNonce = "zz99zz99",
                pivotPosMs = 88_000L, pivotSyncedNanos = 2_000_000_000L,
                durationMs = 200_000L, playing = true, trackMatches = false
            )
        )!!
        val c = JamWire.parseContinuation(f)!!
        assertEquals(12L, c.newEpoch)
        assertEquals("zz99zz99", c.successorNonce)
        assertEquals(88_000L, c.pivotPosMs)
        assertEquals(2_000_000_000L, c.pivotSyncedNanos)
        assertTrue(c.playing)
        assertTrue(!c.trackMatches)
    }

    @Test
    fun `sync req and ack round-trip`() {
        val req = JamWire.parseSyncReq(roundTrip(JamWire.encodeSyncReq(sender, 33, 100L))!!)!!
        assertEquals(33, req.probeId)
        assertEquals(100L, req.t0Nanos)

        val ack = JamWire.parseSyncAck(
            roundTrip(JamWire.encodeSyncAck(sender, 33, 100L, 150L, 152L, "peer0001"))!!
        )!!
        assertEquals(33, ack.probeId)
        assertEquals(100L, ack.t0Nanos)
        assertEquals(150L, ack.t1Nanos)
        assertEquals(152L, ack.t2Nanos)
        assertEquals("peer0001", ack.targetNonce)
    }

    @Test
    fun `next_is with and without track json`() {
        val json = """{"title":"Neon Skyline","artist":"Vektor"}"""
        val fNotNull = roundTrip(JamWire.encodeNextIs(sender, 2L, isNull = false, trackJson = json))!!
        assertEquals(false, JamWire.parseNextIs(fNotNull))
        assertEquals(json, fNotNull.trackJson)

        val fNull = roundTrip(JamWire.encodeNextIs(sender, 2L, isNull = true, trackJson = null))!!
        assertEquals(true, JamWire.parseNextIs(fNull))
        assertNull(fNull.trackJson)
    }

    @Test
    fun `track_change carries position, play flag and json tail`() {
        val f = roundTrip(JamWire.encodeTrackChange(sender, 9L, 44_100L, true, "{}"))!!
        val tc = JamWire.parseTrackChange(f)!!
        assertEquals(44_100L, tc.positionMs)
        assertTrue(tc.playing)
        assertEquals("{}", tc.trackJson)
    }

    @Test
    fun `play pause seek policy leave session_end`() {
        assertEquals(JamWire.Msg.PLAY, roundTrip(JamWire.encodePlayPause(sender, 1L, true))!!.msgType)
        assertEquals(JamWire.Msg.PAUSE, roundTrip(JamWire.encodePlayPause(sender, 1L, false))!!.msgType)
        assertEquals(7L, JamWire.parseSeek(roundTrip(JamWire.encodeSeek(sender, 8L, 7L))!!))
        assertEquals(1, JamWire.parsePolicy(roundTrip(JamWire.encodePolicy(sender, 1L, 1))!!))
        assertEquals(JamWire.Msg.LEAVE, roundTrip(JamWire.encodeLeave(sender))!!.msgType)
        assertEquals(JamWire.Msg.SESSION_END, roundTrip(JamWire.encodeSessionEnd(sender))!!.msgType)
    }

    // ── Track meta / legacy snapshot ──────────────────────────────────────
    // (The Merkle sync registry — ids 15–20 — was retired with the Kotlin
    // CRDT amputation, UFFI-04: convergence is engine-authoritative.)

    @Test
    fun `track meta and queue snapshot round-trip`() {
        val meta = JamWire.parseTrackMeta(roundTrip(JamWire.encodeTrackMeta(sender, 99L, "{}"))!!)
        assertNotNull(meta)
        assertEquals(99L, meta!!.cadId)
        assertEquals("{}", meta.trackJson)

        val snapshotJson = """[{"title":"A"},{"title":"B"}]"""
        val snap = JamWire.parseQueueSnapshot(
            roundTrip(JamWire.encodeQueueSnapshot(sender, snapshotJson))!!
        )
        assertEquals(snapshotJson, snap)
    }

    // ── Hostile inputs ─────────────────────────────────────────────────────

    @Test
    fun `tail length beyond frame rejected`() {
        val f = JamWire.encodeTrackChange(sender, 1L, 2L, true, "{}")
        // Claim a 1 MB tail that isn't there.
        f[HEADER_TAIL_LEN + 0] = 0
        f[HEADER_TAIL_LEN + 1] = 0
        f[HEADER_TAIL_LEN + 2] = 16 // 0x00100000
        f[HEADER_TAIL_LEN + 3] = 0
        assertNull(roundTrip(f))
    }

    @Test
    fun `unknown message type decodes but parses to null everywhere`() {
        // Manually craft a frame with msgType 200.
        val base = JamWire.encodeSeek(sender, 1L, 2L)
        base[6] = 200.toByte()
        val f = roundTrip(base)
        assertNotNull(f) // header is structurally valid — dispatcher will no-op
        assertNull(f!!.asTick)
        assertNull(JamWire.parseSeek(f))
    }

    // ── Gap #37: democratic queue votes ────────────────────────────────────

    @Test
    fun `vote round-trips target, voter and up flag`() {
        val f = roundTrip(JamWire.encodeVote(sender, 9L, 4242L, true))
        assertNotNull(f)
        assertEquals(JamWire.Msg.VOTE, f!!.msgType)
        assertEquals(9L, f.epoch)
        val body = JamWire.parseVote(f)
        assertNotNull(body)
        body!!
        assertEquals(4242L, body.targetAddOpId)
        assertEquals(sender, body.voterNonce)
        assertTrue(body.up)
    }

    @Test
    fun `vote retract round-trips with up false`() {
        val f = roundTrip(JamWire.encodeVote(sender, 0L, 1L, false))
        val body = JamWire.parseVote(f!!)
        assertNotNull(body)
        assertFalse(body!!.up)
        assertEquals(1L, body.targetAddOpId)
    }

    @Test
    fun `vote voter nonce is space-padded and trimmed back`() {
        val f = roundTrip(JamWire.encodeVote("ab", 1L, 5L, true))
        assertEquals("ab", JamWire.parseVote(f!!)!!.voterNonce)
    }

    @Test
    fun `vote parser rejects non-vote frames`() {
        // A structurally valid SEEK frame must never parse as a vote.
        val f = roundTrip(JamWire.encodeSeek(sender, 1L, 2L))
        assertNull(JamWire.parseVote(f!!))
    }

    companion object {
        private const val HEADER_TAIL_LEN = 24 // u32 jsonLen position in the tail
    }
}
