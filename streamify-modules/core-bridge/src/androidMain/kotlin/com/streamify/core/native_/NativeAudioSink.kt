@file:Suppress("PrivatePropertyName", "unused")

package com.streamify.core.native_

import com.streamify.app.media.audio.RustAudioSinkJni
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * NativeAudioSink — Kotlin binding class of the FROZEN audio-sink JNI edge
 * (Phase 4a / MP-HAL05, Beta subtree) — MAIN-TREE since WS-C/C1 landed the
 * bridge (`native/src/jni_bridge_audio_sinks.cpp`, pinned on the host
 * sanitizer lanes by `jni_sink_bridge_test_suite`).
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * WHY THIS FILE IS BACK IN `app/src/main/java`
 * ─────────────────────────────────────────
 * The repo's JNI parity gate (audit #44 P0-1,
 * `scripts/jni_parity_check.py --source`) fails any build whose main tree
 * declares an `external fun` with no matching export in the native or
 * rust source trees — a deferred `UnsatisfiedLinkError` is a P0 defect by
 * repo law. While the C++ half of this contract was unwritten, the
 * declarations lived in the DEBUG-ONLY dogfood source set
 * (`app/src/rustAudioDogfood/java`) so the gate stayed green.
 *
 * WS-C (C1) landed the bridge — the 7 frozen symbols are compiled into
 * `libstreamify_native_core.so` — so the declarations move back into the
 * main tree exactly as the frozen contract always specified: "when
 * Alpha's bridge lands, move the file into the main tree (the parity
 * gate then sees matching exports and stays green) — zero changes above
 * the seam".
 *
 * MP-HAL07 discipline is PRESERVED: the class compiles everywhere, but
 * the bootstrap that installs it into the [RustAudioSinkJni] seam only
 * runs on debug builds or under the USE_RUST_AUDIO dogfood flag (see
 * AppGraph step 0d) — release builds still ship the legacy Media3 route
 * with the binding absent, and every runtime call remains PROBE-GUARDED
 * ([edgesResolved] touches each external under runCatching): on a device
 * whose library set is torn, the probes report false,
 * [com.streamify.app.media.audio.AudioRoutePolicy] degrades to the
 * legacy Media3 chain, and no `UnsatisfiedLinkError` can detonate at a
 * call site.
 *
 * THE FROZEN SYMBOL CONTRACT (package name is ABI):
 *
 *   Java_com_streamify_core_native_1_NativeAudioSink_nativeCreateSink
 *   Java_com_streamify_core_native_1_NativeAudioSink_nativeWriteFloat
 *   Java_com_streamify_core_native_1_NativeAudioSink_nativeReadDirect
 *   Java_com_streamify_core_native_1_NativeAudioSink_nativeGetStats
 *   Java_com_streamify_core_native_1_NativeAudioSink_nativeFlush
 *   Java_com_streamify_core_native_1_NativeAudioSink_nativeDestroy
 *   Java_com_streamify_core_native_1_NativeAudioSink_nativeReadFramesF32  ← MP-HAL05 additive:
 *     (jlong handle, jfloatArray dst, jint max_frames) -> jint, semantics
 *     1:1 with `AudioSink::ReadFramesF32(float*, size_t)` — clamp
 *     max_frames to dst capacity / channels_out, never allocate, never
 *     block, never throw.
 *
 * (`_1` escapes the underscore in the package segment per JNI naming
 * rules; `native` alone is a Java keyword, hence the legal `native_`
 * escape documented in the C++ header.)
 *
 * BINDING SHAPE: the object implements the main-tree seam
 * [RustAudioSinkJni.Binding] with Kotlin-idiomatic method names — the
 * `native*` externals keep the frozen names (an implementing class cannot
 * redeclare an `external fun` and an `override fun` with the identical
 * signature, so the seam deliberately does not reuse them).
 *
 * JNI RESOLUTION MODEL: `external fun` binds lazily per-method at first
 * invocation, searching every native library already loaded into the
 * classloader (`streamify_core_rs` / `streamify_native_core` /
 * `streamify_core` — the NativeBridge fallback chain). A missing symbol
 * therefore never crashes class-load; it surfaces as a catchable
 * [UnsatisfiedLinkError] at the call site, which the probe below converts
 * into a typed availability report.
 */
object NativeAudioSink : RustAudioSinkJni.Binding {

    // ── The frozen JNI contract ────────────────────────────────────────────

    /**
     * Creates one SPSC float sink ring. Returns an opaque registry handle
     * (monotonically increasing int64 — NEVER a raw pointer), or a
     * NEGATIVE sentinel on invalid configuration / registry exhaustion.
     */
    @JvmStatic
    external fun nativeCreateSink(sampleRate: Int, channels: Int, capacityFrames: Int): Long

    /**
     * Producer edge: writes up to `length` interleaved float SAMPLES from
     * `data[offset]` (whole frames only; a trailing partial frame stays
     * for the caller). Returns the number of input samples actually
     * consumed — short returns apply ring backpressure, the caller retries
     * the remainder. Never blocks.
     */
    @JvmStatic
    external fun nativeWriteFloat(handle: Long, data: FloatArray, offset: Int, length: Int): Int

