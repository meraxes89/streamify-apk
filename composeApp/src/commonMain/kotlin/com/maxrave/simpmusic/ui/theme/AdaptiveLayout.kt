package com.maxrave.simpmusic.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.window.core.layout.WindowSizeClass
import androidx.window.core.layout.WindowSizeClass.Companion.WIDTH_DP_EXPANDED_LOWER_BOUND
import androidx.window.core.layout.WindowSizeClass.Companion.WIDTH_DP_MEDIUM_LOWER_BOUND

/**
 * Three-tier adaptive width classification shared across all KMP targets.
 */
enum class AppWindowSizeClass {
    Compact,
    Medium,
    Expanded,
    ;

    val isCompact: Boolean
        get() = this == Compact

    val isExpanded: Boolean
        get() = this == Expanded

    val moodGridColumns: Int
        get() =
            when (this) {
                Compact -> 2
                Medium -> 3
                Expanded -> 4
            }

    val maxDialogListHeight: Dp
        get() =
            when (this) {
                Compact -> 420.dp
                Medium -> 520.dp
                Expanded -> 600.dp
            }
}

val LocalAppWindowSizeClass = staticCompositionLocalOf { AppWindowSizeClass.Compact }

fun WindowSizeClass.toAppWindowSizeClass(): AppWindowSizeClass =
    when {
        isWidthAtLeastBreakpoint(WIDTH_DP_EXPANDED_LOWER_BOUND) -> AppWindowSizeClass.Expanded
        isWidthAtLeastBreakpoint(WIDTH_DP_MEDIUM_LOWER_BOUND) -> AppWindowSizeClass.Medium
        else -> AppWindowSizeClass.Compact
    }
