package com.streamify.app.jam

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * PHASE 1 (Gap #12) — BLE join-fabric packet codec.
 *
 * The advert payload is the entire zero-PIN join contract: a 6-char room
 * code, the host's display name, and three live room flags (party mode /
 * has space / queue non-empty). These tests pin every byte of the wire
 * format and prove that hostile or corrupt scan records die at the codec
 * boundary — never inside the scanner, never inside the engine.
 */
class BleJoinPayloadTest {

    private val code = "AB3X9K"

    // ── Wire format ────────────────────────────────────────────────────────

    @Test
    fun `header layout is magic, version, flags, six-char code, host name`() {
        val bytes = BleJoinPayload.encode(code, "Zephyr", partyMode = true, hasSpace = false, queueNonEmpty = true)
        assertNotNull(bytes)
        bytes!!

        assertEquals(BleJoinPayload.MAGIC_0, bytes[BleJoinPayload.OFFSET_MAGIC])
        assertEquals(BleJoinPayload.MAGIC_1, bytes[BleJoinPayload.OFFSET_MAGIC + 1])
        assertEquals(BleJoinPayload.VERSION, bytes[BleJoinPayload.OFFSET_VERSION])

        val flags = bytes[BleJoinPayload.OFFSET_FLAGS].toInt() and 0xFF
        assertEquals(BleJoinPayload.FLAG_TOPOLOGY_SINGLE_RENDER, flags and BleJoinPayload.FLAG_TOPOLOGY_SINGLE_RENDER)
        assertEquals(0, flags and BleJoinPayload.FLAG_ROOM_HAS_SPACE)
        assertEquals(BleJoinPayload.FLAG_QUEUE_NON_EMPTY, flags and BleJoinPayload.FLAG_QUEUE_NON_EMPTY)

        val wireCode = String(bytes, BleJoinPayload.OFFSET_CODE, BleJoinPayload.CODE_BYTES, Charsets.US_ASCII)
        assertEquals(code, wireCode)

        val name = String(bytes, BleJoinPayload.OFFSET_HOST_NAME, bytes.size - BleJoinPayload.OFFSET_HOST_NAME, Charsets.UTF_8)
        assertEquals("Zephyr", name)
    }

    @Test
    fun `encode decode round trip preserves every field`() {
        for (party in listOf(false, true)) {
            for (space in listOf(false, true)) {
                for (queue in listOf(false, true)) {
                    val dec = BleJoinPayload.decode(
                        BleJoinPayload.encode(code, "Ada Lovelace", party, space, queue)
                    )
                    assertNotNull(dec)
                    dec!!
                    assertEquals(code, dec.sessionCode)
                    assertEquals("Ada Lovelace", dec.hostName)
                    assertEquals(party, dec.partyMode)
                    assertEquals(space, dec.hasSpace)
                    assertEquals(queue, dec.queueNonEmpty)
                }
            }
        }
    }

    @Test
    fun `session code is trimmed and uppercased on the wire`() {
        val dec = BleJoinPayload.decode(BleJoinPayload.encode("  ab3x9k ", "Host"))
        assertNotNull(dec)
        assertEquals(code, dec!!.sessionCode)
    }

    @Test
    fun `empty host name is representable`() {
        val dec = BleJoinPayload.decode(BleJoinPayload.encode(code, ""))
        assertNotNull(dec)
        assertEquals("", dec!!.hostName)
    }

    @Test
    fun `utf-8 host names survive the round trip`() {
        val dec = BleJoinPayload.decode(BleJoinPayload.encode(code, "张伟 🎧"))
        assertNotNull(dec)
        assertEquals("张伟 🎧", dec!!.hostName)
    }

    // ── Encode-side validation ─────────────────────────────────────────────

    @Test
    fun `wrong-length session codes are refused`() {
        assertNull(BleJoinPayload.encode("ABC", "Host"))
        assertNull(BleJoinPayload.encode("ABCDEFG", "Host"))
        assertNull(BleJoinPayload.encode("", "Host"))
    }

    @Test
    fun `non-printable-ascii session codes are refused`() {
        assertNull(BleJoinPayload.encode("AB3\t9K", "Host"))
        assertNull(BleJoinPayload.encode("AB3\n9K", "Host"))
    }

    @Test
    fun `oversize host names are refused`() {
        val name = "x".repeat(BleJoinPayload.MAX_HOST_NAME_BYTES + 1)
        assertNull(BleJoinPayload.encode(code, name))
        // Exactly at the cap is fine.
        assertNotNull(BleJoinPayload.encode(code, "y".repeat(BleJoinPayload.MAX_HOST_NAME_BYTES)))
    }

    // ── Decode-side hardening (hostile scan records die here) ──────────────

    @Test
    fun `null and short buffers are rejected`() {
        assertNull(BleJoinPayload.decode(null))
        assertNull(BleJoinPayload.decode(ByteArray(3)))
        assertNull(BleJoinPayload.decode(ByteArray(BleJoinPayload.OFFSET_HOST_NAME - 1)))
    }

    @Test
    fun `bad magic and unknown version are rejected`() {
        val good = BleJoinPayload.encode(code, "Host")!!
        val badMagic = good.copyOf().also { it[0] = 0x00 }
        assertNull(BleJoinPayload.decode(badMagic))
        val badVersion = good.copyOf().also { it[BleJoinPayload.OFFSET_VERSION] = 2 }
        assertNull(BleJoinPayload.decode(badVersion))
        // Audit #22 item 3 regression lock: byte[1] must be 'F' too —
        // decode used to skip it, so `0x53 ?? 0x01…` foreign payloads
        // passed the magic gate.
        val badMagic1 = good.copyOf().also { it[1] = 0x00 }
        assertNull(BleJoinPayload.decode(badMagic1))
        // Test vector: 'S','X',version 1 — valid byte 0, invalid byte 1.
        val sneaky = good.copyOf().also { it[1] = 'X'.code.toByte() }
        assertNull(BleJoinPayload.decode(sneaky))
    }

