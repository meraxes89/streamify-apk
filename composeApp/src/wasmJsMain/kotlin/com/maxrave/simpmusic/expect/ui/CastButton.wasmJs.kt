package com.maxrave.simpmusic.expect.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

@Composable
actual fun PlatformCastButton(
    modifier: Modifier,
    tint: Color,
) {
}

actual fun isPlatformCastAvailable(): Boolean = false

@Composable
actual fun rememberCastReceivers(discover: Boolean): CastReceivers = NoCastReceivers

private val NoCastReceivers = CastReceivers(receivers = emptyList(), connect = {}, disconnect = {})
