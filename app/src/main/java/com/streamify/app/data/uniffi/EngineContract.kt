package com.streamify.app.data.uniffi

/**
 * Phase 3 push-contract projections (Beta subtree, MP-C01/C02/C03).
 *
 * The Rust-side contract expansion (Alpha's `streamify.udl` ledger items
 * MP-C01–C05) exposes, over the frozen Phase 2 surface:
 *
 *  • [DacPresentationClock] — the hardware DAC presentation timestamp
 *    triple (`dac_frame_position`, `sample_rate`, `monotonic_ns`);
 *  • [EngineEventListener] — the non-blocking push callback carrying
 *    coalesced [EngineSnapshot]s stamped with a monotonic `rev`;
 *  • [MediaCommand] — the reverse control plane
 *    (`Play | Pause | TogglePlayPause | Next | Previous | Seek | SetVolume
 *    | Stop`) with 50–150ms hardware debounce.
 *
 * This file is the app-side mirror of that surface, exactly like
 * [StreamifyEngineBridge.PlaybackState] / [StreamifyEngineBridge.BufferStats]
 * already mirror the Phase 2 UDL types: the generated `uniffi.streamify.*`
 * classes are NOT on the hermetic JVM compile classpath (the unit shard runs
 * with `-PskipExternalNativeBuild=true`, so `generateUniffiKotlinBindings`
 * is SKIPPED), and `NativeEngineBackend` lowers the generated objects onto
 * these projections reflectively — one wiring point, zero compile-time
 * dependencies. When Alpha's bindgen output ships, real builds light the
 * push path up with zero changes above this projection.
 */

/**
 * Hardware DAC presentation timestamp (MP-C01, MP-DEC-03).
 *
 * The hardware audio callback is the SOLE clock master: playback
 * position, seek confirmation and visualization sync derive strictly
 * from these hardware presentation timestamps — never from wall-clock
 * (`System.currentTimeMillis()`) interpolation. Unsigned widths mirror
 * the planned UDL exactly; no information loss across the seam.
 *
 * Acceptance criterion (Phase 4a): `<5ms` error vs a loopback cable
 * reference on each platform HAL.
 */
data class DacPresentationClock(
    /** Hardware-consumed frame counter (monotonic per stream). */
    val dacFramePosition: ULong,
    /** Device DAC nominal rate (Hz) — the frame→time divisor. */
    val sampleRate: UInt,
    /** OS monotonic presentation timestamp (nanoseconds). */
    val monotonicNs: ULong,
)

/**
 * One coalesced engine state snapshot pushed over [EngineEventListener]
 * (MP-C02, MP-DEC-04).
 *
 * `rev` is the engine-side monotonic revision: every emitted snapshot
 * carries a strictly increasing `rev` (the counter starts at 1 — rev 0
 * is never a valid emission), so a consumer that observes
 * `rev <= lastSeenRev` can drop the event as out-of-order. That
 * guarantees deterministic state recovery after device sleep and
 * background lifecycle transitions: queued stale snapshots delivered
 * late never regress the UI.
 */
data class EngineSnapshot(
    /** Monotonic revision — strictly increasing per engine instance. */
    val rev: ULong,
    /** Engine-side playback lifecycle state. */
    val playbackState: StreamifyEngineBridge.PlaybackState,
    /** Engine-authoritative buffering health. */
    val bufferStats: StreamifyEngineBridge.BufferStats,
    /** Latest hardware presentation clock, when the engine emits one. */
    val dacClock: DacPresentationClock? = null,
)

/**
 * Reverse control plane: one user-intent media command (MP-C03,
 * MP-DEC-07). Hardware origins (Bluetooth headsets, car steering-wheel
 * controls, Assistant voice actions) funnel through
 * [com.streamify.app.media.playback.MediaCommandDispatcher]'s debounce
 * before reaching the native core; the variants mirror the planned UDL
 * `MediaCommandSource` enum one-for-one.
 */
sealed interface MediaCommand {
    /** Start (or resume) playback. */
    data object Play : MediaCommand

