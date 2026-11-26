package com.maxrave.simpmusic.expect.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect

/**
 * Browser: a popstate maps straight to commit — the browser owns the gesture, so
 * there is no drag progress to report and nothing to settle.
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
        WebPredictiveBackRegistry.handler = onCommit
        onDispose { WebPredictiveBackRegistry.handler = null }
    }
}

/** The single active commit handler; the popstate conduit calls it. */
object WebPredictiveBackRegistry {
    var handler: (() -> Unit)? = null
}
