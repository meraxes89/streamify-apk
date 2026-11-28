package com.maxrave.simpmusic.expect.ui

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.flow.collectLatest

/**
 * Backed by androidx.activity's PredictiveBackHandler (activity-compose 1.13.0, already
 * a dependency of the shells). The flow's BackEventCompat.progress is linear 0..1 while
 * the gesture drags; a completed gesture ends the flow (commit), a cancelled one throws
 * CancellationException. The lambdas are cached in state so re-registering the handler
 * on every recomposition does not restart an in-flight gesture.
 */
@Composable
internal actual fun PlatformPredictiveBackHandler(
    enabled: Boolean,
    onProgress: (Float) -> Unit,
    onCancel: () -> Unit,
    onCommit: () -> Unit,
) {
    var currentOnProgress by remember { mutableStateOf(onProgress) }
    var currentOnCancel by remember { mutableStateOf(onCancel) }
    var currentOnCommit by remember { mutableStateOf(onCommit) }
    LaunchedEffect(onProgress, onCancel, onCommit) {
        currentOnProgress = onProgress
        currentOnCancel = onCancel
        currentOnCommit = onCommit
    }
    PredictiveBackHandler(enabled = enabled) { progressFlow ->
        try {
            progressFlow.collectLatest { event ->
                currentOnProgress(event.progress)
            }
            currentOnCommit()
        } catch (e: kotlinx.coroutines.CancellationException) {
            currentOnCancel()
        }
    }
}
