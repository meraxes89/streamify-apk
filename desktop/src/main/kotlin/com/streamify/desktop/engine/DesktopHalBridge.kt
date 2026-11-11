package com.streamify.desktop.engine

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * DesktopHalBridge — the JNA binding over the desktop HAL C ABI (WS-C / C5)
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * The loadable module `streamify_desktop_hal` (CMake target, built for
 * Linux and Windows hosts — the windows-native rot lane compiles it with
 * MSVC on every core PR) is the platform bracket that finally puts the
 * Phase 4b C facades (`streamify_wasapi_start`,
 * `streamify_linuxhal_start`, `streamify_coreaudio_start`) behind a
 * stable ABI together with the sink registry surface — the SAME frozen
 * contract the C1 JNI bridge binds on Android:
 *
 *   * opaque monotonic int64 registry handles — never pointers; stale
 *     handles degrade to the closed-ring returns (0 / no-op),
 *   * the SPSC f32 ring (write / canonical f32 pull / legacy PCM16 edge),
 *   * the 96-byte little-endian stats wire (magic 0x51AB0001, layout
 *     bytes 96, monotonic sequence at +88),
 *   * the cooperative generational flush (AMP-10 / MP-C10).
 *
 * MOUNTING (C5's wiring slice): the desktop JVM shell binds this module
 * through JNA — the same degradation ladder the Android seam uses
 * (MP-HAL07): [probe] soft-resolves the library (search paths below);
 * an absent module reads as `null` and every consumer degrades
 * gracefully, never an UnsatisfiedLinkError at a call site. The
 * engine-side relay that feeds the Rust producer's `playback_ring()`
 * output into a registry sink is ENG-1's follow-up on the engine lane;
 * until it lands this bridge is exercised end-to-end by
 * [com.streamify.desktop.engine.DesktopJnaEngineSmokeTest]'s C5
 * round-trip (create → write → read f32 → stats → flush → destroy).
 *
 * LIBRARY RESOLUTION ORDER (all optional — hermetic hosts skip cleanly):
 *  1. `streamify.desktop.hal.dir` system property (explicit override),
 *  2. `STREAMIFY_DESKTOP_HAL_DIR` environment (set by the CI lane that
 *     builds the module next to the workspace),
 *  3. JNA's default search (`jna.library.path` — the test JVM already
 *     points it at the cargo output dir; a module built locally under
 *     one of the `native/build` directories can be appended the same
 *     way — and the classpath fallback).
 */
object DesktopHalBridge {

    /** Crate/module name — JNA maps it per platform (.dll/.so/.dylib). */
    const val HAL_LIBRARY = "streamify_desktop_hal"

    /** Explicit-override system property (resolved before env/JNA paths). */
    private const val HAL_DIR_PROPERTY = "streamify.desktop.hal.dir"

    /** CI/local environment variable carrying the module's directory. */
    private const val HAL_DIR_ENV = "STREAMIFY_DESKTOP_HAL_DIR"

    /**
     * The C ABI (native/include/desktop_hal.h) — JNA direct mapping.
     * Float arrays marshal as `float*`; the stats wire as a byte array.
     */
    interface HalApi : Library {
        // ---- the sink registry surface (the frozen contract, plain C) ----
        fun streamify_desktop_sink_create(
            sampleRate: Int,
            channels: Int,
            capacityFrames: Int,
        ): Long

        fun streamify_desktop_sink_write_f32(
            handle: Long,
            samples: FloatArray,
            sampleCount: Int,
        ): Int

        fun streamify_desktop_sink_read_f32(
            handle: Long,
            dst: FloatArray,
            maxFrames: Int,
        ): Int

        fun streamify_desktop_sink_read_pcm16(
            handle: Long,
            dst: ByteArray,
            capacityBytes: Int,
        ): Int

        fun streamify_desktop_sink_stats(handle: Long, outWire: ByteArray): Int

        fun streamify_desktop_sink_flush(handle: Long)

        fun streamify_desktop_sink_destroy(handle: Long)

        fun streamify_desktop_sink_live_count(): Long

        // ---- the platform pull-adapter facades (Phase 4b) -----------------
        fun streamify_wasapi_start(sinkHandle: Long): Long

        fun streamify_wasapi_stop(adapterHandle: Long)

        fun streamify_wasapi_latest_clock(
            adapterHandle: Long,
            outFrames: LongArray,
            outRate: IntArray,
            outHostNs: LongArray,
        ): Int

        fun streamify_linuxhal_start(sinkHandle: Long): Long

        fun streamify_linuxhal_stop(driverHandle: Long)

        fun streamify_linuxhal_backend(driverHandle: Long): Int

        fun streamify_linuxhal_latest_clock(
            driverHandle: Long,
            outFrames: LongArray,
            outRate: IntArray,
            outHostNs: LongArray,
        ): Int
    }

    /** The resolved API; null until [probe] succeeds (sticky per process). */
    @Volatile
    private var resolved: HalApi? = null

    /**
     * Soft-probes the desktop HAL module. Returns the bound API or null
     * (hermetic host / module not built) — NEVER throws.
     */
    fun probe(): HalApi? {
        resolved?.let { return it }
        // Explicit search-path hints first (JNA's addSearchPath is
        // process-global and idempotent).
        runCatching {
            System.getProperty(HAL_DIR_PROPERTY)?.let { dir ->
                NativeLibrary.addSearchPath(HAL_LIBRARY, dir)
            }
            System.getenv(HAL_DIR_ENV)?.let { dir ->
                NativeLibrary.addSearchPath(HAL_LIBRARY, dir)
            }
        }
        val api = runCatching {
            Native.load(HAL_LIBRARY, HalApi::class.java)
        }.getOrNull()
        resolved = api
        return api
    }

    // ── The 96-byte stats wire (frozen v1, LE — the Kotlin twin of the
    //    app-side RustAudioSinkJni decoder; desktop cannot reach the
    //    Android module, so the layout lives here too, pinned by the C5
    //    round-trip in DesktopJnaEngineSmokeTest) ──────────────────────────

    const val STATS_WIRE_MAGIC: Int = 0x51AB0001.toInt()
    const val STATS_WIRE_BYTES: Int = 96

    /** One decoded stats snapshot (mirrors the C++ `SinkStats` the wire carries). */
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
     * Decodes the 96-byte LE stats wire image. Returns null on a
     * magic/layout mismatch or truncation — a hostile or stale image
     * degrades to "no stats", never a crash.
     */
    fun decodeStats(wire: ByteArray): SinkStatsSnapshot? {
        if (wire.size < STATS_WIRE_BYTES) return null
        val buf = ByteBuffer.wrap(wire, 0, STATS_WIRE_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)
        if (buf.int != STATS_WIRE_MAGIC) return null
        if (buf.int != STATS_WIRE_BYTES) return null
        return SinkStatsSnapshot(
            capacityFrames = buf.int,
            sampleRateIn = buf.int,
            sampleRateOut = buf.int,
            channelsIn = buf.int,
            channelsOut = buf.int,
            currentFullnessFrames = buf.int,
            framesWritten = buf.long,
            framesRead = buf.long,
            underrunEvents = buf.long,
            overrunEvents = buf.long,
            overrunDroppedFrames = buf.long,
            highWatermarkFrames = buf.int,
            lowWatermarkFrames = buf.int,
            fullnessPercent = buf.float,
            estimatedLatencyMs = buf.float,
            sequence = buf.int,
        )
    }
}
