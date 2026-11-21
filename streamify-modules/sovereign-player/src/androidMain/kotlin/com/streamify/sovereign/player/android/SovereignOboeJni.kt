package com.streamify.sovereign.player.android

import android.content.Context

/**
 * Sovereign HAL Phase 2 (Step 1): the ANDROID Oboe bracket's native
 * seam — the C ABI of `native/src/oboe_output_bracket.cpp`, loaded
 * from `libstreamify_native_core.so` (the NDK CMake umbrella the app
 * already packages).
 *
 * MOUNTING GEOMETRY: the Rust producer (libstreamify_core_rs.so) owns
 * the frozen SPSC ring; the C++ Oboe bracket's `onAudioReady` drains
 * it through `streamify_ring_read_f32` (wait-free, zero-alloc). This
 * seam exposes the bracket's lifecycle + telemetry to Kotlin: open /
 * close / counters / the DAC clock mailbox / the 96-byte stats wire /
 * which Oboe backend actually engaged (the ladder's route telemetry).
 *
 * ZERO-CRASH DISCIPLINE (the AudioRoutePolicy contract): a missing or
 * torn native library reads as `null` — never an UnsatisfiedLinkError
 * at a call site. The lane's probe reports the reason and the route
 * ladder degrades to media3 (the zero-deletion policy: the legacy
 * DefaultAudioSink stays the shipped default).
 */
object SovereignOboeJni {

    /** One resolved binding of the bracket's C ABI. */
    interface Binding {
        /** Opens the bracket against the installed ring. api: 1 = Oboe/AAudio first, 2 = force OpenSL ES. 0 = ok, negative = error. */
        fun open(api: Int): Int

        /** Closes + joins the controller thread (idempotent). */
        fun close()

        /** Audio callbacks served so far (diagnostics; 0 while closed). */
        fun callbacks(): Long

        /** Underrun FRAMES zero-filled so far (the verdict's hard gate). */
        fun underrunFrames(): Long

        /** Live ring occupancy in frames (producer-health telemetry). */
        fun ringOccupancy(): Long

        /** The latest DAC clock sample from Oboe's getTimestamp; null before the first poll. */
        fun dacClock(): DacClockSample?

        /** The ring's 96-byte stats wire image, or null when detached. */
        fun statsWire(): ByteArray?

        /** 1 = AAudio, 2 = OpenSL ES, 0 = none (the SettingScreen route label). */
        fun apiRoute(): Int
    }

    /** One hardware DAC presentation-clock sample (the record_dac_clock bridge). */
    data class DacClockSample(
        val framePosition: Long,
        val sampleRate: Int,
        val monotonicNs: Long,
    )

    private const val LIB = "streamify_native_core"

    /** The resolved binding, or null when the library/edges are absent. */
    val binding: Binding? by lazy {
        runCatching {
            System.loadLibrary(LIB)
            JniBinding()
        }.getOrElse {
            android.util.Log.w(TAG, "native bracket not loadable ($LIB): ${it.message}")
            null
        }
    }

    // The raw JNI surface, declared ON THE FILE-LEVEL OBJECT so the
    // JNI symbol mangling is the plain class-name form the parity gate
    // (scripts/jni_parity_check.py) and the JVM both derive:
    // Java_com_streamify_sovereign_player_android_SovereignOboeJni_<fn>.
    // The load happens in the lazy binding resolution below.
    external fun sovereign_oboe_open(audio_api: Int): Int
    external fun sovereign_oboe_close()
    external fun sovereign_oboe_callbacks(): Long
    external fun sovereign_oboe_underrun_frames(): Long
    external fun sovereign_oboe_ring_occupancy(): Long
    external fun sovereign_oboe_dac_clock(frame: LongArray, rate: IntArray, ns: LongArray): Int
    external fun sovereign_oboe_stats_wire(dst: ByteArray, dstBytes: Int): Int
    external fun sovereign_oboe_api_route(): Int

    private class JniBinding : Binding {
        override fun open(api: Int): Int = sovereign_oboe_open(api)

        override fun close() = sovereign_oboe_close()

        override fun callbacks(): Long = sovereign_oboe_callbacks()

        override fun underrunFrames(): Long = sovereign_oboe_underrun_frames()

        override fun ringOccupancy(): Long = sovereign_oboe_ring_occupancy()

        override fun dacClock(): DacClockSample? {
            val frame = LongArray(1)
            val rate = IntArray(1)
            val ns = LongArray(1)
            if (sovereign_oboe_dac_clock(frame, rate, ns) != 1) return null
            return DacClockSample(frame[0], rate[0], ns[0])
        }

        override fun statsWire(): ByteArray? {
            val dst = ByteArray(SINK_STATS_WIRE_BYTES)
            if (sovereign_oboe_stats_wire(dst, SINK_STATS_WIRE_BYTES) != 1) return null
            return dst
        }

        override fun apiRoute(): Int = sovereign_oboe_api_route()
    }

    /** The frozen wire size (sink.rs / streamify_audio_sinks.h). */
    const val SINK_STATS_WIRE_BYTES: Int = 96

    private const val TAG = "SovereignOboeJni"
}
