package com.maxrave.simpmusic.expect.ui

import androidx.compose.runtime.Composable

/**
 * RFC-003 §3.3: gesture-progress-aware back for overlays that live OUTSIDE the
 * NavHost (NowPlayingScreen, the search panel). `onProgress` receives the raw
 * LINEAR system progress 0..1 while a gesture drags; `onCancel`/`onCommit` fire
 * on release. Callers must settle to 0f or 1f LINEARLY from the current value —
 * never re-apply an easing curve mid-gesture — or the overlay visually snaps.
 */
@Composable
internal expect fun PlatformPredictiveBackHandler(
    enabled: Boolean,
    onProgress: (Float) -> Unit,
    onCancel: () -> Unit,
    onCommit: () -> Unit,
)