    @Test
    fun `reserved flag bits must be zero`() {
        val good = BleJoinPayload.encode(code, "Host")!!
        val hostile = good.copyOf().also { it[BleJoinPayload.OFFSET_FLAGS] = 0x08 } // bit 3 reserved
        assertNull(BleJoinPayload.decode(hostile))
    }

    @Test
    fun `over-long buffers beyond the name cap are rejected`() {
        val good = BleJoinPayload.encode(code, "Host")!!
        val padded = good + ByteArray(BleJoinPayload.MAX_HOST_NAME_BYTES) // name would exceed cap
        assertNull(BleJoinPayload.decode(padded))
    }

    @Test
    fun `corrupt utf-8 in the host name never throws`() {
        val good = BleJoinPayload.encode(code, "Host")!!
        val hostile = good.copyOf()
        hostile[hostile.size - 1] = 0x80.toByte() // lone continuation byte
        // May decode to a replacement char, but MUST not throw.
        BleJoinPayload.decode(hostile)
    }

    // ── Manufacturer-specific scan records (AD structure walk) ─────────────

    @Test
    fun `payload round trips through a manufacturer-specific scan record`() {
        val blob = BleJoinPayload.encodeManufacturerData(code, "Zephyr", partyMode = true)!!
        val record = buildScanRecord(blob)

        val dec = BleJoinPayload.fromScanRecord(record, BleJoinPayload.MANUFACTURER_ID)
        assertNotNull(dec)
        dec!!
        assertEquals(code, dec.sessionCode)
        assertEquals("Zephyr", dec.hostName)
        assertTrue(dec.partyMode)
    }

    @Test
    fun `scan record without our manufacturer id yields nothing`() {
        val blob = BleJoinPayload.encodeManufacturerData(code, "Host")!!
        val record = buildScanRecord(blob)
        assertNull(BleJoinPayload.fromScanRecord(record, 0x1234))
    }

    @Test
    fun `empty and foreign scan records yield nothing`() {
        assertNull(BleJoinPayload.fromScanRecord(null, BleJoinPayload.MANUFACTURER_ID))
        assertNull(BleJoinPayload.fromScanRecord(ByteArray(0), BleJoinPayload.MANUFACTURER_ID))

        // A well-formed AD structure carrying a non-Streamify payload.
        val foreign = byteArrayOf(
            0x05, 0x09.toByte(), 'S'.code.toByte(), 'P'.code.toByte(), 'T'.code.toByte(), 'F'.code.toByte()
        )
        assertNull(BleJoinPayload.fromScanRecord(foreign, BleJoinPayload.MANUFACTURER_ID))
    }

    @Test
    fun `truncated AD structures are walked safely`() {
        val blob = BleJoinPayload.encodeManufacturerData(code, "Host")!!
        val record = buildScanRecord(blob)
        for (cut in 1 until record.size) {
            // Never throws, never returns a half-payload.
            BleJoinPayload.fromScanRecord(record.copyOf(cut), BleJoinPayload.MANUFACTURER_ID)
        }
        assertNull(BleJoinPayload.fromScanRecord(record.copyOf(2), BleJoinPayload.MANUFACTURER_ID))
    }

    @Test
    fun `manufacturer blob is little-endian company id followed by payload`() {
        val blob = BleJoinPayload.encodeManufacturerData(code, "Host")!!
        val id = ByteBuffer.wrap(blob, 0, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xFFFF
        assertEquals(BleJoinPayload.MANUFACTURER_ID, id)
        val payload = blob.copyOfRange(2, blob.size)
        assertArrayEquals(BleJoinPayload.encode(code, "Host"), payload)
    }

    // ── Proximity contract ─────────────────────────────────────────────────

    @Test
    fun `proximity rssi threshold is in the one-metre phone-class band`() {
        // ~-60 dBm ≈ 1 m free space for phone radios; pin the constant so a
        // careless edit cannot silently break zero-PIN proximity joins.
        assertTrue(BleJoinPayload.PROXIMITY_RSSI_DBM in -70..-45)
    }

    @Test
    fun `prompt subtitle reflects live room state`() {
        val full = BleJoinPayload.decode(BleJoinPayload.encode(code, "Z", hasSpace = false))!!
        assertTrue(full.promptSubtitle.contains("full", ignoreCase = true))

        val party = BleJoinPayload.decode(BleJoinPayload.encode(code, "Z", partyMode = true))!!
        assertTrue(party.promptSubtitle.contains("speaker", ignoreCase = true))

        val plain = BleJoinPayload.decode(BleJoinPayload.encode(code, "Z"))!!
        assertTrue(plain.promptSubtitle.isNotBlank())
    }

    /** Builds [len][type=0xFF][companyId LE][blob] inside a larger record. */
    private fun buildScanRecord(manufacturerBlob: ByteArray): ByteArray {
        val ad = ByteArray(2 + manufacturerBlob.size)
        ad[0] = (1 + manufacturerBlob.size).toByte() // length covers type + payload
        ad[1] = 0xFF.toByte()
        System.arraycopy(manufacturerBlob, 0, ad, 2, manufacturerBlob.size)
        // Trailing padding (other AD structures / zero terminator) is normal.
        return ad + ByteArray(4)
    }
}
