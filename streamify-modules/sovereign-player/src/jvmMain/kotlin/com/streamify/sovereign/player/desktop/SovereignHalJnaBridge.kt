package com.streamify.sovereign.player.desktop

import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer

/**
 * Sovereign HAL Phase 1 (D4): the desktop JVM bridge to the Rust
 * engine's exported C ABI.
 *
 * The engine library (`libstreamify_core_rs` / `streamify_core_rs.dll` /
 * `streamify_core_rs.dylib` — the Phase 0 `buildDesktopEngineLibrary`
 * artifact) exports the FROZEN ring + stats edge; the unified cpal
 * output engine (streamify-hal) consumes it in-process on the Rust side.
 * The JVM only needs the read-only surfaces:
 *
 *  * `streamify_ring_stats_wire` — the 96-byte telemetry wire
 *    (magic `0x51AB0001`), polled by SettingScreen.
 *  * `streamify_ring_occupancy_frames` / `streamify_ring_channels` —
 *    the live-geometry reads the route policy's health ladder uses.
 *
 * The RT audio path NEVER crosses this bridge (cpal pulls the ring
 * inside the Rust process; the JVM never touches the audio thread).
 */
interface SovereignHalNative : Library {
    /** The frozen stats wire: 1 on success, 0 when detached/too-small. */
    fun streamify_ring_stats_wire(out: Pointer, outLen: Int): Int

    /** Live occupancy of the exported ring, in frames (0 while detached). */
    fun streamify_ring_occupancy_frames(): Long

    /** The exported ring's channel count (0 while detached). */
    fun streamify_ring_channels(): Int
}

/** The loaded native edge (null while the library is absent). */
internal val SOVEREIGN_NATIVE: SovereignHalNative? by lazy {
    val candidates = listOf(
        "streamify_core_rs", // Linux: libstreamify_core_rs.so
        "streamify_core_rs", // Windows: streamify_core_rs.dll (same stem)
    )
    for (stem in candidates.distinct()) {
        runCatching { Native.load(stem, SovereignHalNative::class.java) }
            .onSuccess { return it }
    }
    null
}

/** One decoded 96-byte stats-wire snapshot (the layout the Rust `SinkStats::to_wire` writes). */
data class SovereignStatsWire(
    val layoutMagic: Long,
    val layoutBytes: Long,
    val capacityFrames: Long,
    val sampleRateIn: Long,
    val sampleRateOut: Long,
    val channelsIn: Long,
    val channelsOut: Long,
    val currentFullnessFrames: Long,
    val framesWritten: Long,
    val framesRead: Long,
    val underrunEvents: Long,
    val overrunEvents: Long,
    val overrunDroppedFrames: Long,
    val highWatermarkFrames: Long,
    val lowWatermarkFrames: Long,
    val fullnessPercent: Float,
    val estimatedLatencyMs: Float,
    val sequence: Long,
)

/** The frozen stats wire size + magic (mirror of the Rust constants). */
const val SINK_STATS_WIRE_BYTES: Int = 96
const val SINK_STATS_WIRE_MAGIC: Long = 0x51AB0001L

/**
 * Read one stats-wire snapshot. `null` when the native edge is absent
 * or no ring is attached (the ladder degrades to legacy — never throws).
 */
fun readSovereignStatsWire(): SovereignStatsWire? {
    val native = SOVEREIGN_NATIVE ?: return null
    return runCatching {
        val mem = Memory(SINK_STATS_WIRE_BYTES.toLong())
        val ok = native.streamify_ring_stats_wire(mem, SINK_STATS_WIRE_BYTES)
        if (ok != 1) return null
        fun u32(off: Int) = mem.getInt((off * 4).toLong()).toLong() and 0xFFFFFFFFL
        fun u64(off: Int): Long {
            var v = 0L
            for (i in 7 downTo 0) {
                v = (v shl 8) or (mem.getByte(((off * 4) + i).toLong()).toLong() and 0xFF)
            }
            return v
        }
        SovereignStatsWire(
            layoutMagic = u32(0),
            layoutBytes = u32(1),
            capacityFrames = u32(2),
            sampleRateIn = u32(3),
            sampleRateOut = u32(4),
            channelsIn = u32(5),
            channelsOut = u32(6),
            currentFullnessFrames = u32(7),
            framesWritten = u64(8),
            framesRead = u64(10),
            underrunEvents = u64(12),
            overrunEvents = u64(14),
            overrunDroppedFrames = u64(16),
            highWatermarkFrames = u32(18),
            lowWatermarkFrames = u32(19),
            fullnessPercent = mem.getFloat((20 * 4).toLong()),
            estimatedLatencyMs = mem.getFloat((21 * 4).toLong()),
            sequence = u32(22),
        )
    }.getOrNull()?.takeIf { it.layoutMagic == SINK_STATS_WIRE_MAGIC }
}

/** The exported ring's live channel count (0 = detached). */
fun sovereignRingChannels(): Int = SOVEREIGN_NATIVE?.streamify_ring_channels() ?: 0
