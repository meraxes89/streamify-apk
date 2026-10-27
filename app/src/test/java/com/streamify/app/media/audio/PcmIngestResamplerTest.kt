package com.streamify.app.media.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * Phase 4a (MP-HAL05) — the producer-side ingestion converter: decoded PCM
 * → canonical 48 kHz f32 stereo.
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * EXACTNESS TIER (must be bit-perfect):
 *  • 48 kHz stereo f32 → passthrough relay,
 *  • mono → stereo duplication,
 *  • PCM16 → f32 (/32768) in BOTH byte orders,
 *  • f32 extraction in BOTH byte orders,
 *  • chunked streaming == one-shot streaming (the pending-frame tail
 *    rule — no kernel-clamp glitch at chunk boundaries).
 *
 * QUALITY TIER (dogfood bridge, honestly labeled):
 *  • 44.1 kHz → 48 kHz cubic Hermite ratio (±0.5% length, no NaN,
 *    bounded overshoot on program material),
 *  • speed folding (2× consumes at half the media rate).
 */
class PcmIngestResamplerTest {

    private fun collect(converter: PcmIngestResampler, signal: FloatArray, frames: Int): FloatArray {
        val out = ArrayList<Float>(frames * 4)
        converter.process(signal, 0, frames) { arr, off, len ->
            for (i in 0 until len) out += arr[off + i]
        }
        return out.toFloatArray()
    }

    // ── exactness tier ─────────────────────────────────────────────────────

    @Test
    fun `48k stereo float passthrough is bit-exact`() {
        val frames = 1000
        val signal = FloatArray(frames * 2) { (it % 97).toFloat() / 97f - 0.5f }
        val converter = PcmIngestResampler(48_000, 2, inputIsPcm16 = false)
        assertTrue(converter.isPassthrough)
        val out = collect(converter, signal, frames)
        assertEquals(signal.size, out.size)
        assertTrue("output must alias the input bit-for-bit", signal.contentEquals(out))
        assertEquals(frames.toLong(), converter.outputFramesEmitted)
    }

    @Test
    fun `mono 48k duplicates to stereo exactly`() {
        val frames = 500
        val mono = FloatArray(frames) { sin(2.0 * PI * 440.0 * it / 48_000.0).toFloat() }
        val converter = PcmIngestResampler(48_000, 1, inputIsPcm16 = false)
        assertTrue(converter.isPassthrough)
        val out = collect(converter, mono, frames)
        assertEquals(frames * 2, out.size)
        for (f in 0 until frames) {
            assertEquals(mono[f], out[f * 2], 0f)
            assertEquals(mono[f], out[f * 2 + 1], 0f)
        }
    }

