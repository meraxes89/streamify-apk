package com.streamify.desktop.shell

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.painter.Painter
// Wildcard on purpose: the tray/menu DSL (Tray, MenuScope's
// MenuItem/CheckboxItem/MenuSeparator items) resolves uniformly whether the
// item builders are MenuScope members or package-level extensions in this
// Compose Desktop line.
import androidx.compose.ui.window.*
import com.streamify.shared.state.PlaybackController

/**
 * Phase 5 (MP-UI04 / Beta): the system tray surface.
 *
 * Directive contract — a tray icon with menu entries for Play/Pause, Next,
 * Previous, Mute, Show/Hide Window and Quit. Every transport action routes
 * through the shared [PlaybackController] (the same command plane as the
 * in-app transport and the keyboard shortcuts — one source of intent), so
 * tray clicks and window clicks can never disagree.
 *
 * Mute semantics: a checkbox bound to the engine-authoritative volume from
 * the push flow (volume <= 0 = muted); toggling dispatches a SetVolume
 * command, and the NEXT pushed snapshot repaints the checkbox — round-trip
 * discipline, not local echo. The last non-zero volume is remembered in
 * the shell and restored on unmute.
 *
 * The tray lives only when the engine lane is live (a tray without
 * playback control is chrome without function on a hermetic host).
 */
@Composable
fun ApplicationScope.StreamifySystemTray(
    icon: Painter,
    controller: PlaybackController,
    isMuted: Boolean,
    onToggleMute: () -> Unit,
    onShowHideWindow: () -> Unit,
    onQuit: () -> Unit,
) {
    Tray(
        icon = icon,
        tooltip = "Streamify — audiophile client",
        onAction = onShowHideWindow,
    ) {
        Item("Play / Pause") { controller.togglePlayPause() }
        Item("Next") { controller.next() }
        Item("Previous") { controller.previous() }
        Separator()
        CheckboxItem("Muted", checked = isMuted) { onToggleMute() }
        Separator()
        Item("Show / Hide window") { onShowHideWindow() }
        Item("Quit") { onQuit() }
    }
}
