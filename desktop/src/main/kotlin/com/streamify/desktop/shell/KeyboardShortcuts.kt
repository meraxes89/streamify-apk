package com.streamify.desktop.shell

import androidx.compose.ui.input.key.Key

/**
 * Phase 5 (MP-UI04 / Beta): the desktop shell's keyboard-shortcut map —
 * pure, headless-testable.
 *
 * Directive contract:
 *  * Space → Play/Pause toggle;
 *  * Ctrl+Right / Ctrl+Left → track skip forward / backward;
 *  * Ctrl+F → focus the search pane's query field.
 *
 * Dispatch discipline (see DesktopShell): the map is consulted from a
 * BUBBLE-phase `onKeyEvent` at the shell root, so focused text entry
 * consumes its own keys first — typing a space into the search field
 * inserts a character instead of toggling playback, and Ctrl+Left inside
 * the field moves the caret instead of skipping tracks. Exactly the
 * window-level behavior a media player should have.
 */
enum class ShellShortcut {
    TOGGLE_PLAY_PAUSE,
    NEXT_TRACK,
    PREVIOUS_TRACK,
    FOCUS_SEARCH,
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

        else -> null
    }
}
