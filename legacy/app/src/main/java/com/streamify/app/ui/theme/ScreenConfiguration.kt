package com.streamify.app.ui.theme

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

data class ScreenConfiguration(
    val widthDp: Dp,
    val heightDp: Dp,
    val isLandscape: Boolean,
    val isTablet: Boolean
)

val LocalScreenConfiguration = staticCompositionLocalOf {
    ScreenConfiguration(0.dp, 0.dp, false, false)
}

@Composable
fun rememberScreenConfiguration(): ScreenConfiguration {
    val configuration = LocalConfiguration.current
    return remember(configuration) {
        ScreenConfiguration(
            widthDp = configuration.screenWidthDp.dp,
            heightDp = configuration.screenHeightDp.dp,
            isLandscape = configuration.screenWidthDp > configuration.screenHeightDp,
            isTablet = configuration.screenWidthDp >= 600
        )
    }
}

// Extension to prevent UI stretching on tablets and wide screens
@Composable
fun Modifier.centerInLargeScreen(maxWidth: Dp = 1100.dp): Modifier {
    val config = LocalScreenConfiguration.current
    return if (config.isTablet || config.widthDp > maxWidth) {
        this.fillMaxWidth().wrapContentWidth(Alignment.CenterHorizontally).width(maxWidth)
    } else {
        this.fillMaxWidth()
    }
}
