package com.streamify.app.media.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * RustAudioSinkJni — the app-side seam over the FROZEN audio-sink JNI contract
 * (Phase 4a / MP-HAL05 + MP-HAL07, Beta subtree).
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * The frozen symbol contract lives in Alpha's
 * `native/include/streamify_audio_sinks.h` (six entrypoints) plus the
 * additive f32 pull edge (MP-HAL05):
 *
 *   Java_com_streamify_core_native_1_NativeAudioSink_nativeCreateSink
 *   Java_com_streamify_core_native_1_NativeAudioSink_nativeWriteFloat
 *   Java_com_streamify_core_native_1_NativeAudioSink_nativeReadDirect
 *   Java_com_streamify_core_native_1_NativeAudioSink_nativeGetStats
 *   Java_com_streamify_core_native_1_NativeAudioSink_nativeFlush
 *   Java_com_streamify_core_native_1_NativeAudioSink_nativeDestroy
 *   Java_com_streamify_core_native_1_NativeAudioSink_nativeReadFramesF32  ← additive
 *
 * THE SEAM, NOT THE DECLARATIONS. The actual `external fun` declarations
 * CANNOT live in `app/src/main/java`: the repo's JNI parity gate
 * (audit #44 P0-1, `scripts/jni_parity_check.py`) — correctly — fails any
 * build whose main tree declares a native method with no matching export
 * in the native or rust source trees (a deferred `UnsatisfiedLinkError`).
 * Alpha's `jni_bridge_audio_sinks.cpp` has not landed, so the declarations
 * would be exactly that violation.
 *
 * The declarations therefore live in the DEBUG-ONLY dogfood source set
 * `app/src/rustAudioDogfood/java` (see `NativeAudioSink.kt` there):
 *
 *  • RELEASE builds never compile them — MP-HAL07's "default false in
 *    release" is enforced structurally (the code is ABSENT, not merely
 *    toggled off).
 *  • DEBUG builds compile them and a reflection bootstrap (AppGraph step
 *    0c, house `StreamifyEngineBridge` soft-probe pattern) installs the
 *    binding into [binding] here.
 *  • Every consumer goes through THIS seam: an absent binding (release,
 *    JVM tests, bootstrap miss) reads as "edges unresolved" — the ring
 *    reports closed, the route policy falls back to the legacy Media3
 *    chain. Never a raw `UnsatisfiedLinkError` at a call site.
 *
 * When Alpha's bridge lands, the declarations move back into the main
 * tree (the parity gate then sees matching exports and stays green) —
 * zero changes above this seam.
 */
object RustAudioSinkJni {

    // ── The typed binding surface (mirrors the frozen contract 1:1) ───────

    /**
     * One installed JNI binding. The dogfood source set provides the
     * production implementation over the frozen symbols; JVM tests can
     * install fakes. Method names are Kotlin-idiomatic — the frozen
     * `native*` JNI names live only on the dogfood binding class (an
     * implementing class cannot redeclare an `external fun` and an
     * `override fun` with the same signature).
     */
    interface Binding {
        /** Creates one SPSC f32 sink ring; returns the opaque handle (< 0 on rejection). */
        fun createSink(sampleRate: Int, channels: Int, capacityFrames: Int): Long

        /** Producer edge: non-blocking whole-frame f32 write; returns samples consumed. */
        fun writeFloat(handle: Long, data: FloatArray, offset: Int, length: Int): Int

        /** LEGACY PCM16 DirectByteBuffer read (frozen edge). */
        fun readDirectPcm16(handle: Long, buf: ByteBuffer, capacityBytes: Int): Int

        /** CANONICAL f32 pull edge (MP-HAL05 additive). Returns frames read (0 = starved). */
        fun readFramesF32(handle: Long, dst: FloatArray, maxFrames: Int): Int

        /** Serializes the 96-byte LE stats wire image into [statsOut]. */
        fun getStats(handle: Long, statsOut: ByteBuffer)

        /** Cooperative generational flush (AMP-10 / MP-C10). */
        fun flush(handle: Long)

        /** Reference-counted destroy; idempotent for stale handles. */
        fun destroy(handle: Long)

        /**
         * Probes which native edges actually resolved in this process —
         * the implementation TOUCHES each external under runCatching so
         * an absent symbol reports as a typed false (the MP-HAL07
         * degradation ladder's step 3).
         */
        fun edgesResolved(): NativeSinkEdges
    }

    /**
     * Which native edges of the sink contract actually resolved.
     * (Main-tree twin of the dogfood class's probe report.)
     */
    data class NativeSinkEdges(
        val createSink: Boolean,
        val writeFloat: Boolean,
        val readFramesF32: Boolean,
        val readDirectPcm16: Boolean,
        val stats: Boolean,
        val flush: Boolean,
        val destroy: Boolean,
    ) {
        /** The full dogfood f32 pull path needs create/write/read-f32/flush/destroy. */
        val supportsFloatPullPath: Boolean
            get() = createSink && writeFloat && readFramesF32 && flush && destroy
    }

