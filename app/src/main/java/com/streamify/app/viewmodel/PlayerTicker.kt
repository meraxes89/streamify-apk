package com.streamify.app.viewmodel

import com.streamify.app.util.SLog
import android.content.ComponentName
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.streamify.app.data.NativeBridge
import com.streamify.app.data.repository.TrackRepository
import com.streamify.app.data.models.Track
import com.streamify.app.media.playback.PlaybackService
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
/** Position/jam tick loops: the 200ms position poller and the adaptive
 * jam heartbeat ticker. */
internal fun PlayerViewModel.startPollingPosition() {
    positionPollingJob?.cancel()
    positionPollingJob = viewModelScope.launch {
        while (true) {
            // STATS OVERHAUL: this poller NO LONGER accumulates listening
            // seconds — PlaybackService's ExoPlayer listener is the single
            // authoritative writer (double-counting eliminated at source).

            controller?.let { ctrl ->
                val curState = _playerState.value
                val playerDuration = if (ctrl.duration > 0) ctrl.duration else 0L
                val currentTrack = curState.currentTrack
                val trackDuration = (currentTrack?.durationSec?.toLong() ?: 0L) * 1000L
                val finalDuration = if (playerDuration > 0) playerDuration else if (trackDuration > 0) trackDuration else curState.duration

                val updatedTrack = if (currentTrack != null && currentTrack.durationSec <= 0 && finalDuration > 0) {
                    currentTrack.copy(durationSec = (finalDuration / 1000).toInt())
                } else currentTrack

                if (!isOptimisticSeeking) {
                    val ctrlPos = ctrl.currentPosition.coerceAtLeast(0L)
                    // HOT PATH: position ticks go to the dedicated flow so the
                    // UI root never recomposes for them.
                    _positionMs.value = ctrlPos
                    _durationMs.value = finalDuration

                    // COLD PATH: full-state copy only when something beyond
                    // the playhead actually changed (rare).
                    if (curState.duration != finalDuration || curState.currentTrack !== updatedTrack) {
                        _playerState.value = curState.copy(
                            duration = finalDuration,
                            currentTrack = updatedTrack
                        )
                    }
                } else {
                    // Safety fallback: Unlatch optimistic seek if engine caught up within 250ms threshold
                    pendingSeekTargetMs?.let { target ->
                        if (kotlin.math.abs(ctrl.currentPosition - target) < 250L) {
                            isOptimisticSeeking = false
                            pendingSeekTargetMs = null
                        }
                    }
                }

                // UI-01: pull engine-side buffer telemetry while a session is
                // live. Gated on availability so degraded environments (JVM
                // tests, proxies pending) never churn FFI dispatch — the
                // bridge's app-side underrun detection keeps flowing
                // regardless, and the ViewModel's bufferHealth collector
                // mirrors every publication.
                if (com.streamify.app.data.uniffi.StreamifyEngineBridge.availability.value !=
                    com.streamify.app.data.uniffi.StreamifyEngineBridge.Availability.NATIVE_UNAVAILABLE
                ) {
                    com.streamify.app.data.uniffi.StreamifyEngineBridge.refreshBufferStats()
                }

                // Explicit Unit keeps the trailing if/else above in statement
                // position so the inner if needs no else branch (K2 semantics).
                Unit
            }
            delay(200)
        }
    }

    startJamTicker()
}

// ── JAM PHASE-1: dedicated adaptive tick loop ─────────────────────────
// Runs OUTSIDE the 200ms UI position poll so the 50ms end-of-track cadence
// is actually achievable, and fires the NEXT_IS pre-hydration intent (P3).

internal fun PlayerViewModel.stopPollingPosition() {
    positionPollingJob?.cancel()
}

internal fun PlayerViewModel.startJamTicker() {
    if (jamTickerJob?.isActive == true) return
    jamTickerJob = viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
        while (isActive) {
            val engine = com.streamify.app.jam.JamEngine
            if (!engine.isActive() || !engine.isHost() || isApplyingJamSync) {
                delay(500L)
                continue
            }
            val ctrl = controller
            if (ctrl == null) { delay(500L); continue }
            if (_playerState.value.isPlaying) {
                val cur = _playerState.value.currentTrack
                val pos = ctrl.currentPosition.coerceAtLeast(0L)
                val dur = ctrl.duration.takeIf { it > 0 } ?: cur?.durationSec?.toLong()?.times(1000L) ?: 0L

                engine.heartbeatTick(track = cur, positionMs = pos, isPlaying = true)

                // P3: announce the queue head ~30s before track end.
                if (dur > 0 && dur - pos in 1..30_000L) {
                    val next = engine.queueHead()
                    if (next != null && engine.announcedNextId != "${next.id}:${next.title}") {
                        engine.announcedNextId = "${next.id}:${next.title}"
                        engine.announceNextIs(next)
                    }
                }
            } else {
                // JAM v4: a paused-but-live leader still heartbeats (frozen
                // position, playing=false) — heartbeat-loss detection must
                // never mistake a paused host for a dead one.
                engine.heartbeatTick(
                    track = _playerState.value.currentTrack,
                    positionMs = ctrl.currentPosition.coerceAtLeast(0L),
                    isPlaying = false
                )
            }
            delay(engine.tickIntervalMs(ctrl.currentPosition.coerceAtLeast(0L), ctrl.duration, _playerState.value.isPlaying))
        }
    }
}
