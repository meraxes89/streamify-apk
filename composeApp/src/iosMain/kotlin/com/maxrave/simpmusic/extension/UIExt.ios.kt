package com.maxrave.simpmusic.extension

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import com.maxrave.domain.data.model.ui.ScreenSizeInfo
import kotlin.math.roundToInt

@Composable
actual fun getScreenSizeInfo(): ScreenSizeInfo {
    val density = LocalDensity.current
    val window = LocalWindowInfo.current
    val contentHeightPx = window.containerSize.height.coerceAtLeast(0)
    return ScreenSizeInfo(
        hDP = with(density) { contentHeightPx.toDp().value.roundToInt() },
        wDP = with(density) { window.containerSize.width.toDp().value.roundToInt() },
        hPX = contentHeightPx,
        wPX = window.containerSize.width,
    )
}

@Composable
actual fun KeepScreenOn() {
}

@Composable
actual fun rememberIsInPipMode(): Boolean = false
