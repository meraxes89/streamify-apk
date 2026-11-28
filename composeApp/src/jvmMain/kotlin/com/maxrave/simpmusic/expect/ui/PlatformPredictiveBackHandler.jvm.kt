package com.maxrave.simpmusic.expect.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect

/**
 * Desktop: Esc maps straight to commit — there is no dragged gesture to scrub, so
 * progress is never reported (the caller's settle-to-rest stays at 0f; nothing snaps
 * because nothing moved).
 */
@Composable
internal actual fun PlatformPredictiveBackHandler(
    enabled: Boolean,
    onProgress: (Float) -> Unit,
    onCancel: () -> Unit,
    onCommit: () -> Unit,
) {
    if (!enabled) return
    DisposableEffect(onCommit) {
        DesktopPredictiveBackRegistry.handler = onCommit
        onDispose { DesktopPredictiveBackRegistry.handler = null }
    }
}

/** The single active commit handler; DesktopApp's key dispatch calls it on Esc. */
object DesktopPredictiveBackRegistry {
    var handler: (() -> Unit)? = null
}