    /** Suspend playback at the current position. */
    data object Pause : MediaCommand

    /** Play/pause as one atomic intent (single-button remotes). */
    data object TogglePlayPause : MediaCommand

    /** Advance to the next queue item. */
    data object Next : MediaCommand

    /** Return to the previous queue item. */
    data object Previous : MediaCommand

    /** Jump to an absolute position within the current item. */
    data class Seek(val positionMs: Long) : MediaCommand

    /** Apply an output gain scalar, clamped to `[0, 1]`. */
    data class SetVolume(val volume: Float) : MediaCommand

    /** Terminate the session and release resources. */
    data object Stop : MediaCommand
}

/**
 * App-side mirror of the UDL `EngineEventListener` callback interface
 * (MP-C02): the Rust core dispatches coalesced [EngineSnapshot]s on a
 * dedicated, non-real-time worker thread; the implementation must be
 * cheap and never block (the push thread belongs to the engine).
 */
fun interface EngineEventListener {
    /** One coalesced engine state snapshot (monotonic [EngineSnapshot.rev]). */
    fun onEngineEvent(snapshot: EngineSnapshot)
}

/**
 * Production listener implementation (directive §A.1): a thin trampoline
 * delegating each pushed snapshot onto [onSnapshot]. The backend lowers
 * the *generated* `uniffi.streamify.EngineEventListener` interface onto
 * this type reflectively (dynamic proxy) so the hermetic JVM shard
 * never references the generated classes at compile time.
 */
class EngineEventListenerImpl(
    private val onSnapshot: (EngineSnapshot) -> Unit,
) : EngineEventListener {
    override fun onEngineEvent(snapshot: EngineSnapshot) {
        onSnapshot(snapshot)
    }
}

/**
 * Interpolation-free UI position calculator over [DacPresentationClock]
 * (directive §D.2, MP-DEC-03 — hardware clock as sole master).
 *
 * Phase 3 scaffolding: Media3 remains the interim position authority
 * while the Rust audio pipeline rides behind the Phase 5
 * `USE_RUST_AUDIO` feature flag — but every new position consumer
 * derives through this calculator so the Phase 4a cutover swaps the
 * *source* (AAudio DAC callbacks) without touching any UI math.
 *
 * Pure functions over value types: JVM-unit-testable with zero mocks.
 */
object PlaybackPositionCalculator {

    /**
     * Derives the playback position from the hardware presentation
     * clock: consumed frames divided by the device DAC nominal rate.
     *
     *  • a zero/absent sample rate yields 0 (no division by zero, no
     *    fabricated progress);
     *  • the frame→millisecond product saturates at [Long.MAX_VALUE] —
     *    a u64 frame counter can never wrap the result into a negative
     *    lie;
     *  • NO wall-clock interpolation: the value is exact as of
     *    [DacPresentationClock.monotonicNs], never extrapolated past
     *    it.
     */
    fun calculatePlaybackPositionMs(clock: DacPresentationClock): Long {
        if (clock.sampleRate == 0u) return 0L
        val frames = clock.dacFramePosition
        // Saturation guard BEFORE the product: frames * 1000 overflows
        // u64 only past ~1.8e16 frames (≈584k years of 48kHz audio) —
        // saturate instead of wrapping into a negative lie.
        if (frames > ULong.MAX_VALUE / 1000uL) return Long.MAX_VALUE
        val positionMs = (frames * 1000uL) / clock.sampleRate.toULong()
        return if (positionMs > Long.MAX_VALUE.toULong()) Long.MAX_VALUE else positionMs.toLong()
    }

    /**
     * Seekbar fraction in `[0, 1]` for the given track duration; 0 when
     * the duration is unknown. Clamped — a clock momentarily past the
     * nominal duration (final drain frames) never overshoots the bar.
     */
    fun positionFraction(clock: DacPresentationClock, durationMs: Long): Float {
        if (durationMs <= 0L) return 0f
        val positionMs = calculatePlaybackPositionMs(clock)
        return (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
    }
}