    /**
     * LEGACY PCM16 consumer edge (frozen DirectByteBuffer contract): reads
     * frame-aligned PCM16 bytes into a direct `buf`. Returns bytes copied
     * (multiple of the frame size), 0 when starved. The f32 ring contents
     * are quantized AT THIS EDGE — the dogfood f32 path uses
     * [nativeReadFramesF32] instead.
     */
    @JvmStatic
    external fun nativeReadDirect(handle: Long, buf: ByteBuffer, capacityBytes: Int): Int

    /**
     * CANONICAL f32 PULL EDGE (MP-HAL05 — additive contract, see file
     * header): reads up to `maxFrames` interleaved f32 output frames
     * straight out of the float ring into `dst` (frame count × channels
     * floats). Returns the frame count actually read; 0 == starved (and
     * counts one underrun event inside the ring). Zero quantization
     * between the producer and the DAC.
     */
    @JvmStatic
    external fun nativeReadFramesF32(handle: Long, dst: FloatArray, maxFrames: Int): Int

    /**
     * Serializes the sink stats snapshot into `statsOut` (direct
     * ByteBuffer, capacity >= 96 bytes, little-endian layout v1). The
     * `sequence` field at +88 increments on every write — the app-side
     * change detector (decoded by [RustAudioSinkJni.decodeStats]).
     */
    @JvmStatic
    external fun nativeGetStats(handle: Long, statsOut: ByteBuffer)

    /**
     * Cooperative generational flush (AMP-10 / MP-C10): bumps the flush
     * generation and bounded-waits (<= 100ms) for the CONSUMER thread to
     * acknowledge. Safe while the pull feeder is live — the consumer
     * performs the cursor reset at the top of its next read. MUST be
     * called from a producer/control thread, never the consumer thread.
     */
    @JvmStatic
    external fun nativeFlush(handle: Long)

    /**
     * Reference-counted destroy: drops the registry entry; a destroy
     * racing an in-flight call just unmaps the handle (the shared_ptr
     * keeps the sink alive until the last caller is done). Idempotent for
     * a stale handle.
     */
    @JvmStatic
    external fun nativeDestroy(handle: Long)

    // ── RustAudioSinkJni.Binding (the seam-side typed surface) ────────────

    override fun createSink(sampleRate: Int, channels: Int, capacityFrames: Int): Long =
        nativeCreateSink(sampleRate, channels, capacityFrames)

    override fun writeFloat(handle: Long, data: FloatArray, offset: Int, length: Int): Int =
        nativeWriteFloat(handle, data, offset, length)

    override fun readDirectPcm16(handle: Long, buf: ByteBuffer, capacityBytes: Int): Int =
        nativeReadDirect(handle, buf, capacityBytes)

    override fun readFramesF32(handle: Long, dst: FloatArray, maxFrames: Int): Int =
        nativeReadFramesF32(handle, dst, maxFrames)

    override fun getStats(handle: Long, statsOut: ByteBuffer) {
        nativeGetStats(handle, statsOut)
    }

    override fun flush(handle: Long) {
        nativeFlush(handle)
    }

    override fun destroy(handle: Long) {
        nativeDestroy(handle)
    }

    // ── Availability probing (MP-HAL07 degradation ladder, step 3) ────────

    /** Probe result cache — sticky per process (invalidate on library swaps). */
    @Volatile
    private var cachedEdges: RustAudioSinkJni.NativeSinkEdges? = null

    fun invalidateProbeCache() {
        cachedEdges = null
    }

    override fun edgesResolved(): RustAudioSinkJni.NativeSinkEdges {
        cachedEdges?.let { return it }
        val probed = RustAudioSinkJni.NativeSinkEdges(
            createSink = probe { nativeCreateSink(0, 0, 0) },
            writeFloat = probe { nativeWriteFloat(-1L, EmptyFloats, 0, 0) },
            readFramesF32 = probe { nativeReadFramesF32(-1L, EmptyFloats, 0) },
            readDirectPcm16 = probe { nativeReadDirect(-1L, EmptyStatsBuffer, 0) },
            stats = probe { nativeGetStats(-1L, EmptyStatsBuffer) },
            flush = probe { nativeFlush(-1L) },
            destroy = probe { nativeDestroy(-1L) },
        )
        cachedEdges = probed
        return probed
    }

    private val EmptyFloats = FloatArray(0)
    private val EmptyStatsBuffer: ByteBuffer =
        ByteBuffer.allocateDirect(RustAudioSinkJni.STATS_WIRE_BYTES).order(ByteOrder.LITTLE_ENDIAN)

    /** True when the external resolved (any return, even a rejection sentinel). */
    private inline fun probe(block: () -> Any?): Boolean = try {
        block()
        true
    } catch (_: UnsatisfiedLinkError) {
        false
    }
}

/**
 * Bootstrap for the sink binding — installed by AppGraph's step-0d hook
 * on debug builds / under the USE_RUST_AUDIO dogfood flag (MP-HAL07:
 * release builds never install it; the seam stays null and the route
 * policy ships the legacy Media3 chain). Since C1 moved this class into
 * the main tree the hook is a DIRECT call — the historical reflection
 * lookup (kept in the git log) existed only to reach the debug-only
 * dogfood source set.
 */
object RustAudioDogfoodBootstrap {

    /** Idempotently installs [NativeAudioSink] into the [RustAudioSinkJni] seam. */
    @JvmStatic
    fun install() {
        if (RustAudioSinkJni.binding == null) {
            RustAudioSinkJni.binding = NativeAudioSink
        }
    }
}
