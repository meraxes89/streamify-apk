package com.streamify.shared.model

/**
 * Phase 5 (MP-UI03 / Beta): platform-agnostic presentation models.
 *
 * These types are the SINGLE currency of the shared UI layer: the desktop
 * CMP shell, the Android seam and the shared state holder
 * ([com.streamify.shared.state.PlaybackController]) all speak them, and each
 * platform's [com.streamify.shared.engine.EnginePort] implementation lowers
 * its native engine types onto them. No `uniffi.*`, `android.*` or AWT type
 * ever appears here — that is what makes the same source compile for both
 * the desktop (KGP jvm / CMP) and Android (AGP / androidx Compose) seams.
 */

/**
 * Coarse playback lifecycle, mirrored 1:1 from the UDL `PlaybackState`
 * enum (Idle/Buffering/Playing/Paused/Error) into a UI-stable spelling.
 */
enum class PlaybackStatus {
    IDLE,
    BUFFERING,
    PLAYING,
    PAUSED,
    ERROR,
}

/**
 * Canonical descriptive metadata for one playable track (presentation-side
 * mirror of the UDL `TrackMetadata`).
 *
 * `durationMs` and `artworkUrl` are optional: the engine snapshot carries
 * neither today, so platform adapters populate them only when their catalog
 * surface can answer. Components render graceful position-only / placeholder
 * modes when the fields are absent — never invent data.
 */
data class PresentationTrack(
    val id: String,
    val title: String,
    val artist: String,
    val durationMs: Long? = null,
    val artworkUrl: String? = null,
)

/**
 * Live buffering health snapshot (mirror of the UDL `BufferStats`).
 */
data class BufferHealth(
    val bufferedDurationMs: Long,
    val underrunCount: Long,
    /** Ring fill ratio in [0.0, 1.0]. */
    val fillRatio: Float,
)

/**
 * One hardware DAC presentation clock sample (mirror of the UDL
 * `DacPresentationClock`, MP-DEC-03: the hardware HAL callback is the SOLE
 * clock master — playback position derives strictly from these hardware
 * presentation timestamps, never from wall-clock interpolation).
 */
data class DacClockSample(
    /** Hardware-consumed frame counter (monotonic per device stream). */
    val framePosition: Long,
    /** Device DAC nominal rate in Hz (e.g. 48000). */
    val sampleRateHz: Int,
    /** OS monotonic presentation timestamp in nanoseconds. */
    val monotonicNs: Long,
)

/**
 * Reverse control plane: user / hardware intent lowered onto the engine
 * (mirror of the UDL `MediaCommand` enum; the engine applies its mandated
 * 50-150 ms hardware debounce on identical duplicates).
 */
sealed interface PlayerCommand {
    data object Play : PlayerCommand
    data object Pause : PlayerCommand
    data object TogglePlayPause : PlayerCommand
    data object Next : PlayerCommand
    data object Previous : PlayerCommand
    data class Seek(val positionMs: Long) : PlayerCommand
    data class SetVolume(val volume: Float) : PlayerCommand
    data object Stop : PlayerCommand
}

/**
 * Outcome of dispatching one [PlayerCommand] through the engine port.
 *
 * `DEBOUNCED` mirrors the engine's deliberate swallow of an identical
 * command inside the 50-150 ms hardware debounce window — it is a success
 * path, not an error.
 */
enum class PlayerCommandResult {
    /** The engine accepted and applied the command. */
    APPLIED,

    /** The engine swallowed an identical duplicate inside the debounce window. */
    DEBOUNCED,

    /** The lane is unavailable or the engine rejected the command. */
    FAILED,
}

/**
 * One coalesced engine state snapshot, pushed with a strictly monotonic
 * revision (mirror of the UDL `EngineSnapshot`, MP-DEC-04: push-only — UI
 * shells never poll `get_playback_state`).
 */
data class PlaybackSnapshot(
    /** Monotonic revision; strictly increases across dispatched snapshots. */
    val rev: Long,
    val status: PlaybackStatus,
    /** Output volume scalar in [0.0, 1.0]. */
    val volume: Float,
    val buffer: BufferHealth? = null,
    /** Latest hardware DAC presentation sample; null until the first HAL callback. */
    val clock: DacClockSample? = null,
    /** Position derived from the DAC clock (null while no clock sample exists). */
    val positionMs: Long? = null,
    /** Active track metadata when a catalog surface has supplied it. */
    val currentTrack: PresentationTrack? = null,
    /** Active Jam session name, when the engine holds one. */
    val jamSessionName: String? = null,
) {
    companion object {
        /** Cold-start placeholder before the first push arrives. */
        val IDLE: PlaybackSnapshot = PlaybackSnapshot(
            rev = 0L,
            status = PlaybackStatus.IDLE,
            volume = 0f,
        )
    }
}

/**
 * UI-facing projection of a [PlaybackSnapshot] plus the derived values the
 * components actually render (progress fraction, formatted timestamps).
 * Derivation is pure and total: absent clocks / durations degrade to
 * position-only or indeterminate rendering, never to invented numbers.
 */
data class PlaybackUiState(
    val snapshot: PlaybackSnapshot,
) {
    val status: PlaybackStatus get() = snapshot.status
    val volume: Float get() = snapshot.volume
    val isPlaying: Boolean get() = status == PlaybackStatus.PLAYING
    val isBuffering: Boolean get() = status == PlaybackStatus.BUFFERING
    val positionMs: Long? get() = snapshot.positionMs
    val durationMs: Long? get() = snapshot.currentTrack?.durationMs

    /** Track progress in [0.0, 1.0]; null when position or duration is unknown. */
    val progressFraction: Float?
        get() = com.streamify.shared.model.progressFraction(positionMs, durationMs)

    val currentTrack: PresentationTrack? get() = snapshot.currentTrack

    companion object {
        val IDLE: PlaybackUiState = PlaybackUiState(PlaybackSnapshot.IDLE)

        fun from(snapshot: PlaybackSnapshot): PlaybackUiState =
            PlaybackUiState(snapshot)
    }
}

/**
 * MP-CUT01 dogfood telemetry presentation (Android cutover observability).
 *
 * Pure presentation state fed by the app's [com.streamify.shared.android]
 * panel host from the real [com.streamify.app.media.audio] collectors —
 * the panel itself never touches the collectors, so it renders identically
 * on any platform and stays JVM-unit-testable.
 */
data class DogfoodTelemetryState(
    /** Effective route this playback-stack generation resolved. */
    val route: String,
    /** Human-readable resolution reason (the ladder's decision trace). */
    val routeReason: String,
    /** Feeder underrun rate as a percentage of total bursts ([0..100]). */
    val underrunRatePercent: Float?,
    /**
     * DAC drift between successive hardware clock samples in ppm
     * (positive = device DAC runs fast vs its nominal rate).
     */
    val dacDriftPpm: Float?,
    /** Cold-start latency: route resolution -> first DAC clock sample, in ms. */
    val coldStartMs: Long?,
    /** Total frames pulled through the native ring so far. */
    val framesPulled: Long,
    /** Hardware DAC clock samples ingested so far. */
    val dacClockSamples: Long,
    /** Whether the Rust pull pipeline is currently live. */
    val pipelineLive: Boolean,
    /** Count of automatic zero-crash fallbacks to the legacy Media3 sink. */
    val legacyFallbacks: Long,
)
