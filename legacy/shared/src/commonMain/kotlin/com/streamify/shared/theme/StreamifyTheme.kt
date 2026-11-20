package com.streamify.shared.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Phase 5 (MP-UI03 / Beta): platform-agnostic Compose Multiplatform theme
 * tokens — colors, typography, spacing, shapes — plus the [StreamifyTheme]
 * entry point both client shells wrap their content in.
 *
 * The palette is the app's proven OLED true-dark graphite + brand red
 * system (WCAG AAA text hierarchy on #030303), lifted verbatim from
 * app/src/main/java/com/streamify/app/ui/theme/Color.kt so the desktop
 * shell and the Android seam render the same brand surface without either
 * platform duplicating token definitions. Typography follows the app's
 * system-default-family doctrine (FontFamily.Default — zero font-provider
 * network calls, zero cold-start latency, zero reflow).
 */

/**
 * Raw color tokens (single source of truth for both platforms' shared UI).
 */
object StreamifyPalette {
    /** True OLED dark graphite background. */
    val BgBase = Color(0xFF030303)

    /** Primary container surface. */
    val BgSurface = Color(0xFF0F0F0F)

    /** Bottom sheets, menus, dialogs, docked mini player. */
    val BgSurfaceElevated = Color(0xFF212121)

    /** Feed cards and carousel containers. */
    val BgCard = Color(0xFF181818)

    /** Brand red — primary actions, active progress, emphasis. */
    val Primary = Color(0xFFFF0000)

    /** Vibrant red-orange variant for hover/press states. */
    val PrimaryAccent = Color(0xFFFF0033)

    /** High-contrast white active controls. */
    val ActiveControl = Color(0xFFFFFFFF)

    /** Link & verified-badge accent. */
    val AccentBlue = Color(0xFF3EA6FF)

    /** Primary titles, active values. */
    val TextMain = Color(0xFFFFFFFF)

    /** Secondary metadata (artist, album, counts). */
    val TextSecondary = Color(0xFFAAAAAA)

    /** Tertiary metadata (timestamps, hints, inactive lyrics). */
    val TextTertiary = Color(0xFF717171)

    /** Card & sheet borders. */
    val BorderSubtle = Color(0xFF282828)

    /** Hairline list dividers. */
    val Divider = Color(0xFF212121)

    /** Error surfaces. */
    val Error = Color(0xFFFF4D4D)
}

/**
 * Spacing tokens (dp) — the rhythm scale of the shared surfaces.
 */
object StreamifySpacing {
    val Xs = 4.dp
    val Sm = 8.dp
    val Md = 12.dp
    val Lg = 16.dp
    val Xl = 24.dp
    val Xxl = 32.dp
    val Xxxl = 48.dp

    /** Docked mini player height (matches the app's dock spec). */
    val MiniPlayerHeight = 64.dp

    /** Shared control hit-target size. */
    val ControlSize = 40.dp
}

/**
 * Typography tokens — the app's compact scale over FontFamily.Default.
 */
data class StreamifyTextStyle(
    val headline: TextStyle = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
    ),
    val title: TextStyle = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp,
    ),
    val songTitle: TextStyle = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 15.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp,
    ),
    val songArtist: TextStyle = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.1.sp,
    ),
    val body: TextStyle = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    val caption: TextStyle = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.2.sp,
    ),
    val mono: TextStyle = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    ),
)

/** Default shared typography set. */
val StreamifyTextStyles = StreamifyTextStyle()

/**
 * Shape tokens — the app's soft-rectangle family.
 */
val StreamifyShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

/**
 * The Material3 mapping of the shared tokens (single dark scheme — the
 * Streamify brand is dark-native on every platform).
 */
private val StreamifyColorScheme = darkColorScheme(
    primary = StreamifyPalette.Primary,
    onPrimary = StreamifyPalette.BgBase,
    secondary = StreamifyPalette.AccentBlue,
    onSecondary = StreamifyPalette.BgBase,
    background = StreamifyPalette.BgBase,
    onBackground = StreamifyPalette.TextMain,
    surface = StreamifyPalette.BgSurface,
    onSurface = StreamifyPalette.TextMain,
    surfaceVariant = StreamifyPalette.BgSurfaceElevated,
    onSurfaceVariant = StreamifyPalette.TextSecondary,
    outline = StreamifyPalette.BorderSubtle,
    error = StreamifyPalette.Error,
)

private val StreamifyMaterialTypography = Typography().let { base ->
    // Typography() defaults with the shared text styles layered onto the
    // slots the shared components actually use.
    base.copy(
        headlineMedium = StreamifyTextStyles.headline,
        titleMedium = StreamifyTextStyles.title,
        bodyMedium = StreamifyTextStyles.body,
        labelSmall = StreamifyTextStyles.caption,
    )
}

/**
 * The shared theme entry point: wraps platform shell content in the token
 * set so every [com.streamify.shared.components] composable renders the
 * Streamify brand identically on desktop and Android.
 */
@Composable
fun StreamifyTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = StreamifyColorScheme,
        typography = StreamifyMaterialTypography,
        shapes = StreamifyShapes,
        content = content,
    )
}
