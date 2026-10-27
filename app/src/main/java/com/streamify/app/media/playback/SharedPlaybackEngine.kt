package com.streamify.app.media.playback

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.streamify.app.data.uniffi.StreamifyEngineBridge
import com.streamify.app.util.SLog
import java.util.concurrent.CopyOnWriteArrayList

/**
 * SharedPlaybackEngine — process-wide holder for the playback engine
 * (Phase 4, Android Auto support; lifecycle hardened in MEDIA-01).
 *
 * The phone UI and the Auto browse service both need a Media3 session over
 * the SAME player so a queue started from the head unit is exactly the
 * queue the phone shows (and vice versa). Media3 explicitly supports one
 * Player instance behind multiple MediaSessions; this holder is the seam
 * where the main [PlaybackService] publishes its engine and
 * [com.streamify.app.auto.StreamifyAutoMediaBrowserService] consumes it.
 *
 * Cold-start path (MEDIA-01): if the main service is not running when Auto
 * binds, the Auto service performs ONE best-effort warm start (background
 * FGS restrictions may decline it silently) and then latches onto
 * [addEngineListener] — the listener fires the moment the engine publishes
 * (or immediately when it already has), so a publication racing the Auto
 * onCreate no longer strands the head unit without a session until the
 * next bind.
 *
 * Withdrawal contract (MEDIA-01): [withdraw] first clears the held
 * reference (identity-guarded — a late callback from a destroyed service
 * must not yank a freshly re-published engine), THEN synchronously
 * notifies [EngineListener]s so live consumers drop their sessions and
 * player references BEFORE the owner releases the underlying player. No
 * consumer can ever hold a released player, and listener callbacks that
 * re-read [player] observe null instead of re-grabbing the dying engine.
 */
object SharedPlaybackEngine {

    private const val TAG = "SharedPlaybackEngine"

    /**
     * Warm-start single-flight window: a startForegroundService request
     * either lands the service onCreate or throws within roughly this
     * budget. A second ensureEngine() call inside the window is a no-op —
     * stacking FGS start intents only churns the system.
     */
    private const val WARM_START_TIMEOUT_MS = 1500L

    /** The forwarding player backing the main app session (seek-safe). */
    @Volatile
    var player: androidx.media3.common.Player? = null
        private set

    private val lock = Any()

    /**
     * Monotonic publication generation — consumers that cached a player
     * together with its generation can detect that the engine they hold
     * has been superseded.
     */
    val generation: Long
        get() = synchronized(lock) { generationCounter }

    private var generationCounter: Long = 0L

    private val listeners = CopyOnWriteArrayList<EngineListener>()

    /**
     * Non-zero while a warm start request is in flight (wall-clock ms).
     * Wall clock instead of SystemClock.elapsedRealtime keeps this holder
     * exercisable in hermetic JVM unit tests (no android.os stubs).
     */
    @Volatile
    private var warmStartInFlightSinceMs: Long = 0L

    /**
     * Engine lifecycle observer. Cold-start consumers (Android Auto,
     * background widgets with direct engine access) latch onto publication
     * instead of polling [player] — registration order versus publication
     * order no longer matters.
     */
    interface EngineListener {
        /**
         * A fresh engine was published and is safe to build sessions on.
         * Called on the publishing thread (the main thread of this process).
         */
        fun onEnginePublished(player: androidx.media3.common.Player)

        /**
         * The engine was withdrawn and is about to be released — drop
         * sessions and player references NOW. Always called AFTER [player]
         * has been cleared.
         */
        fun onEngineWithdrawn()
    }

    /**
     * Registers a lifecycle observer. When an engine is ALREADY published
     * the observer receives [EngineListener.onEnginePublished] immediately
     * (synchronously) — this is the cold-start race shield. Listeners stay
     * registered across withdrawals (the next publication re-fires them)
     * and must be removed via [removeEngineListener] by their owner.
     */
    fun addEngineListener(listener: EngineListener) {
        val current: androidx.media3.common.Player? = synchronized(lock) {
            listeners.add(listener)
            player
        }
        // Fire OUTSIDE the lock — listener code may touch this holder again
        // and must never re-enter the lock.
        current?.let { listener.onEnginePublished(it) }
    }

    fun removeEngineListener(listener: EngineListener) {
        synchronized(lock) { listeners.remove(listener) }
    }

    /** Published by PlaybackService once its session is wired. */
    fun publish(player: androidx.media3.common.Player) {
        val toNotify = synchronized(lock) {
            generationCounter++
            this.player = player
            warmStartInFlightSinceMs = 0L
            listeners.toList()
        }
        for (l in toNotify) {
            runCatching { l.onEnginePublished(player) }
                .onFailure { SLog.st(TAG, "onEnginePublished listener failed", it) }
        }
        // MEDIA-02: a live session arms the Rust core engine — the one-way,
        // idempotent Idle -> Buffering transition from the UFFI-01 contract.
        // Every publish path (main service onCreate, Auto-service warm
        // start) flows through here, so the engine and the Media3 lifecycle
        // can never drift apart. Degraded backends (no cdylib / proxies
        // pending) fail silently inside the bridge.
        StreamifyEngineBridge.initializeEngine()
    }

    fun withdraw(player: androidx.media3.common.Player) {
        // Only the owner may withdraw — a late callback from a destroyed
        // service must not yank a freshly re-published engine.
        val toNotify = synchronized(lock) {
            if (this.player !== player) return
            this.player = null
            warmStartInFlightSinceMs = 0L
            listeners.toList()
        }
        // Notify AFTER the reference is cleared (see class doc): consumers
        // re-reading SharedPlaybackEngine.player inside the callback see
        // null and drop their own sessions before the owner releases the
        // underlying player.
        for (l in toNotify) {
            runCatching { l.onEngineWithdrawn() }
                .onFailure { SLog.st(TAG, "onEngineWithdrawn listener failed", it) }
        }
        // MEDIA-02: the session is over — mirror Idle into the engine
        // bridge telemetry so the session-scoped underrun tally resets for
        // the next publish (Player.release may race listener delivery).
        StreamifyEngineBridge.recordPlaybackState(
            StreamifyEngineBridge.PlaybackState.Idle
        )
    }

    /**
     * Best-effort cold-start of the main playback service. Never throws;
     * when the platform refuses the background FGS start the Auto bind
     * simply retries later. Single-flight (MEDIA-01): concurrent callers
     * inside the [WARM_START_TIMEOUT_MS] window coalesce into one FGS
     * start request instead of stacking intents.
     */
    fun ensureEngine(context: Context) {
        if (player != null) return
        val now = System.currentTimeMillis()
        synchronized(lock) {
            if (player != null) return
            val inFlightSince = warmStartInFlightSinceMs
            if (inFlightSince != 0L && (now - inFlightSince) < WARM_START_TIMEOUT_MS) {
                return
            }
            warmStartInFlightSinceMs = now
        }
        runCatching {
            ContextCompat.startForegroundService(
                context.applicationContext,
                Intent(context.applicationContext, PlaybackService::class.java)
            )
        }.onFailure { t ->
            SLog.st(TAG, "warm start declined", t)
            // Unblock the next attempt — a declined FGS start never lands
            // a publish() that would clear the in-flight stamp.
            synchronized(lock) { warmStartInFlightSinceMs = 0L }
        }
    }
}
