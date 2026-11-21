package com.streamify.app.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// =========================================================================
// StreamifyDimenTokens (YouTube Music Information Density Scale)
// =========================================================================
data class StreamifyDimenTokens(
    // Global Layout
    val bottomNavHeight: Dp        = 56.dp,
    // Mini Player (Docked Architecture)
    val miniPlayerHeight: Dp       = 64.dp,
    val miniPlayerMargin: Dp       = 0.dp,
    val miniPlayerArt: Dp          = 48.dp,
    val miniProgressBarHeight: Dp  = 2.dp,
    // Lists & Rows
    val quickPickRowHeight: Dp     = 56.dp,
    val trackRowHeight: Dp         = 56.dp,
    val trackRowArt: Dp            = 48.dp,
    // Cards & Carousels
    val cardWidth: Dp              = 150.dp,
    val cardArtSize: Dp            = 150.dp,
    // Interactive Elements
    val filterChipHeight: Dp       = 32.dp,
    val playerActionPillHeight: Dp = 36.dp
)

val LocalAppDimens = staticCompositionLocalOf { StreamifyDimenTokens() }

// =========================================================================
// StreamifyDimens Object (Compatibility with Existing UI Components)
// =========================================================================
object StreamifyDimens {
    // Spacing Scale (4dp base grid)
    val SpaceXXS  = 2.dp
    val SpaceXS   = 4.dp
    val SpaceSM   = 8.dp
    val SpaceMD   = 12.dp
    val SpaceLG   = 16.dp
    val SpaceXL   = 20.dp
    val SpaceXXL  = 24.dp
    val SpaceHuge = 32.dp
    val SpaceGiant= 48.dp

    // Component Sizes (YouTube Music Overhaul)
    val BottomNavHeight       = 56.dp
    val MiniPlayerHeight      = 64.dp
    val MiniPlayerMargin      = 0.dp      // YTM docked edge-to-edge
    val MiniPlayerRadius      = 0.dp
    val MiniPlayerArt         = 48.dp     // 48x48 album art thumbnail
    val MiniProgressBarHeight = 2.dp      // Thin bottom progress line
    val FullPlayerArtSize     = 340.dp
    val SearchBarHeight       = 48.dp
    val QuickPickRowHeight    = 56.dp     // 4 stacked rows in carousel
    val RecentCardHeight      = 48.dp
    val RecentCardArt         = 48.dp
    val TrackRowHeight        = 56.dp
    val TrackRowArt           = 48.dp
    val CategoryCardH         = 100.dp
    val ChipHeight            = 32.dp
    val FilterChipHeight      = 32.dp
    val PlayerActionPillHeight= 36.dp

    // Card Sizes
    val CardWidth             = 150.dp
    val CardArtSize           = 150.dp
    val ArtistCardSize        = 150.dp

    // Radii
    val RadiusNone    = 0.dp
    val RadiusSM      = 4.dp
    val RadiusMD      = 8.dp
    val RadiusLG      = 12.dp
    val RadiusXL      = 16.dp
    val RadiusFull    = 50.dp

    // Player Controls
    val PlayButtonSize    = 64.dp
    val SkipButtonSize    = 32.dp
    val ShuffleButtonSize = 24.dp
    val SeekBarHeight     = 4.dp
    val SeekBarThumb      = 12.dp
    val ProgressLineH     = 2.dp
    val DividerThickness  = 1.dp
    val PlayerBarHeight   = 64.dp     // Docked MiniPlayerBar height

    // Bottom Sheet
    val SheetPeekHeight   = 0.dp
    val SheetMaxHeight    = 0.95f
}

object StreamifyAnimations {
    const val Fast = 150
    const val Normal = 300
    const val Slow = 500
    const val SuperSlow = 800

    const val SpringStiffness = 300f
    const val SpringDamping = 0.75f

    const val StaggerBase = 50
    const val StaggerIncrement = 30
}
