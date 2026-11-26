package com.maxrave.simpmusic.expect.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect

/**
 * iOS edge-swipe: the interactive pop gesture reports progress through
 * [IosPredictiveBackRegistry], which the app's back conduit feeds from the system
 * gesture. Progress is linear 0..1; passing the threshold commits, lifting the
 * finger early cancels — the overlay then settles linearly back to rest.
 */
@Composable
internal actual fun PlatformPredictiveBackHandler(
    enabled: Boolean,
    onProgress: (Float) -> Unit,
    onCancel: () -> Unit,
    onCommit: () -> Unit,
) {
    if (!enabled) return
    DisposableEffect(onProgress, onCancel, onCommit) {
        val listener = IosPredictiveBackRegistry.Listener(onProgress, onCommit, onCancel)
        IosPredictiveBackRegistry.register(listener)
        onDispose { IosPredictiveBackRegistry.unregister(listener) }
    }
}

/** Progress fan-out for the edge-swipe gesture, mirroring [IosBackHandlerRegistry]. */
object IosPredictiveBackRegistry {
    class Listener(
        val onUpdate: (Float) -> Unit,
        val onCommit: () -> Unit,
        val onCancel: () -> Unit,
    )

    private val listeners = mutableListOf<Listener>()

    fun register(listener: Listener) {
        listeners.add(listener)
    }

    fun unregister(listener: Listener) {
        listeners.remove(listener)
    }

    /** The system gesture conduit calls these; no-op when nothing listens. */
    fun dispatchProgress(progress: Float) {
        listeners.lastOrNull()?.onUpdate(progress)
    }

    fun dispatchCommit() {
        listeners.lastOrNull()?.onCommit()
    }

    fun dispatchCancel() {
        listeners.lastOrNull()?.onCancel()
    }
}
