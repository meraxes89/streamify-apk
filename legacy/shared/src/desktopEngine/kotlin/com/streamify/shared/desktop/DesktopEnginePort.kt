package com.streamify.shared.desktop

import com.streamify.shared.engine.EnginePort
import com.streamify.shared.model.BufferHealth
import com.streamify.shared.model.DacClockSample
import com.streamify.shared.model.PlaybackSnapshot
import com.streamify.shared.model.PlaybackStatus
import com.streamify.shared.model.PlayerCommand
import com.streamify.shared.model.PlayerCommandResult
import com.streamify.shared.model.positionMsFromDacClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import uniffi.streamify.DacPresentationClock
import uniffi.streamify.EngineEventListener
import uniffi.streamify.EngineSnapshot
import uniffi.streamify.JamSessionHandle
import uniffi.streamify.MediaCommand
import uniffi.streamify.PlaybackState
import uniffi.streamify.StreamifyEngine

/**
 * Phase 5 (MP-UI03/MP-UI04 / Beta): the desktop implementation of the shared
 * [EnginePort] seam over the JNA-mode UniFFI engine lane (MP-DEC-02,
 * `streamify-jvm`).
 *
 * This is the single file of the shared tree that speaks `uniffi.streamify`
 * directly — which is why it lives in `src/desktopEngine` (attached to the
 * desktop target ONLY when the binding lane is active; hermetic hosts with
 * -Pstreamify.skipRust=true never compile it). Everything above the seam —
 * theme, components, [com.streamify.shared.state.PlaybackController] —
 * stays pure platform-agnostic Compose.
 *
 * Threading contract (UDL, Phase 3 MP-C02 / MP-DEC-04):
 *  * `on_engine_event` arrives on the engine's DEDICATED dispatch thread —
 *    never the real-time audio thread, never the registration thread. The
 *    listener body here is a cheap pure mapping into a [StateFlow] update
 *    (zero allocation beyond the snapshot value; never throws — the engine
 *    panic-shields the boundary, and a mapping that stays total cannot trip
 *    it).
 *  * Engine methods are thread-safe by construction (Arc + RwLock/atomics
 *    per the UDL), so [send] may run on any coroutine dispatcher.
 *  * NO POLLING: the push flow is the only state source; the one-shot
 *    [currentSnapshot] exists solely for cold-start hydration, exactly as
 *    the UDL sanctions.
 *
 * The engine handle's native lifetime is managed by the uniffi object
 * cleaner (same policy as the Phase 4b JNA smoke suite — no explicit
 * destroy on a long-lived application engine). [close] is therefore a
 * documented no-op for the bridge itself.
 */
