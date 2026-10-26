package com.streamify.app.connect

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.streamify.app.cast.CastMediaManager
import com.streamify.app.cast.CastPlaybackState
import com.streamify.app.viewmodel.PlayerViewModel

/**
 * ConnectPlaybackBridge — single-responsibility composable wiring the live
 * player controller into the Connect runtime (sprint P2, god-object
 * decomposition).
 *
 * Owns exactly three wirings, extracted verbatim from MainActivity:
 *   1. one-shot [ConnectRuntime.initialize]
 *   2. [LocalPlaybackHooks]: silent remote-controller enter/exit against the
 *      live controller (pause local render on takeover; seek+play on flip-back)
 *   3. [CastMediaManager.queueProvider]: full MediaItems handoff source from
 *      the live session controller
 *
 * Behavior identical; the activity composition root reads as intent, not
 * plumbing.
 */
@Composable
fun ConnectPlaybackBridgeEffect(activity: android.app.Activity, playerViewModel: PlayerViewModel) {
    LaunchedEffect(Unit) {
        ConnectRuntime.initialize(activity)
    }
    LaunchedEffect(playerViewModel) {
        ConnectRuntime.playbackHooks =
            object : LocalPlaybackHooks {
                override fun enterSilentController() {
                    // Silent remote controller: pause local render,
                    // keep queue + clock loaded (flip-back = seek).
                    val ctrl = playerViewModel.getController() ?: return
                    runCatching { ctrl.pause() }
                }

                override fun exitSilentController(positionMs: Long, play: Boolean) {
                    val ctrl = playerViewModel.getController() ?: return
                    runCatching {
                        ctrl.seekTo(positionMs)
                        if (play) ctrl.play() else ctrl.pause()
                    }
                }
            }

        // Cast handoff source: full MediaItems (stream URL +
        // metadata + artwork) from the live session controller.
        CastMediaManager.queueProvider = {
            val ctrl = playerViewModel.getController()
            if (ctrl != null && ctrl.mediaItemCount > 0) {
                CastPlaybackState(
                    items = (0 until ctrl.mediaItemCount).mapNotNull { i ->
                        runCatching { ctrl.getMediaItemAt(i) }.getOrNull()
                    },
                    startIndex = ctrl.currentMediaItemIndex,
                    positionMs = ctrl.currentPosition,
                    isPlaying = ctrl.isPlaying
                )
            } else {
                null
            }
        }
    }
}
