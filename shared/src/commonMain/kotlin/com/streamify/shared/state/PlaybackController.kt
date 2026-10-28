package com.streamify.shared.state

import com.streamify.shared.engine.EnginePort
import com.streamify.shared.model.PlaybackSnapshot
import com.streamify.shared.model.PlaybackUiState
import com.streamify.shared.model.PlayerCommand
import com.streamify.shared.model.PlayerCommandResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Phase 5 (MP-UI03 / Beta): the platform-independent playback state holder —
 * the shared "ViewModel" of the multiplatform presentation layer.
 *
 * A pure Kotlin class over [EnginePort]: it collects the port's push flow
 * (MP-DEC-04 — zero polling; the engine decides when state changes), holds
 * the latest [PlaybackUiState] behind a [StateFlow] for Compose collection,
 * and dispatches user intent as fire-and-forget [PlayerCommand]s on its own
 * scope so UI event handlers never suspend.
 *
 * Platform lifecycle is deliberately NOT modeled here: the desktop shell
 * constructs it once per application run and closes it on exit; an Android
 * host would scope it to a service/ViewModel lifetime (the app's own
 * [com.streamify.app.viewmodel.PlayerViewModel] keeps its Media3-native
 * counterpart until the MP-CUT01 dogfood window promotes this one).
 *
 * Rev discipline: the controller keeps the newest snapshot unconditionally
 * (the engine guarantees strictly monotonic revisions per instance), and
 * publishes the derived [PlaybackUiState] only on change-relevant updates.
 */
class PlaybackController(
    private val port: EnginePort,
    private val scope: CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {

    private val _state = MutableStateFlow(PlaybackUiState.IDLE)
    val state: StateFlow<PlaybackUiState> = _state.asStateFlow()

    init {
        scope.launch {
            // Cold-start hydration first (exactly one sanctioned one-shot
            // read), then the push flow takes over.
            port.currentSnapshot()?.let { seed ->
                _state.value = PlaybackUiState.from(seed)
            }
        }
        scope.launch {
            port.snapshots.collect { snapshot ->
                _state.value = PlaybackUiState.from(snapshot)
            }
        }
    }

    /** Latest observed snapshot revision (0 before the first push). */
    val lastRevision: Long get() = _state.value.snapshot.rev

    /**
     * Dispatches user intent to the engine (fire-and-forget): the call
     * returns immediately; the resulting state arrives via the push flow.
     * The engine's 50-150 ms hardware debounce applies to rapid duplicates
     * (e.g. double space-bar presses) — by design, not an error.
     */
    fun send(command: PlayerCommand) {
        scope.launch { port.send(command) }
    }

    /**
     * Creates the engine's local Jam session mirror; the session name (or
     * null on rejection) surfaces through the next pushed snapshot.
     */
    fun createJamSession(sessionName: String) {
        scope.launch { port.createJamSession(sessionName) }
    }

    /**
     * Convenience transport wrappers — the exact intent vocabulary of the
     * shared components (MiniPlayerBar / VolumeSlider / keyboard shortcuts).
     */
    fun togglePlayPause() = send(PlayerCommand.TogglePlayPause)
    fun next() = send(PlayerCommand.Next)
    fun previous() = send(PlayerCommand.Previous)
    fun seekTo(positionMs: Long) = send(PlayerCommand.Seek(positionMs))
    fun setVolume(volume: Float) =
        send(PlayerCommand.SetVolume(volume.coerceIn(0f, 1f)))

    /**
     * Issues one command and awaits its result — the test/diagnostics path
     * ([com.streamify.shared.state.PlaybackController] UI paths use the
     * fire-and-forget variants above).
     */
    suspend fun sendAndAwait(command: PlayerCommand): PlayerCommandResult =
        port.send(command)

    /** Stops the bridge collection and releases the scope (idempotent). */
    fun close() {
        scope.cancel()
        port.close()
    }

    /** Direct snapshot injection for hosts that already hold engine state. */
    fun onSnapshot(snapshot: PlaybackSnapshot) {
        _state.value = PlaybackUiState.from(snapshot)
    }
}
