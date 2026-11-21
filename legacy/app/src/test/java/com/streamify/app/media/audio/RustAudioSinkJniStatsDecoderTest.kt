package com.streamify.app.media.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * ═════════════════════════════════════════════════════════════════════════
 * Phase 4a (MP-HAL05) — the frozen 96-byte stats wire decoder (main-tree
 * seam [RustAudioSinkJni]), proven hermetically against the layout contract
 * in `native/include/streamify_audio_sinks.h` (layout v1, little-endian).
 * ═════════════════════════════════════════════════════════════════════════
 */
class RustAudioSinkJniStatsDecoderTest {

    private fun wire(
        magic: Int = RustAudioSinkJni.STATS_WIRE_MAGIC,
        layoutBytes: Int = RustAudioSinkJni.STATS_WIRE_BYTES,
        mutate: (ByteBuffer) -> Unit = {},
    ): ByteBuffer {
        val buf = ByteBuffer.allocateDirect(RustAudioSinkJni.STATS_WIRE_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(0, magic)
        buf.putInt(4, layoutBytes)
        buf.putInt(8, 16_384)      // capacity_frames
        buf.putInt(12, 48_000)     // sample_rate_in
        buf.putInt(16, 48_000)     // sample_rate_out
        buf.putInt(20, 2)          // channels_in
        buf.putInt(24, 2)          // channels_out
        buf.putInt(28, 3_000)      // current_fullness_frames
        buf.putLong(32, 10_000L)   // frames_written
        buf.putLong(40, 9_000L)    // frames_read
        buf.putLong(48, 4L)        // underrun_events
        buf.putLong(56, 1L)        // overrun_events
        buf.putLong(64, 0L)        // overrun_dropped_frames
        buf.putInt(72, 4_096)      // high_watermark_frames
        buf.putInt(76, 12)         // low_watermark_frames
        buf.putFloat(80, 18.31f)   // fullness_percent
        buf.putFloat(84, 62.5f)    // estimated_latency_ms
        buf.putInt(88, 77)         // stats sequence
        mutate(buf)
        return buf
    }

    @Test
    fun `decodes every field of a valid v1 image`() {
        val snapshot = RustAudioSinkJni.decodeStats(wire())
        assertNotNull(snapshot)
        snapshot!!
        assertEquals(16_384, snapshot.capacityFrames)
        assertEquals(48_000, snapshot.sampleRateIn)
        assertEquals(48_000, snapshot.sampleRateOut)
        assertEquals(2, snapshot.channelsIn)
        assertEquals(2, snapshot.channelsOut)
        assertEquals(3_000, snapshot.currentFullnessFrames)
        assertEquals(10_000L, snapshot.framesWritten)
        assertEquals(9_000L, snapshot.framesRead)
        assertEquals(4L, snapshot.underrunEvents)
        assertEquals(1L, snapshot.overrunEvents)
        assertEquals(0L, snapshot.overrunDroppedFrames)
        assertEquals(4_096, snapshot.highWatermarkFrames)
        assertEquals(12, snapshot.lowWatermarkFrames)
        assertEquals(18.31f, snapshot.fullnessPercent, 0.0001f)
        assertEquals(62.5f, snapshot.estimatedLatencyMs, 0.0001f)
        assertEquals(77, snapshot.sequence)
    }

    @Test
    fun `rejects a stale or hostile magic`() {
        assertNull(RustAudioSinkJni.decodeStats(wire(magic = 0xDEADBEEF.toInt())))
    }

    @Test
    fun `rejects a layout-size mismatch`() {
        assertNull(RustAudioSinkJni.decodeStats(wire(layoutBytes = 64)))
    }

    @Test
    fun `rejects a truncated buffer`() {
        val short = ByteBuffer.allocateDirect(32).order(ByteOrder.LITTLE_ENDIAN)
        assertNull(RustAudioSinkJni.decodeStats(short))
    }

    @Test
    fun `decoder does not consume the buffer position`() {
        val buf = wire()
        buf.position(42)
        RustAudioSinkJni.decodeStats(buf)
        assertEquals(42, buf.position())
    }

    @Test
    fun `uninstalled seam degrades to no-edges (release or bootstrap miss)`() {
        // No binding installed in the hermetic JVM: the seam reports the
        // all-false availability and never throws — the typed-degradation
        // contract AudioRoutePolicy's fallback rides on (MP-HAL07).
        val saved = RustAudioSinkJni.binding
        try {
            RustAudioSinkJni.binding = null
            val edges = RustAudioSinkJni.probeEdges()
            assertFalse(edges.createSink)
            assertFalse(edges.readFramesF32)
            assertFalse(edges.supportsFloatPullPath)
            assertEquals(edges, RustAudioSinkJni.NO_EDGES)
        } finally {
            RustAudioSinkJni.binding = saved
        }
    }

    @Test
    fun `installed fake binding drives the probe through the seam`() {
        val saved = RustAudioSinkJni.binding
        try {
            RustAudioSinkJni.binding = object : RustAudioSinkJni.Binding {
                override fun createSink(sampleRate: Int, channels: Int, capacityFrames: Int) = 1L
                override fun writeFloat(handle: Long, data: FloatArray, offset: Int, length: Int) = length
                override fun readDirectPcm16(handle: Long, buf: ByteBuffer, capacityBytes: Int) = 0
                override fun readFramesF32(handle: Long, dst: FloatArray, maxFrames: Int) = 0
                override fun getStats(handle: Long, statsOut: ByteBuffer) {}
                override fun flush(handle: Long) {}
                override fun destroy(handle: Long) {}
                override fun edgesResolved() = RustAudioSinkJni.NativeSinkEdges(
                    createSink = true, writeFloat = true, readFramesF32 = true,
                    readDirectPcm16 = false, stats = false, flush = true, destroy = true,
                )
            }
            val edges = RustAudioSinkJni.probeEdges()
            assertEquals(true, edges.supportsFloatPullPath)
            assertEquals(false, edges.stats)
        } finally {
            RustAudioSinkJni.binding = saved
        }
    }
}
