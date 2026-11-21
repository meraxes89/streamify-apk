package com.streamify.desktop.shell

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Phase 5 (MP-UI04 / Beta): the desktop shell's navigation model — pure,
 * headless-testable state machinery for the adaptive navigation rail.
 *
 * The adaptive policy (master plan §4 Phase 5, "adaptive navigation rail"):
 * a window at or above [RailBreakpoints.EXPANDED_WIDTH] defaults to the
 * EXPANDED rail (icon + label destinations); narrower windows default to
 * the COMPACT icon-only rail. The user's manual collapse toggle always
 * wins over the adaptive default until the window crosses another
 * breakpoint band (standard Material adaptive-rail semantics).
 */
enum class ShellSection(val label: String) {
    HOME("Home"),
    SEARCH("Search"),
    LIBRARY("Library"),
    JAM("Jam Sessions"),
    SETTINGS("Settings"),
}

/** Rail presentation mode. */
enum class RailMode {
    /** Icon + label destinations (~220 dp). */
    EXPANDED,

    /** Icon-only destinations (~72 dp). */
    COMPACT,
}

/** Width breakpoints for the adaptive rail. */
object RailBreakpoints {
    /** At/above this width the rail defaults to EXPANDED. */
    val EXPANDED_WIDTH: Dp = 1000.dp
}

/**
 * The adaptive default for a content-pane width. Pure function — the
 * headless shell smoke test pins the band edges.
 */
fun defaultRailModeFor(width: Dp): RailMode =
    if (width >= RailBreakpoints.EXPANDED_WIDTH) RailMode.EXPANDED else RailMode.COMPACT

/**
 * Resolves the effective rail mode from the adaptive default, the user's
 * manual toggle state, and the width band the toggle was last set in.
 *
 * Semantics: a manual toggle applies while the window stays in the same
 * adaptive band; crossing into a different band re-applies that band's
 * default (so restoring a wide window after a narrow one returns to the
 * EXPANDED rail instead of inheriting a stale compact preference).
 */
fun effectiveRailMode(
    width: Dp,
    manualToggle: RailMode?,
    manualToggleBand: RailMode?,
): RailMode {
    val adaptive = defaultRailModeFor(width)
    if (manualToggle == null || manualToggleBand == null) return adaptive
    return if (manualToggleBand == adaptive) manualToggle else adaptive
}
