package com.streamify.desktop.shell

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.unit.dp
import com.streamify.shared.model.DacClockSample
import com.streamify.shared.model.PlaybackSnapshot
import com.streamify.shared.model.PlaybackStatus
import com.streamify.shared.model.PlayerCommand
import com.streamify.shared.state.PlaybackController
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Phase 5 (MP-UI04 / Beta): HEADLESS desktop UI smoke suite.
 *
 * The directive's CI contract ("a headless test step executing desktop UI
 * smoke tests with CMP") lands here: the shell's pure decision machinery —
 * adaptive rail breakpoints, keyboard-shortcut mapping, engine-port-backed
 * controller wiring — is verified on the JVM without opening a window,
 * while the Compose-level compilation of the shared components is proven
 * by the :shared/:desktop compilation itself in the same CI step.
 *
 * Runs on every `:desktop:test` invocation (cargo-backed CI legs) and
 * stays engine-free: FakeEnginePort stands in for the JNA lane, so the
 * suite is deterministic on both Linux and Windows runners.
 */
class DesktopShellUiSmokeTest {

    // ── Adaptive navigation rail ────────────────────────────────────────

    @Test
    fun railDefaultsToExpandedAtAndAboveTheBreakpoint() {
        assertEquals(RailMode.EXPANDED, defaultRailModeFor(1000.dp))
        assertEquals(RailMode.EXPANDED, defaultRailModeFor(1440.dp))
    }

    @Test
    fun railDefaultsToCompactBelowTheBreakpoint() {
        assertEquals(RailMode.COMPACT, defaultRailModeFor(999.dp))
        assertEquals(RailMode.COMPACT, defaultRailModeFor(720.dp))
    }

    @Test
    fun manualToggleHoldsInsideItsBandAndResetsAcrossBands() {
        // User collapsed the rail while in the EXPANDED band.
        assertEquals(
            RailMode.COMPACT,
            effectiveRailMode(1200.dp, RailMode.COMPACT, RailMode.EXPANDED),
        )
        // Same manual preference, but the window narrowed into the COMPACT
        // band: the band default applies (compact either way).
        assertEquals(
            RailMode.COMPACT,
            effectiveRailMode(800.dp, RailMode.COMPACT, RailMode.EXPANDED),
        )
        // No manual toggle yet: adaptive default.
        assertEquals(
            RailMode.EXPANDED,
            effectiveRailMode(1200.dp, null, null),
        )
        // Manual EXPAND in the compact band holds until the band changes.
        assertEquals(
            RailMode.EXPANDED,
            effectiveRailMode(800.dp, RailMode.EXPANDED, RailMode.COMPACT),
        )
        // Widening back into the EXPANDED band re-applies that band's
        // default instead of inheriting the stale manual expand.
        assertEquals(
            RailMode.EXPANDED,
            effectiveRailMode(1200.dp, RailMode.EXPANDED, RailMode.COMPACT),
        )
    }

    // ── Keyboard shortcuts ──────────────────────────────────────────────

    @Test
    fun spaceTogglesPlaybackWithoutModifiers() {
        assertEquals(
            ShellShortcut.TOGGLE_PLAY_PAUSE,
            shortcutFor(Key.Spacebar, ctrlPressed = false, metaPressed = false),
        )
        // Modifier-riding space belongs to the OS/IME layer — not ours.
        assertNull(shortcutFor(Key.Spacebar, ctrlPressed = true, metaPressed = false))
        assertNull(shortcutFor(Key.Spacebar, ctrlPressed = false, metaPressed = true))
    }

    @Test
    fun ctrlArrowsSkipTracks() {
        assertEquals(
            ShellShortcut.NEXT_TRACK,
            shortcutFor(Key.DirectionRight, ctrlPressed = true, metaPressed = false),
        )
        assertEquals(
            ShellShortcut.PREVIOUS_TRACK,
            shortcutFor(Key.DirectionLeft, ctrlPressed = true, metaPressed = false),
        )
        // Meta (macOS ⌘) maps to the same shortcuts.
        assertEquals(
            ShellShortcut.NEXT_TRACK,
            shortcutFor(Key.DirectionRight, ctrlPressed = false, metaPressed = true),
        )
    }

    @Test
    fun ctrlFocusesSearchAndUnboundKeysMapToNothing() {
        assertEquals(
            ShellShortcut.FOCUS_SEARCH,
            shortcutFor(Key.F, ctrlPressed = true, metaPressed = false),
        )
        assertNull(shortcutFor(Key.F, ctrlPressed = false, metaPressed = false))
        assertNull(shortcutFor(Key.A, ctrlPressed = true, metaPressed = false))
        assertNull(shortcutFor(Key.DirectionRight, ctrlPressed = false, metaPressed = false))
    }

    // ── Engine-port-backed shell state wiring ───────────────────────────

    @Test
    fun controllerBackedShellStateReflectsEnginePushesAndCommands() = runTest {
        val port = FakeEnginePort()
        val controller = PlaybackController(port, scope = backgroundScope)
        advanceUntilIdle()

        // Engine pushes a playing snapshot with a live DAC clock sample —
        // the exact wiring the shell's Home pane + MiniPlayerBar render.
        port.controller.value = PlaybackSnapshot(
            rev = 3L,
            status = PlaybackStatus.PLAYING,
            volume = 0.65f,
            clock = DacClockSample(
                framePosition = 48_000L * 125L,
                sampleRateHz = 48_000,
                monotonicNs = 125_000_000_000L,
            ),
        )
        advanceUntilIdle()

        val ui = controller.state.value
        assertEquals(PlaybackStatus.PLAYING, ui.status)
        assertEquals(0.65f, ui.volume)
        assertEquals(125_000L, ui.positionMs)
        assertNull(ui.progressFraction) // no duration known — indeterminate

        // Shell intent (transport buttons / tray menu / shortcuts share the
        // same command plane): every surface dispatches through the
        // controller, so tray and window can never disagree.
        controller.togglePlayPause()
        controller.next()
        controller.previous()
        controller.setVolume(1.5f)
        advanceUntilIdle()

        assertEquals(
            listOf(
                PlayerCommand.TogglePlayPause,
                PlayerCommand.Next,
                PlayerCommand.Previous,
                PlayerCommand.SetVolume(1.0f),
            ),
            port.dispatchedCommands,
        )
    }
}
