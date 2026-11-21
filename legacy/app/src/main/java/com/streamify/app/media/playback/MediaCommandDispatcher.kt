package com.streamify.app.media.playback

import com.streamify.app.data.uniffi.MediaCommand
import com.streamify.app.data.uniffi.StreamifyEngineBridge
import com.streamify.app.util.SLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * MediaCommandDispatcher — hardware media-command debounce actor
 * (Phase 3, MP-C03 / MP-DEC-07; directive §C).
 *
 * Bluetooth headsets and car steering-wheel controls emit rapid burst
 * signals: a double-tap-for-skip gesture can arrive as two rapid toggles,
 * and flaky AVRCP links re-deliver the same key press. Dispatching those
 * bursts straight through to the native core would flap the engine's
 * state machine — so every hardware-origin [MediaCommand] funnels through
 * this single actor, which coalesces bursts inside a 50–150ms window
 * (default 100ms) before handing the FINAL command to
 * [StreamifyEngineBridge.dispatchMediaCommand].
 *
 * Policy — trailing-edge debounce over the merged command stream:
 *  • every arrival inside the window defers dispatch and resets the
 *    deadline; the loop dispatches `windowMs` after the LAST arrival;
 *  • the engine state machine is convergent, so a skipped intermediate
 *    command is always subsumed by the final intent (Play,Play → one
 *    Play; a scrub burst → the final Seek);
 *  • [dispatchMediaCommand] is `trySend` on a conflation channel —
 *    callers ride binder/main threads and NEVER block; if the actor is
 *    momentarily busy, only the newest command is retained (exactly the
 *    coalescing the window would have performed anyway).
 *
 * Lifecycle: [PlaybackService] starts the actor when the media session
 * goes live and stops it on teardown; [start] is idempotent and [stop]
 * drains nothing (a dying session's tail command is meaningless).
 *
 * System entrypoint mapping (directive §C.2) — this is the Media3-era
 * translation of the legacy `MediaSessionCompat.Callback` surface:
 * hardware buttons, Assistant voice actions and controller commands all
 * arrive as Player transport invocations on the session's
 * `ForwardingPlayer`, which taps [dispatchMediaCommand]:
 *
 *  • `play()`                      → [MediaCommand.Play]
 *  • `pause()`                     → [MediaCommand.Pause]
 *  • `seekTo(positionMs)`          → [MediaCommand.Seek]
 *  • `seekToNext()`/`NextMediaItem`→ [MediaCommand.Next]
 *  • `seekToPrevious()/…`          → [MediaCommand.Previous]
 *  • `stop()`                      → [MediaCommand.Stop]
 */
object MediaCommandDispatcher {

    private const val TAG = "MediaCommandDispatcher"

    /** Lower bound of the MP-DEC-07 debounce envelope (ms). */
    const val MIN_DEBOUNCE_WINDOW_MS = 50L

    /** Upper bound of the MP-DEC-07 debounce envelope (ms). */
    const val MAX_DEBOUNCE_WINDOW_MS = 150L

    /** Default window: the geometric center of the approved envelope. */
    const val DEFAULT_DEBOUNCE_WINDOW_MS = 100L

    /**
     * Single ingress funnel (directive §C.1): conflation keeps the newest
     * command when the actor is mid-dispatch — bursts never queue up and
     * replay stale intent after the window closes.
     */
    private val mediaCommandChannel = Channel<MediaCommand>(Channel.CONFLATED)

    /** Active debounce window (ms), clamped to the approved envelope. */
    @Volatile
    private var debounceWindowMs = DEFAULT_DEBOUNCE_WINDOW_MS

    /** The running actor loop, when started. */
    @Volatile
    private var consumer: Job? = null

    /**
     * Dispatch sink — swappable seam so the hermetic JVM unit shard
     * observes dispatches without the FFI. Production default lowers onto
     * the bridge's reverse control plane; unwired backends already degrade
     * to typed `EngineUnavailable` failures inside the bridge (never a
     * raw Throwable), and the actor treats dispatch failures as fire-and-
     * forget telemetry, never a loop death.
     */
    @Volatile
    internal var dispatchSink: (MediaCommand) -> Unit = {
        StreamifyEngineBridge.dispatchMediaCommand(it)
            .onFailure { err -> SLog.st(TAG, "media command dispatch failed", err) }
    }

    /** Scopes the actor when the caller supplies none (defensive default). */
    private val fallbackScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Arms the debounce actor. Idempotent: a second [start] while live only
     * re-clamps the window. [windowMs] is coerced into the approved
     * 50–150ms envelope (MP-DEC-07) — an out-of-range value can never
     * silently disable the debounce.
     */
    @Synchronized
    fun start(scope: CoroutineScope = fallbackScope, windowMs: Long = DEFAULT_DEBOUNCE_WINDOW_MS) {
        debounceWindowMs = windowMs.coerceIn(MIN_DEBOUNCE_WINDOW_MS, MAX_DEBOUNCE_WINDOW_MS)
        if (consumer?.isActive == true) return
        consumer = scope.launch { runDebounceLoop() }
        SLog.i(TAG, "armed — window=${debounceWindowMs}ms")
    }

    /** Disarms the actor (session teardown). Safe when not running. Drains
     * the conflation buffer — a dying session's tail command must never
     * replay into the next session's actor. */
    @Synchronized
    fun stop() {
        consumer?.cancel()
        consumer = null
        while (mediaCommandChannel.tryReceive().isSuccess) {
            // Drain: discard the undelivered tail intent with the session.
        }
    }

    /**
     * Non-blocking ingress for hardware-origin media commands
     * (directive §C.1): `trySend` on the conflation channel never suspends
     * and never drops the NEWEST intent.
     */
    fun dispatchMediaCommand(command: MediaCommand) {
        mediaCommandChannel.trySend(command)
    }

    /**
     * The debounce actor: suspends until a command arrives, then absorbs
     * the burst — every further arrival inside [debounceWindowMs] defers
     * the dispatch and resets the deadline. `withTimeoutOrNull` is
     * virtual-time friendly, so the JVM unit shard proves burst semantics
     * deterministically (no sleeps, no flakiness). The loop is UNKILLABLE
     * from a sink failure: dispatch problems are telemetry, never a reason
     * to drop the actor.
     */
    private suspend fun CoroutineScope.runDebounceLoop() {
        while (isActive) {
            var pending = mediaCommandChannel.receive()
            while (true) {
                val next = withTimeoutOrNull(debounceWindowMs) {
                    mediaCommandChannel.receive()
                } ?: break
                pending = next
            }
            runCatching { dispatchSink(pending) }.onFailure { t ->
                SLog.st(TAG, "media command sink failed", t)
            }
        }
    }
}
