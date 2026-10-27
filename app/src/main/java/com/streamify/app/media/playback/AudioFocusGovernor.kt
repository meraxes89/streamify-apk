package com.streamify.app.media.playback

/**
 * AudioFocusGovernor — the audio-focus decision core (strict architecture
 * firewall, Phase 4).
 *
 * Media3's built-in focus handling (`setAudioAttributes(attrs, true)`)
 * only ever PAUSES on permanent AUDIOFOCUS_LOSS — the session stays alive,
 * the notification stays up, the foreground service keeps zombie-idling.
 * The governor takes explicit focus ownership and guarantees the strict
 * teardown contract instead:
 *
 *   AUDIOFOCUS_LOSS (permanent)  → [Action.TeardownPlayback] +
 *     [Action.AbandonFocus]: the caller must transition the player to
 *     STOPPED atomically (one synchronized pause+stop+Idle-record), leave
 *     foreground state cleanly, and stop the service — no intermediate
 *     state is observable, nothing leaks.
 *   AUDIOFOCUS_LOSS_TRANSIENT    → [Action.Pause]; focus is RETAINED so
 *     the position survives; GAIN resumes.
 *   AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK → [Action.Duck] to 20% gain;
 *     GAIN restores.
 *   AUDIOFOCUS_GAIN              → [Action.Unduck] / [Action.Resume] as
 *     the retained state requires.
 *
 * Pure Kotlin, zero Android imports — the full transition table is
 * unit-testable on the JVM shard; [PlaybackService] only EXECUTES the
 * actions against ExoPlayer, the audio manager and its own lifecycle.
 */
internal class AudioFocusGovernor {

    /** Commands the service executes; the governor owns ONLY the decisions. */
    sealed class Action {
        /** Ask the system for focus (playback is starting). */
        object RequestFocus : Action()

        /** Give focus back (playback ended without the governor's involvement). */
        object AbandonFocus : Action()

        /** Lower output gain (a transient loss that permits ducking). */
        data class Duck(val gain: Float) : Action()

        /** Restore output gain after a duck. */
        object Unduck : Action()

        /** Pause without losing position (transient loss). */
        object Pause : Action()

        /** Resume after a transient-loss pause (focus regained). */
        object Resume : Action()

        /**
         * PERMANENT loss: stop the player, record the terminal Idle state,
         * leave foreground, stop the service — the atomic teardown.
         */
        object TeardownPlayback : Action()
    }

    private var focusHeld = false
    private var ducked = false
    private var pausedByTransientLoss = false
    private var tornDown = false

    /** True while the governor itself paused playback (transient loss). */
    fun isTransientPauseActive(): Boolean = pausedByTransientLoss

    /**
     * Playback is starting — request focus. Idempotent; a start after a
     * permanent-loss teardown re-arms the governor (the user explicitly
     * played again, so a fresh focus request is legitimate).
     */
    fun onPlaybackStarted(): List<Action> {
        if (tornDown) {
            tornDown = false
            ducked = false
            pausedByTransientLoss = false
        }
        if (focusHeld) return emptyList()
        focusHeld = true
        return listOf(Action.RequestFocus)
    }

    /**
     * Playback ended without the governor's involvement (user pause, idle,
     * end of queue). Focus is RETAINED across a transient-loss pause or an
     * active duck — the GAIN that ends them must still reach us.
     */
    fun onPlaybackStopped(): List<Action> {
        if (!focusHeld || ducked || pausedByTransientLoss) return emptyList()
        focusHeld = false
        return listOf(Action.AbandonFocus)
    }

    /** One focus event → the actions to execute (may be empty). */
    fun onFocusChange(focusChange: Int): List<Action> {
        if (tornDown) return emptyList()
        return when (focusChange) {
            FOCUS_LOSS -> {
                tornDown = true
                focusHeld = false
                ducked = false
                pausedByTransientLoss = false
                listOf(Action.TeardownPlayback, Action.AbandonFocus)
            }
            FOCUS_LOSS_TRANSIENT -> {
                if (pausedByTransientLoss) {
                    emptyList()
                } else {
                    pausedByTransientLoss = true
                    listOf(Action.Pause)
                }
            }
            FOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                if (ducked) {
                    emptyList()
                } else {
                    ducked = true
                    listOf(Action.Duck(DUCK_GAIN))
                }
            }
            FOCUS_GAIN -> {
                val actions = ArrayList<Action>(2)
                if (ducked) {
                    ducked = false
                    actions += Action.Unduck
                }
                if (pausedByTransientLoss) {
                    pausedByTransientLoss = false
                    actions += Action.Resume
                }
                actions
            }
            else -> emptyList()
        }
    }

    companion object {
        // AudioManager focus constants mirrored — the governor stays
        // Android-free so the JVM shard can drive the whole table.
        const val FOCUS_GAIN = 1
        const val FOCUS_LOSS = -1
        const val FOCUS_LOSS_TRANSIENT = -2
        const val FOCUS_LOSS_TRANSIENT_CAN_DUCK = -3

        /** Output gain while ducked under a transient loss. */
        const val DUCK_GAIN = 0.2f
    }
}