    /** The all-false report used when no binding is installed. */
    val NO_EDGES = NativeSinkEdges(
        createSink = false,
        writeFloat = false,
        readFramesF32 = false,
        readDirectPcm16 = false,
        stats = false,
        flush = false,
        destroy = false,
    )

    /** The installed binding; null on release builds / before bootstrap. */
    @Volatile
    var binding: Binding? = null

    /** Edge availability through the installed binding (all-false when absent). */
    fun probeEdges(): NativeSinkEdges = binding?.edgesResolved() ?: NO_EDGES

    // ── Stats wire layout (frozen v1, 96 bytes, LE — pure Kotlin) ─────────
    //
    // Mirror of the offsets in streamify_audio_sinks.h — main-tree so the
    // decoder is provable in hermetic JVM tests without an NDK.

    const val STATS_WIRE_MAGIC: Int = 0x51AB0001.toInt()
    const val STATS_WIRE_BYTES: Int = 96

    private const val OFF_MAGIC = 0
    private const val OFF_LAYOUT_BYTES = 4
    private const val OFF_CAPACITY = 8
    private const val OFF_RATE_IN = 12
    private const val OFF_RATE_OUT = 16
    private const val OFF_CHANNELS_IN = 20
    private const val OFF_CHANNELS_OUT = 24
    private const val OFF_FULLNESS = 28
    private const val OFF_FRAMES_WRITTEN = 32
    private const val OFF_FRAMES_READ = 40
    private const val OFF_UNDERRUNS = 48
    private const val OFF_OVERRUNS = 56
    private const val OFF_OVERRUN_DROPPED = 64
    private const val OFF_HIGH_WATERMARK = 72
    private const val OFF_LOW_WATERMARK = 76
    private const val OFF_FULLNESS_PCT = 80
    private const val OFF_LATENCY_MS = 84
    private const val OFF_SEQUENCE = 88

    /**
     * One decoded stats snapshot. Mirrors the C++ `SinkStats` the wire
     * carries; `sequence` is the change detector (increments every write).
     */
    data class SinkStatsSnapshot(
        val capacityFrames: Int,
        val sampleRateIn: Int,
        val sampleRateOut: Int,
        val channelsIn: Int,
        val channelsOut: Int,
        val currentFullnessFrames: Int,
        val framesWritten: Long,
        val framesRead: Long,
        val underrunEvents: Long,
        val overrunEvents: Long,
        val overrunDroppedFrames: Long,
        val highWatermarkFrames: Int,
        val lowWatermarkFrames: Int,
        val fullnessPercent: Float,
        val estimatedLatencyMs: Float,
        val sequence: Int,
    )

    /**
     * Decodes the 96-byte little-endian stats wire image. The buffer's
     * position is NOT consumed (absolute indexed reads); returns null on
     * a magic/layout mismatch or truncation — a hostile or stale image
     * degrades to "no stats", never a crash.
     */
    fun decodeStats(wire: ByteBuffer): SinkStatsSnapshot? {
        if (wire.capacity() < STATS_WIRE_BYTES) return null
        val orderSaved = wire.order()
        wire.order(ByteOrder.LITTLE_ENDIAN)
        try {
            if (wire.getInt(OFF_MAGIC) != STATS_WIRE_MAGIC) return null
            if (wire.getInt(OFF_LAYOUT_BYTES) != STATS_WIRE_BYTES) return null
            return SinkStatsSnapshot(
                capacityFrames = wire.getInt(OFF_CAPACITY),
                sampleRateIn = wire.getInt(OFF_RATE_IN),
                sampleRateOut = wire.getInt(OFF_RATE_OUT),
                channelsIn = wire.getInt(OFF_CHANNELS_IN),
                channelsOut = wire.getInt(OFF_CHANNELS_OUT),
                currentFullnessFrames = wire.getInt(OFF_FULLNESS),
                framesWritten = wire.getLong(OFF_FRAMES_WRITTEN),
                framesRead = wire.getLong(OFF_FRAMES_READ),
                underrunEvents = wire.getLong(OFF_UNDERRUNS),
                overrunEvents = wire.getLong(OFF_OVERRUNS),
                overrunDroppedFrames = wire.getLong(OFF_OVERRUN_DROPPED),
                highWatermarkFrames = wire.getInt(OFF_HIGH_WATERMARK),
                lowWatermarkFrames = wire.getInt(OFF_LOW_WATERMARK),
                fullnessPercent = wire.getFloat(OFF_FULLNESS_PCT),
                estimatedLatencyMs = wire.getFloat(OFF_LATENCY_MS),
                sequence = wire.getInt(OFF_SEQUENCE),
            )
        } catch (_: IndexOutOfBoundsException) {
            return null
        } finally {
            wire.order(orderSaved)
        }
    }
}
