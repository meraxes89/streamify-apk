package com.streamify.app.jam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PHASE 1 (Gap #12) — LAN UDP Beacon 0x09 discovery-packet codec.
 *
 * The Rust mesh announces live rooms on the local network with a BEACON
 * control frame; the app's join fabric listens for it and raises the
 * "A Streamify Jam is live" prompt on Home. These tests pin the frozen
 * Rust wire contract from the Kotlin side: header layout, FNV-1a/32
 * integrity, and the total-parser discipline (hostile datagrams die at
 * the codec boundary, never inside the listener).
 */
class LanBeaconPacketTest {

    private val sessionId = ByteArray(16) { (it * 7 + 3).toByte() }

    private fun beacon(
        sender: Long = 0x21B2C3D4E5F60718L,
        caps: Int = LanBeaconPacket.CAP_LAN or LanBeaconPacket.CAP_WEBRTC,
        port: Int = LanBeaconPacket.DEFAULT_MESH_PORT
    ): ByteArray = LanBeaconPacket.encodeBeacon(sender, sessionId, caps, port)!!

    // ── Round trip ─────────────────────────────────────────────────────────

    @Test
    fun `beacon round trips sender, room identity, caps and port`() {
        val d = LanBeaconPacket.decode(beacon())
        assertNotNull(d)
        d!!
        assertEquals(16, d.senderIdHex.length)
        assertEquals(32, d.sessionIdHex.length)
        assertTrue(d.hasLan)
        assertTrue(d.hasWebRtc)
        assertEquals(LanBeaconPacket.DEFAULT_MESH_PORT, d.meshPort)
    }

    @Test
    fun `caps bits are reported independently`() {
        val lanOnly = LanBeaconPacket.decode(beacon(caps = LanBeaconPacket.CAP_LAN))!!
        assertTrue(lanOnly.hasLan)
        assertTrue(!lanOnly.hasWebRtc)

        val none = LanBeaconPacket.decode(beacon(caps = 0))!!
        assertTrue(!none.hasLan)
        assertTrue(!none.hasWebRtc)
    }

    @Test
    fun `non-default mesh ports survive`() {
        val d = LanBeaconPacket.decode(beacon(port = 7778))!!
        assertEquals(7778, d.meshPort)
    }

    @Test
    fun `fnv1a32 matches the rust deviation d2 contract`() {
        // Known vector: FNV-1a/32 of 42 zero bytes (offset basis + 42 zero
        // folds). Pinning this keeps the Kotlin and Rust checksummers from
        // ever silently diverging — a divergence would blackhole every
        // beacon as "corrupt".
        val zeros = ByteArray(64)
        val expected = run {
            var h = 0x811C9DC5.toInt()
            repeat(42) {
                h = h xor 0
                h *= 0x01000193
            }
            h
        }
        assertEquals(expected, LanBeaconPacket.fnv1a32(zeros))
    }

    // ── Total-parser hardening ─────────────────────────────────────────────

    @Test
    fun `null and short datagrams are rejected`() {
        assertNull(LanBeaconPacket.decode(null))
        assertNull(LanBeaconPacket.decode(ByteArray(0)))
        assertNull(
            LanBeaconPacket.decode(
                ByteArray(LanBeaconPacket.HEADER_LEN + LanBeaconPacket.BEACON_PAYLOAD_LEN - 1)
            )
        )
    }

    @Test
    fun `wrong magic, version or message type is rejected`() {
        val badMagic = beacon().copyOf().also { it[0] = 0x00 }
        assertNull(LanBeaconPacket.decode(badMagic))

        val badVersion = beacon().copyOf().also { it[2] = 0x02 }
        assertNull(LanBeaconPacket.decode(badVersion))

        val badMsg = beacon().copyOf().also { it[3] = 0x08 } // not BEACON 0x09
        assertNull(LanBeaconPacket.decode(badMsg))
    }

    @Test
    fun `corrupted checksum is rejected`() {
        val raw = beacon()
        // Flip one bit inside the checksummed header span.
        val corrupt = raw.copyOf().also { it[10] = (it[10].toInt() xor 0x40).toByte() }
        assertNull(LanBeaconPacket.decode(corrupt))
    }

    @Test
    fun `wrong payload length is rejected`() {
        val raw = beacon()
        // Overwrite payload_len (offset 40, u16 LE) with a lie, then repair
        // the checksum so the frame is provably killed by the length check
        // itself — not incidentally by checksum mismatch.
        val lie = raw.copyOf()
        lie[40] = 0x20
        lie[41] = 0x00
        assertNull(LanBeaconPacket.decode(ByteBufferFixer.fix(lie)))
    }

    @Test
    fun `hostile zero ports are rejected`() {
        // A zero port is smuggled past encode by hand-building the frame.
        val raw = beacon(port = 7777)
        val hostile = raw.copyOf()
        // Zero the port in the payload (last 2 bytes) and fix the checksum.
        val portOffset = hostile.size - 2
        hostile[portOffset] = 0
        hostile[portOffset + 1] = 0
        val fixed = ByteBufferFixer.fix(hostile)
        assertNull(LanBeaconPacket.decode(fixed))
    }

    // ── Encoder validation ─────────────────────────────────────────────────

    @Test
    fun `encoder refuses session ids that are not 16 bytes`() {
        assertNull(LanBeaconPacket.encodeBeacon(1L, ByteArray(15), 0, 7777))
        assertNull(LanBeaconPacket.encodeBeacon(1L, ByteArray(17), 0, 7777))
    }

    @Test
    fun `header constants pin the frozen rust grammar`() {
        assertEquals(0x5354, LanBeaconPacket.MAGIC)
        assertEquals(3, LanBeaconPacket.PROTO_VERSION)
        assertEquals(0x09, LanBeaconPacket.MSG_BEACON)
        assertEquals(46, LanBeaconPacket.HEADER_LEN)
        assertEquals(42, LanBeaconPacket.CHECKSUM_COVERED_LEN)
        assertEquals(12, LanBeaconPacket.BEACON_PAYLOAD_LEN)
    }
}

/** Recomputes and writes the FNV-1a checksum at offset 42 (test helper). */
private object ByteBufferFixer {
    fun fix(raw: ByteArray): ByteArray {
        val out = raw.copyOf()
        val checksum = LanBeaconPacket.fnv1a32(out)
        out[42] = (checksum and 0xFF).toByte()
        out[43] = ((checksum shr 8) and 0xFF).toByte()
        out[44] = ((checksum shr 16) and 0xFF).toByte()
        out[45] = ((checksum shr 24) and 0xFF).toByte()
        return out
    }
}
