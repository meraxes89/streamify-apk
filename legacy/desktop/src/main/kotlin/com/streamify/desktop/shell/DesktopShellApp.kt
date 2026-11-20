package com.streamify.desktop.shell

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.window.ApplicationScope
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.rememberWindowState
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.streamify.desktop.engine.EnginePortLoader
import com.streamify.shared.state.PlaybackController
import com.streamify.shared.theme.StreamifyTheme

/**
 * Phase 5 (MP-UI04 / Beta): the desktop composition root.
 *
 * Assembles the shell's four directive surfaces:
 *
 *  1. **Window frame** — a native-feeling UNDECORATED window with the
 *     custom [WindowFrame] title bar (minimize / maximize-restore / close)
 *     and the shared theme wrapping the adaptive shell.
 *  2. **System tray** — brand icon + menu (Play/Pause, Next, Previous,
 *     Mute, Show/Hide, Quit); see [StreamifySystemTray].
 *  3. **Engine lane** — [EnginePortLoader] constructs the JNA-mode engine
 *     gateway once per application run; on failure (hermetic host, missing
 *     cdylib) the shell renders the engine-unavailable pane and the tray
 *     stays off (chrome without playback control would be a lie).
 *  4. **Window visibility** — the tray's Show/Hide action toggles the AWT
 *     window's visibility; the window keeps composing while hidden so the
 *     tray menu and the push flow stay live.
 *
 * Window-handle access: the Window content receiver is a FrameWindowScope,
 * whose `window` property exposes the live [ComposeWindow] directly — no
 * platform-view indirection. The handle is captured into a remembered
 * ref for the tray's actions (the tray composes outside the window).
 */
@Composable
fun ApplicationScope.StreamifyDesktopApp() {
    val windowState: WindowState = rememberWindowState(
        size = DpSize(1280.dp, 820.dp),
        position = WindowPosition(Alignment.Center),
    )

    // Engine lane: one port + one controller per application run. The
    // controller's collector scope rides Dispatchers.Default; the
    // application lifetime is its lifetime.
    val enginePort = remember { EnginePortLoader.load() }
    val controller = remember(enginePort) { enginePort?.let { PlaybackController(it) } }

    val appIcon = remember { AppIcon.create() }

    // The AWT window handle, captured from the window content for the
    // frame's minimize button and the tray's show/hide action.
    var composeWindow by remember { mutableStateOf<ComposeWindow?>(null) }

    // Tray mute round-trip bookkeeping: the last audible engine volume,
    // restored on unmute (engine-authoritative — restored via command and
    // repainted by the next push, never local echo).
    var lastAudibleVolume by remember { mutableStateOf(0.8f) }
    val uiVolume = controller?.state?.collectAsState()?.value?.volume ?: 0f
    if (uiVolume > 0f) lastAudibleVolume = uiVolume
    val isMuted = uiVolume <= 0f

    fun showHideWindow() {
        val window = composeWindow ?: return
        if (window.isVisible) {
            window.isVisible = false
        } else {
            window.isVisible = true
            window.toFront()
            window.requestFocus()
        }
    }

    Window(
        title = "Streamify",
        undecorated = true,
        state = windowState,
        icon = appIcon,
        onCloseRequest = ::exitApplication,
    ) {
        // FrameWindowScope.window IS the live ComposeWindow — capture it
        // for the tray (which composes outside this scope).
        val window = this.window
        SideEffect { composeWindow = window }

        StreamifyTheme {
            DesktopShell(
                controller = controller,
                windowState = windowState,
                onMinimize = { window.isMinimized = true },
                onCloseRequest = { exitApplication() },
            )
        }
    }

    // System tray — only with a live engine lane.
    if (controller != null) {
        StreamifySystemTray(
            icon = appIcon,
            controller = controller,
            isMuted = isMuted,
            onToggleMute = {
                if (isMuted) {
                    controller.setVolume(lastAudibleVolume.coerceIn(0.05f, 1f))
                } else {
                    controller.setVolume(0f)
                }
            },
            onShowHideWindow = ::showHideWindow,
            onQuit = { exitApplication() },
        )
    }
}