    @Test
    fun `pcm16 little-endian decodes exactly in both channel counts`() {
        val frames = 300
        val samples = ShortArray(frames * 2) { ((it % 255) - 127).toShort() }
        val buf = ByteBuffer.allocate(frames * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (s in samples) buf.putShort(s)
        buf.flip()

        val stereo = PcmIngestResampler(48_000, 2, inputIsPcm16 = true)
        val outStereo = ArrayList<Float>(frames * 2)
        val ingested = stereo.ingestByteBuffer(buf, buf.position(), buf.limit()) { arr, off, len ->
            for (i in 0 until len) outStereo += arr[off + i]
        }
        assertEquals(frames, ingested)
        assertEquals(frames * 2, outStereo.size)
        for (i in samples.indices) {
            assertEquals(samples[i] / 32768f, outStereo[i], 0f)
        }

        // Mono variant: same wire, one channel.
        val monoBuf = ByteBuffer.allocate(frames * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (f in 0 until frames) monoBuf.putShort(samples[f])
        monoBuf.flip()
        val mono = PcmIngestResampler(48_000, 1, inputIsPcm16 = true)
        val outMono = ArrayList<Float>(frames * 2)
        mono.ingestByteBuffer(monoBuf, 0, monoBuf.limit()) { arr, off, len ->
            for (i in 0 until len) outMono += arr[off + i]
        }
        assertEquals(frames * 2, outMono.size) // duplicated to stereo
        for (f in 0 until frames) {
            assertEquals(samples[f] / 32768f, outMono[f * 2], 0f)
            assertEquals(samples[f] / 32768f, outMono[f * 2 + 1], 0f)
        }
    }

    @Test
    fun `pcm16 and f32 big-endian wires decode via manual assembly`() {
        val frames = 64
        // PCM16 BE
        val be16 = ByteBuffer.allocate(frames * 2).order(ByteOrder.BIG_ENDIAN)
        for (f in 0 until frames) be16.putShort(((f * 31) % 4096 - 2048).toShort())
        be16.flip()
        val c16 = PcmIngestResampler(48_000, 1, inputIsPcm16 = true)
        val out16 = ArrayList<Float>(frames * 2)
        c16.ingestByteBuffer(be16, 0, be16.limit()) { arr, off, len ->
            for (i in 0 until len) out16 += arr[off + i]
        }
        assertEquals(((0 * 31) % 4096 - 2048) / 32768f, out16[0], 0f)

        // f32 BE (stereo passthrough)
        val be32 = ByteBuffer.allocate(frames * 4).order(ByteOrder.BIG_ENDIAN)
        for (f in 0 until frames) be32.putFloat(f / 1000f)
        be32.flip()
        val c32 = PcmIngestResampler(48_000, 1, inputIsPcm16 = false)
        val out32 = ArrayList<Float>(frames * 2)
        c32.ingestByteBuffer(be32, 0, be32.limit()) { arr, off, len ->
            for (i in 0 until len) out32 += arr[off + i]
        }
        assertEquals(0f, out32[0], 0f)
        assertEquals(0.063f, out32[126], 0.0001f) // duplicated stereo, frame 63
    }

    @Test
    fun `chunked streaming is bit-identical to one-shot`() {
        val frames = 10_000
        val signal = FloatArray(frames * 2) {
            (sin(2.0 * PI * 997.0 * (it / 2) / 44_100.0) * 0.9).toFloat()
        }
        val oneShot = PcmIngestResampler(44_100, 2, inputIsPcm16 = false)
        val oneShotOut = collect(oneShot, signal, frames)

        val chunked = PcmIngestResampler(44_100, 2, inputIsPcm16 = false)
        val chunkedOut = ArrayList<Float>(oneShotOut.size)
        var offset = 0
        val chunkSizes = intArrayOf(1, 7, 251, 1024, 4096, 3)
        var ci = 0
        while (offset < frames) {
            val n = minOf(chunkSizes[ci % chunkSizes.size], frames - offset)
            chunked.process(signal, offset * 2, n) { arr, off, len ->
                for (i in 0 until len) chunkedOut += arr[off + i]
            }
            offset += n
            ci++
        }
        // The pending-frame tail rule means the chunked converter holds
        // exactly ONE input frame more than one-shot at compare time;
        // flushTail brings both to the same EOS boundary.
        val tail = ArrayList<Float>(8)
        chunked.flushTail { arr, off, len -> for (i in 0 until len) tail += arr[off + i] }
        for (v in tail) chunkedOut += v
        val eos = ArrayList<Float>(8)
        oneShot.flushTail { arr, off, len -> for (i in 0 until len) eos += arr[off + i] }

        val a = oneShotOut + eos.toFloatArray()
        val b = chunkedOut.toFloatArray()
        assertEquals(a.size, b.size)
        assertTrue(
            "chunked streaming drifted from one-shot (first diff at " +
                firstDiff(a, b) + ")",
            a.contentEquals(b),
        )
    }

    // ── quality tier (dogfood bridge) ─────────────────────────────────────

    @Test
    fun `44k1 to 48k keeps the ratio within a half percent`() {
        val frames = 44_100 // one input second
        val signal = FloatArray(frames * 2) {
            (sin(2.0 * PI * 440.0 * (it / 2) / 44_100.0) * 0.9).toFloat()
        }
        val converter = PcmIngestResampler(44_100, 2, inputIsPcm16 = false)
        val out = collect(converter, signal, frames)
        // ~48_000 frames (±0.5%); the held tail accounts for ≤ 2 frames.
        val ratio = out.size.toDouble() / 2.0 / frames
        assertTrue("unexpected ratio $ratio", abs(ratio - 48_000.0 / 44_100.0) < 0.005)
        for (v in out) {
            assertTrue(!v.isNaN() && !v.isInfinite())
            assertTrue("overshoot $v", abs(v) <= 1.0f)
        }
    }

    @Test
    fun `speed two halves the emitted media rate`() {
        val frames = 9_600 // 0.2 input seconds
        val signal = FloatArray(frames * 2) { (sin(2.0 * PI * 220.0 * (it / 2) / 48_000.0) * 0.5).toFloat() }
        val converter = PcmIngestResampler(48_000, 2, inputIsPcm16 = false, speed = 2f)
        val out = collect(converter, signal, frames)
        // 2x speed at equal rates: one output frame per TWO input frames
        // (0.2 input seconds collapse to 0.1 output seconds).
        assertEquals(frames / 2.0, out.size / 2.0, frames * 0.01)
    }

    @Test
    fun `reset clears the streaming tail`() {
        val converter = PcmIngestResampler(44_100, 2, inputIsPcm16 = false)
        val signal = FloatArray(2 * 100) { 0.5f }
        collect(converter, signal, 100)
        converter.reset()
        // After reset the converter behaves like a fresh one: warmup from
        // digital silence, no stale pending frame leaking into the output.
        val fresh = PcmIngestResampler(44_100, 2, inputIsPcm16 = false)
        val probe = FloatArray(2 * 100) { 0.5f }
        val a = collect(converter, probe, 100)
        val b = collect(fresh, probe, 100)
        assertTrue(a.contentEquals(b))
    }

    private fun firstDiff(a: FloatArray, b: FloatArray): Int {
        val n = minOf(a.size, b.size)
        for (i in 0 until n) if (a[i] != b[i]) return i
        return -1
    }
}