class DesktopEnginePort(
    private val engine: StreamifyEngine = StreamifyEngine(),
) : EnginePort {

    private val bridgeListener = object : EngineEventListener {
        override fun onEngineEvent(snapshot: EngineSnapshot) {
            // Engine dispatch thread: total, allocation-light mapping only.
            _snapshots.value = mapSnapshot(snapshot)
        }
    }

    private val _snapshots = MutableStateFlow(PlaybackSnapshot.IDLE)

    override val snapshots: StateFlow<PlaybackSnapshot> = _snapshots.asStateFlow()

    init {
        // Arm the engine: verify runtime invariants, advance the one-way
        // Idle -> Buffering transition (idempotent per the UDL).
        engine.initialize()
        // Listener registration itself bumps the revision: the dispatcher
        // pushes a full snapshot to the fresh listener on its next pass,
        // which seeds the StateFlow without any one-shot read.
        engine.addEventListener(bridgeListener)
    }

    override suspend fun send(command: PlayerCommand): PlayerCommandResult {
        return try {
            when (command) {
                // UI-driven gain goes through the direct set_volume API (the
                // reverse-plane SetVolume command exists for HARDWARE media
                // keys and carries the 50-150 ms debounce — wrong tool for a
                // slider drag).
                is PlayerCommand.SetVolume -> {
                    engine.setVolume(command.volume.coerceIn(0f, 1f))
                    PlayerCommandResult.APPLIED
                }
                else -> {
                    val applied = engine.onMediaCommand(command.toUniffi())
                    if (applied) PlayerCommandResult.APPLIED
                    else PlayerCommandResult.DEBOUNCED
                }
            }
        } catch (_: uniffi.streamify.EngineException) {
            // Failures across the seam degrade to a failed dispatch — never
            // an exception into UI code (MP-C06 panic shield catches Rust
            // panics on this path too).
            PlayerCommandResult.FAILED
        }
    }

    override suspend fun currentSnapshot(): PlaybackSnapshot? = try {
        mapSnapshot(engine.getEngineSnapshot())
    } catch (_: uniffi.streamify.EngineException) {
        null
    }

    override suspend fun createJamSession(sessionName: String): String? = try {
        val handle: JamSessionHandle = engine.createJamSession(sessionName)
        handle.sessionName
    } catch (_: uniffi.streamify.EngineException) {
        null
    }

    override suspend fun jamStateHash(): String? = try {
        engine.getJamStateHash()
    } catch (_: uniffi.streamify.EngineException) {
        null
    }

    override fun close() {
        // NOTE: the bridge is a listener -> StateFlow edge with no bridging
        // coroutines, so there is nothing to cancel. The UDL surface has
        // add_event_listener only — no removal — and the engine object's
        // native lifetime follows the uniffi cleaner. An
        // application-lifetime engine therefore leaks nothing on close; a
        // shorter-lived port must be constructed with its own engine
        // instance (the default parameter does exactly that).
    }

    // ── UDL -> shared presentation mapping (total by construction) ──────────

    private fun mapSnapshot(snapshot: EngineSnapshot): PlaybackSnapshot {
        val clock: DacClockSample? = snapshot.clock?.toSharedClock()
        return PlaybackSnapshot(
            rev = snapshot.rev.toLong(),
            status = snapshot.state.toSharedStatus(),
            volume = snapshot.volume.coerceIn(0f, 1f),
            buffer = BufferHealth(
                bufferedDurationMs = snapshot.bufferStats.bufferedDurationMs.toLong(),
                underrunCount = snapshot.bufferStats.underrunCount.toLong(),
                fillRatio = snapshot.bufferStats.fillRatio.coerceIn(0f, 1f),
            ),
            clock = clock,
            // MP-DEC-03: position derives strictly from the hardware DAC
            // presentation counters — never a wall clock.
            positionMs = clock?.let { positionMsFromDacClock(it) },
        )
    }

    private fun DacPresentationClock.toSharedClock(): DacClockSample =
        DacClockSample(
            framePosition = dacFramePosition.toLong(),
            sampleRateHz = sampleRate.toInt(),
            monotonicNs = monotonicNs.toLong(),
        )

    private fun PlaybackState.toSharedStatus(): PlaybackStatus = when (this) {
        PlaybackState.IDLE -> PlaybackStatus.IDLE
        PlaybackState.BUFFERING -> PlaybackStatus.BUFFERING
        PlaybackState.PLAYING -> PlaybackStatus.PLAYING
        PlaybackState.PAUSED -> PlaybackStatus.PAUSED
        PlaybackState.ERROR -> PlaybackStatus.ERROR
    }

    private fun PlayerCommand.toUniffi(): MediaCommand = when (this) {
        PlayerCommand.Play -> MediaCommand.Play
        PlayerCommand.Pause -> MediaCommand.Pause
        PlayerCommand.TogglePlayPause -> MediaCommand.TogglePlayPause
        PlayerCommand.Next -> MediaCommand.Next
        PlayerCommand.Previous -> MediaCommand.Previous
        is PlayerCommand.Seek -> MediaCommand.Seek(positionMs.toULong())
        is PlayerCommand.SetVolume -> MediaCommand.SetVolume(volume.coerceIn(0f, 1f))
        PlayerCommand.Stop -> MediaCommand.Stop
    }
}

/**
 * Factory entry point used reflectively by the desktop shell's
 * [com.streamify.desktop.engine.EnginePortLoader] — the shell's source set
 * stays free of `uniffi.*` references so the whole :desktop module compiles
 * on toolchain-less hosts (the Phase 4b hermetic contract, preserved
 * structurally in Phase 5).
 */
object DesktopEnginePortFactory {
    /** Constructs and arms a live desktop engine port, or null on failure. */
    fun create(): DesktopEnginePort? = try {
        DesktopEnginePort()
    } catch (_: Throwable) {
        // Library load failure, ABI mismatch, initialization error — the
        // shell degrades to the engine-unavailable pane, never a crash.
        null
    }
}
