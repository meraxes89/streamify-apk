package com.streamify.app.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

// =========================================================================
// Material 3 Dark Color Scheme (YouTube Music OLED Graphite & Red Palette)
// =========================================================================
private val StreamifyDarkColorScheme = darkColorScheme(
    primary            = Primary,
    onPrimary          = TextMain,
    primaryContainer   = PrimaryAccent,
    onPrimaryContainer = TextMain,
    secondary          = ActiveControl,
    onSecondary        = BgBase,
    background         = BgBase,
    onBackground       = TextMain,
    surface            = BgSurface,
    onSurface          = TextMain,
    surfaceVariant     = BgSurfaceElevated,
    onSurfaceVariant   = TextSecondary,
    tertiary           = AccentBlue,
    onTertiary         = TextMain,
    error              = PrimaryAccent,
    onError            = TextMain,
    outline            = BorderSubtle,
    outlineVariant     = Divider
)

// =========================================================================
// StreamifyTheme Object Accessor
// =========================================================================
object StreamifyTheme {
    val typography: StreamifyTypography
        @Composable
        get() = LocalAppTypography.current

    val shapes: StreamifyShapeTokens
        @Composable
        get() = LocalAppShapes.current

    val dimens: StreamifyDimenTokens
        @Composable
        get() = LocalAppDimens.current
}

// =========================================================================
// Master Theme Wrapper
// =========================================================================
@Composable
fun StreamifyTheme(
    content: @Composable () -> Unit
) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window
            if (window != null) {
                // Enforce Edge-to-Edge for YTM immersive layout
                WindowCompat.setDecorFitsSystemWindows(window, false)
                val controller = WindowCompat.getInsetsController(window, view)
                controller.isAppearanceLightStatusBars = false
                controller.isAppearanceLightNavigationBars = false
                window.statusBarColor = BgBase.toArgb()
                window.navigationBarColor = BgBase.toArgb()
            }
        }
    }

    val screenConfiguration = rememberScreenConfiguration()

    CompositionLocalProvider(
        LocalAppTypography provides StreamifyTypography(),
        LocalAppShapes provides StreamifyShapeTokens(),
        LocalAppDimens provides StreamifyDimenTokens(),
        LocalScreenConfiguration provides screenConfiguration
    ) {
        MaterialTheme(
            colorScheme = StreamifyDarkColorScheme,
            typography  = Typography,
            shapes      = AppShapes,
            content     = content
        )
    }
}
