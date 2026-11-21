package com.streamify.app.ui.theme

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

// =========================================================================
// Top-Level Tokens (YouTube Music OLED True Dark Graphite & Red)
// =========================================================================
val BgBase            = Color(0xFF030303) // True OLED dark graphite
val BgSurface         = Color(0xFF0F0F0F) // Primary container surface
val BgSurfaceElevated = Color(0xFF212121) // Bottom sheets, menus, dialogs
val BgCard            = Color(0xFF181818) // Feed cards and carousel containers
val BgChipInactive    = Color(0xFF212121) // Filter pill inactive surface
val BgChipActive      = Color(0xFFFFFFFF) // Selected filter pill active surface
val BgMiniPlayer      = Color(0xFF212121) // Docked mini player surface
val BgSearchBar       = Color(0xFF212121) // Omnibar search input

// Brand & Accent Tokens
val Primary           = Color(0xFFFF0000) // YouTube Brand Red
val PrimaryAccent     = Color(0xFFFF0033) // Vibrant Red-Orange variant
val ActiveControl     = Color(0xFFFFFFFF) // High-contrast white active controls
val AccentBlue        = Color(0xFF3EA6FF) // YouTube link & verified badge accent

// Text Hierarchy (WCAG AAA on #030303)
val TextMain          = Color(0xFFFFFFFF) // Primary titles, active lyrics
val TextSecondary     = Color(0xFFAAAAAA) // Secondary metadata (Artist, Album, Views)
val TextTertiary      = Color(0xFF717171) // Inactive lyrics, timestamps, subtitle cues
val TextOnActiveChip  = Color(0xFF030303) // Dark text on active white pill

// Borders & Dividers
val BorderSubtle      = Color(0xFF282828) // Card & sheet borders
val BorderChip        = Color(0xFF383838) // Unselected filter pill outline
val Divider           = Color(0xFF212121) // Hairline list dividers

// Gradients & Overlays
val Scrim             = Color(0xCC000000) // 80% black scrim
val GradientPop       = Brush.linearGradient(listOf(Color(0xFF8D67AB), Color(0xFF4B3F8E)))

// =========================================================================
// StreamifyColors Object (Single Source of Truth for App UI Components)
// =========================================================================
object StreamifyColors {
    val BgBase            = com.streamify.app.ui.theme.BgBase
    val BgSurface         = com.streamify.app.ui.theme.BgSurface
    val BgSurfaceElevated = com.streamify.app.ui.theme.BgSurfaceElevated
    val BgCard            = com.streamify.app.ui.theme.BgCard
    val BgCardHover       = Color(0xFF282828)
    val BgElevated        = com.streamify.app.ui.theme.BgSurfaceElevated
    val BgMiniPlayer      = com.streamify.app.ui.theme.BgMiniPlayer
    val BgPlayer          = Color(0xFF0A0A0A)
    val BgSearchBar       = com.streamify.app.ui.theme.BgSearchBar
    val BgChipInactive    = com.streamify.app.ui.theme.BgChipInactive
    val BgChipActive      = com.streamify.app.ui.theme.BgChipActive

    val Primary           = com.streamify.app.ui.theme.Primary
    val PrimaryHover      = Color(0xFFFF3333)
    val PrimaryDark       = Color(0xFFCC0000)
    val PrimaryAccent     = com.streamify.app.ui.theme.PrimaryAccent
    val ActiveControl     = com.streamify.app.ui.theme.ActiveControl
    val AccentSecondary   = com.streamify.app.ui.theme.ActiveControl
    val AccentBlue        = com.streamify.app.ui.theme.AccentBlue

    val TextMain          = com.streamify.app.ui.theme.TextMain
    val TextSub           = com.streamify.app.ui.theme.TextSecondary
    val TextSecondary     = com.streamify.app.ui.theme.TextSecondary
    val TextTertiary      = com.streamify.app.ui.theme.TextTertiary
    val TextDimmed        = com.streamify.app.ui.theme.TextTertiary
    val TextOnSearch      = com.streamify.app.ui.theme.TextSecondary
    val TextOnActiveChip  = com.streamify.app.ui.theme.TextOnActiveChip

    val Border            = com.streamify.app.ui.theme.BorderSubtle
    val BorderSubtle      = com.streamify.app.ui.theme.BorderSubtle
    val BorderChip        = com.streamify.app.ui.theme.BorderChip
    val Divider           = com.streamify.app.ui.theme.Divider

    val ErrorRed          = Color(0xFFFF4D4D)
    val ErrorBg           = Color(0x26EB5757)
    val Explicit          = Color(0xFF888888)
    val Shuffle           = com.streamify.app.ui.theme.ActiveControl

    val Scrim             = com.streamify.app.ui.theme.Scrim
    val PlayerGradient    = Color(0xE60A0A0A)

    val Surface0dp        = Color(0xFF030303)
    val Surface1dp        = Color(0xFF0F0F0F)
    val Surface2dp        = Color(0xFF181818)
    val Surface3dp        = Color(0xFF212121)
    val Surface4dp        = Color(0xFF282828)

    val GradientPop       = listOf(Color(0xFFE13300), Color(0xFFFF8A65))
    val GradientHipHop    = listOf(Color(0xFFBA5D07), Color(0xFFFFB74D))
    val GradientRock      = listOf(Color(0xFFE91E63), Color(0xFFF48FB1))
    val GradientIndie     = listOf(Color(0xFF608108), Color(0xFFAED581))
    val GradientWorkout   = listOf(Color(0xFF777777), Color(0xFFE0E0E0))
    val GradientFocus     = listOf(Color(0xFF509BF5), Color(0xFF90CAF9))
    val GradientChill     = listOf(Color(0xFF8D67AB), Color(0xFFCE93D8))
    val GradientSleep     = listOf(Color(0xFF1E3264), Color(0xFF5C6BC0))

    val PlayerDynamicBase = Color(0xFF212121)
}
