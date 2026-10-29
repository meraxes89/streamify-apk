package com.streamify.desktop.shell

import androidx.compose.ui.input.key.Key

/**
 * Phase 5 (MP-UI04 / Beta): the desktop shell's keyboard-shortcut map —
 * pure, headless-testable.
 *
 * Directive contract:
 *  * Space → Play/Pause toggle;
 *  * Left / Right (bare) → seek back / forward one [TransportSteps.SEEK_STEP_MS] step;
 *  * Up / Down (bare) → volume up / down one [TransportSteps.VOLUME_STEP] step;
 *  * Ctrl+Right / Ctrl+Left → track skip forward / backward;
 *  * Ctrl+F → focus the search pane's query field.
 *
 * Dispatch discipline (see DesktopShell): the map is consulted from a
 * BUBBLE-phase `onKeyEvent` at the shell root, so focused text entry
 * consumes its own keys first — typing a space into the search field
 * inserts a character instead of toggling playback, and bare arrows inside
 * the field move the caret instead of seeking. Exactly the window-level
 * behavior a media player should have.
 */
enum class ShellShortcut {
    TOGGLE_PLAY_PAUSE,
    NEXT_TRACK,
    PREVIOUS_TRACK,
    FOCUS_SEARCH,
    SEEK_FORWARD,
    SEEK_BACK,
    VOLUME_UP,
    VOLUME_DOWN,
}

/**
 * Maps one key press (plus modifier state) to a shell shortcut, or null
 * when the combination is not bound. Pure function — pinned by the
 * headless shell smoke test.
 */
fun shortcutFor(
    key: Key,
    ctrlPressed: Boolean,
    metaPressed: Boolean,
): ShellShortcut? {
    val primaryModifier = ctrlPressed || metaPressed
    return when {
        // Bare Space toggles playback — but never when a modifier rides it
        // (Ctrl+Space / Meta+Space belong to the OS and IME layers).
        key == Key.Spacebar && !primaryModifier -> ShellShortcut.TOGGLE_PLAY_PAUSE

        key == Key.DirectionRight && primaryModifier -> ShellShortcut.NEXT_TRACK
        key == Key.DirectionLeft && primaryModifier -> ShellShortcut.PREVIOUS_TRACK

        key == Key.F && primaryModifier -> ShellShortcut.FOCUS_SEARCH

        // Bare arrows: transport nudging (seek / volume). Modifier-riding
        // arrows belong to focus traversal and the OS.
        key == Key.DirectionRight && !primaryModifier -> ShellShortcut.SEEK_FORWARD
        key == Key.DirectionLeft && !primaryModifier -> ShellShortcut.SEEK_BACK
        key == Key.DirectionUp && !primaryModifier -> ShellShortcut.VOLUME_UP
        key == Key.DirectionDown && !primaryModifier -> ShellShortcut.VOLUME_DOWN

        else -> null
    }
}

/**
 * The transport step math behind the seek/volume shortcuts — pure and
 * headless-testable so the directive's "Left/Right = Seek, Up/Down =
 * Volume" contract is pinned by the smoke suite, not by inspection.
 */
object TransportSteps {

    /** One bare-arrow seek step: ±10 s (the common desktop-player nudge). */
    const val SEEK_STEP_MS: Long = 10_000L

    /** One bare-arrow volume step: ±5% of the [0.0, 1.0] output range. */
    const val VOLUME_STEP: Float = 0.05f

    /**
     * The seek target for one nudge from the ENGINE-AUTHORITATIVE position
     * (the pushed snapshot's DAC-derived value — never the
     * presentation-layer smoothed playhead, which exists only for
     * rendering). Null when no position is known: absence is never treated
     * as zero. Clamped to the track bounds when the duration is known.
     */
    fun seekTargetFrom(positionMs: Long?, deltaMs: Long, durationMs: Long? = null): Long? {
        if (positionMs == null) return null
        val unclamped = positionMs + deltaMs
        val lower = 0L
        val upper = durationMs?.takeIf { it > 0L } ?: Long.MAX_VALUE
        return unclamped.coerceIn(lower, upper)
    }

    /**
     * The volume target for one nudge from the engine-authoritative volume,
     * clamped to [0.0, 1.0].
     */
    fun volumeTargetFrom(volume: Float, step: Float): Float =
        (volume + step).coerceIn(0f, 1f)
}
