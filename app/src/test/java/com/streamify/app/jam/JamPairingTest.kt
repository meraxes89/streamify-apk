package com.streamify.app.jam

import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.google.zxing.qrcode.QRCodeWriter
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Offline pairing payload codec + QR content round-trip (zxing core, the
 * same writer the Compose widget uses — proves the QR a friend scans
 * reconstructs the exact pairing intent).
 */
class JamPairingTest {

    /** Lowercase hex — same display format the engine's pairing key uses. */
    private fun toHex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    @Test
    fun `full payload round-trips`() {
        val session = JamEngine.JamSession(
            id = "11111111-2222-3333-4444-555555555555",
            sessionCode = "AB3CD9",
            pairingKeyHex = toHex(ByteArray(32) { it.toByte() }),
            hostNonce = "a1b2c3d4",
            createdAtMs = 1L
        )
        val uri = JamPairing.encodePayload(session)
        assertTrue(uri.startsWith("streamify://jam/AB3CD9?v=4&s="))
        val parsed = JamPairing.parsePayload(uri)
        assertNotNull(parsed)
        parsed!!
        assertEquals("AB3CD9", parsed.sessionCode)
        assertEquals(session.id, parsed.sessionId)
        assertArrayEquals(ByteArray(32) { it.toByte() }, parsed.pairingKey)
    }

    @Test
    fun `bare pin parses with derived session id`() {
        val parsed = JamPairing.parsePayload("  ab3cd9 ")!!
        assertEquals("AB3CD9", parsed.sessionCode)
        assertTrue(parsed.pairingKey.all { it == 0.toByte() }) // key-less LAN join
    }

    @Test
    fun `deep-link form strips to pin when query is absent`() {
        val parsed = JamPairing.parsePayload("streamify://jam/XY7ZZ8")!!
        assertEquals("XY7ZZ8", parsed.sessionCode)
    }

    @Test
    @Suppress("ktlint:standard:max-line-length")
    fun `pin with key value fragments parses`() {
        val keyHex = toHex(ByteArray(32) { (it * 3).toByte() })
        val parsed = JamPairing.parsePayload("ab3cd9 s=deadbeef k=$keyHex")!!
        assertEquals("AB3CD9", parsed.sessionCode)
        assertEquals("deadbeef", parsed.sessionId)
        assertArrayEquals(ByteArray(32) { (it * 3).toByte() }, parsed.pairingKey)
    }

    @Test
    fun `garbage and ambiguous-glyph pins rejected`() {
        assertNull(JamPairing.parsePayload(""))
        assertNull(JamPairing.parsePayload(null))
        assertNull(JamPairing.parsePayload("hello world"))
        assertNull(JamPairing.parsePayload("AB3CDI")) // I is excluded from the alphabet
        assertNull(JamPairing.parsePayload("AB3CD0")) // 0 is excluded from the alphabet
        assertNull(JamPairing.parsePayload("streamify://jam/SHORT?s=x&k=001122"))
    }

    @Test
    fun `session codes avoid ambiguous glyphs`() {
        repeat(50) {
            val code = JamPairing.generateSessionCode()
            assertEquals(6, code.length)
            assertTrue(code.none { it in "ILO01" })
        }
    }

    @Test
    fun `pairing keys are 32 random bytes`() {
        val a = JamPairing.generatePairingKey()
        val b = JamPairing.generatePairingKey()
        assertEquals(32, a.size)
        assertTrue(!a.contentEquals(b))
    }

    /**
     * QR round-trip through the REAL zxing pipeline: the exact writer the
     * Compose widget uses, then a full reader decode (luminance → binarizer
     * → decoder). If the widget renders it, any camera app can read it.
     */
    @Test
    fun `qr encodes and decodes the pairing payload`() {
        val session = JamEngine.JamSession(
            id = "aaaabbbb-cccc-dddd-eeee-ffff00001111",
            sessionCode = "QR7TST",
            pairingKeyHex = toHex(JamPairing.generatePairingKey()),
            hostNonce = "qq7tst01",
            createdAtMs = 0L
        )
        val payload = JamPairing.encodePayload(session)

        val matrix = QRCodeWriter().encode(
            payload,
            com.google.zxing.BarcodeFormat.QR_CODE,
            0, 0,
            mapOf(
                com.google.zxing.EncodeHintType.MARGIN to 0,
                com.google.zxing.EncodeHintType.ERROR_CORRECTION to
                    com.google.zxing.qrcode.decoder.ErrorCorrectionLevel.M
            )
        )
        // Render the BitMatrix into ARGB pixels with a 1-module quiet zone.
        val n = matrix.width
        val scale = 8
        val dim = (n + 2) * scale
        val pixels = IntArray(dim * dim) { androidColor(255, 255, 255) }
        for (y in 0 until n) {
            for (x in 0 until n) {
                if (matrix.get(x, y)) {
                    for (dy in 0 until scale) {
                        for (dx in 0 until scale) {
                            val px = (x + 1) * scale + dx
                            val py = (y + 1) * scale + dy
                            pixels[py * dim + px] = androidColor(0, 0, 0)
                        }
                    }
                }
            }
        }
        val source = RGBLuminanceSource(dim, dim, pixels)
        val bitmap = BinaryBitmap(HybridBinarizer(source))
        val result = QRCodeReader().decode(
            bitmap,
            mapOf(DecodeHintType.TRY_HARDER to true)
        )
        assertEquals(payload, result.text)
        // And the reconstructed text parses back into the same join intent.
        val parsed = JamPairing.parsePayload(result.text)!!
        assertEquals(session.sessionCode, parsed.sessionCode)
        assertEquals(session.id, parsed.sessionId)
        assertArrayEquals(
            JamPairing.parsePayload(payload)!!.pairingKey,
            parsed.pairingKey
        )
    }

    private fun androidColor(r: Int, g: Int, b: Int): Int =
        (0xFF shl 24) or (r shl 16) or (g shl 8) or b
}
