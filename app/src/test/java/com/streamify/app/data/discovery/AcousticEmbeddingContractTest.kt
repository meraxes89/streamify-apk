package com.streamify.app.data.discovery

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * AcousticEmbeddingContractTest — WS-D D1's Kotlin side of the
 * three-language layout pin (Rust: streamify-core; Swift:
 * AcousticEmbeddingContractTests). The 516-byte golden fixture below is the
 * byte-exact reference image produced by the Rust contract; this suite
 * recomputes the documented lanes from the schema constants and compares
 * layout-for-layout. A lane renumbering fails all three languages at once.
 */
class AcousticEmbeddingContractTest {

    private object Layout {
        const val DIM = 128
        const val WIRE_BYTES = 4 + DIM * 4
        const val BPM = 0
        const val CAMELOT_NUMBER = 1
        const val CAMELOT_LETTER = 2
        const val INTEGRATED_LUFS = 3
        const val CREST_FACTOR_DB = 4
        const val SPECTRAL_BAND_FIRST = 5
        const val SPECTRAL_BAND_COUNT = 10
        const val SPECTRAL_CENTROID_KHZ = 15
        const val DANCEABILITY = 23
        const val ENERGY = 24
        const val VALENCE = 25
        const val ACOUSTICNESS = 26
        const val WAVEFORM_FIRST = 27
        const val WAVEFORM_BUCKET_COUNT = 64
        const val RESERVED_FIRST = 91
        const val LAYOUT_VERSION = 127
    }

    private val fixtureHex = "010000000000f8420000004100000000333363c100001841cdcccc3dcdcc4c3e9a99993ecdcccc3e0000003f9a99193f3333333fcdcc4c3f6766663f0000803f9a991940ec51383eec51b83dae47613eae47e13e00006040ec51383f295c4f3fd7a3303fe17a143f52b81e3fd7a3f03e00000000e1e0e03ce1e0603da9a8a83de1e0e03d8d8c0c3ea9a8283ec5c4443ee1e0603efdfc7c3e8d8c8c3e9b9a9a3ea9a8a83eb7b6b63ec5c4c43ed3d2d23ee1e0e03eefeeee3efdfcfc3e8685053f8d8c0c3f9493133f9b9a1a3fa2a1213fa9a8283fb0af2f3fb7b6363fbebd3d3fc5c4443fcccb4b3fd3d2523fdad9593fe1e0603fe8e7673fefee6e3ff6f5753f8180803b8180003df1f0703db1b0b03de9e8e83d9190103eadac2c3ec9c8483ee5e4643e8180803e8f8e8e3e9d9c9c3eabaaaa3eb9b8b83ec7c6c63ed5d4d43ee3e2e23ef1f0f03efffefe3e8786063f8e8d0d3f9594143f9c9b1b3fa3a2223faaa9293fb1b0303fb8b7373fbfbe3e3f0000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000803f"

    private fun decodeHex(hex: String): ByteArray {
        val out = ByteArray(hex.length / 2)
        for (i in out.indices) {
            out[i] = ((Character.digit(hex[i * 2], 16) shl 4) or Character.digit(hex[i * 2 + 1], 16)).toByte()
        }
        return out
    }

    private fun laneF32(bytes: ByteArray, lane: Int): Float {
        val base = 4 + lane * 4
        var bits = 0
        for (i in 0 until 4) {
            bits = bits or ((bytes[base + i].toInt() and 0xFF) shl (8 * i))
        }
        return Float.fromBits(bits)
    }

    @Test
    fun `wire geometry is exactly 516 bytes with a v1 header`() {
        val bytes = decodeHex(fixtureHex)
        assertEquals(Layout.WIRE_BYTES, bytes.size)
        assertEquals(516, bytes.size)
        assertEquals(1, bytes[0].toInt())
        assertEquals(0, bytes[1].toInt())
        assertEquals(0, bytes[2].toInt())
        assertEquals(0, bytes[3].toInt())
    }

    @Test
    fun `documented lanes parse layout for layout`() {
        val bytes = decodeHex(fixtureHex)
        assertEquals(124.0f, laneF32(bytes, Layout.BPM))
        assertEquals(8.0f, laneF32(bytes, Layout.CAMELOT_NUMBER))
        assertEquals(0.0f, laneF32(bytes, Layout.CAMELOT_LETTER))
        assertEquals(-14.2f, laneF32(bytes, Layout.INTEGRATED_LUFS), 1e-4f)
        assertEquals(9.5f, laneF32(bytes, Layout.CREST_FACTOR_DB), 1e-4f)
        assertEquals(2.4f, laneF32(bytes, Layout.SPECTRAL_CENTROID_KHZ), 1e-4f)
        assertEquals(0.69f, laneF32(bytes, Layout.DANCEABILITY), 1e-4f)
        assertEquals(0.58f, laneF32(bytes, Layout.ENERGY), 1e-4f)
        assertEquals(0.62f, laneF32(bytes, Layout.VALENCE), 1e-4f)
        assertEquals(0.47f, laneF32(bytes, Layout.ACOUSTICNESS), 1e-4f)
        assertEquals(1.0f, laneF32(bytes, Layout.LAYOUT_VERSION))
    }

    @Test
    fun `spectral and waveform blocks cover their ranges`() {
        val bytes = decodeHex(fixtureHex)
        for (i in 0 until Layout.SPECTRAL_BAND_COUNT) {
            assertEquals(0.1f * (i + 1), laneF32(bytes, Layout.SPECTRAL_BAND_FIRST + i), 1e-4f)
        }
        for (i in 0 until Layout.WAVEFORM_BUCKET_COUNT) {
            assertEquals(((i * 7) % 251) / 255.0f, laneF32(bytes, Layout.WAVEFORM_FIRST + i), 1e-4f)
        }
        for (lane in Layout.RESERVED_FIRST until Layout.LAYOUT_VERSION) {
            assertEquals(0.0f, laneF32(bytes, lane), 1e-6f)
        }
    }

    @Test
    fun `schema covers all 128 lanes`() {
        val covered = 5 + Layout.SPECTRAL_BAND_COUNT + 12 +
            Layout.WAVEFORM_BUCKET_COUNT + (Layout.LAYOUT_VERSION - Layout.RESERVED_FIRST)
        assertEquals(Layout.DIM, covered)
    }
}
