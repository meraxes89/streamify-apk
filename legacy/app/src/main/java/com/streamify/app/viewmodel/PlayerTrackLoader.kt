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
/** Track loading pipeline: JIT resolve + vault gate, automatic transitions,
 * lookahead pre-buffering and continuum-radio hydration. */
internal suspend fun PlayerViewModel.playTrackInternal(track: Track, index: Int, queue: List<Track>) {
    seekTimeoutJob?.cancel()
    isOptimisticSeeking = false
    pendingSeekTargetMs = null

    SLog.i(
        "PlayerVM",
        com.streamify.app.util.Trace.pfx() + "PLAY idx=$index '${track.title}' vid=${track.ytmVideoId ?: "?"} path=${track.filepath.take(48)}"
    )

    _playerState.value = _playerState.value.copy(
        currentTrack = track,
        currentIndex = index,
        currentPosition = 0L,
        queue = queue,
        isPlaying = true,
        isBuffering = true,
        isVideoMode = false
    )
    playbackStartTimeMs = System.currentTimeMillis()
    com.streamify.app.media.audio.StreamifyAudioProcessor.currentPreGainDb = null

    // 0. SMART OFFLINE VAULT GATE (0ms instant local playback if pre-cached)
    // File-stat work (vault index hit + existence/size probe) kept off the
    // main thread — this runs on every track tap.
    val vaulted = withContext(Dispatchers.IO) {
        com.streamify.app.data.persistence.SmartOfflineVaultEngine.getOfflineTrack(track, appContext)
    }
    val trackToPlay = vaulted ?: track
    if (vaulted != null) {
        SLog.d("ResolveTrace", "R0 VAULT HIT: ${track.title}")
    }

    // 1. FAST-PATH GATE: If trackToPlay.filepath is already a direct playable local file or unexpired CDN stream
    val isAlreadyDirectCdn = (trackToPlay.filepath.contains("googlevideo.com") || trackToPlay.filepath.contains(".googlevideo.")) &&
            !com.streamify.app.data.network.YouTubeStreamResolver.isCdnExpired(trackToPlay.filepath)
    val isLocalFile = trackToPlay.filepath.startsWith("/") || trackToPlay.filepath.startsWith("file://") ||
            withContext(Dispatchers.IO) { java.io.File(trackToPlay.filepath).exists() }

    val knownVideoId = trackToPlay.ytmVideoId?.takeIf { it.isNotBlank() }
        ?: com.streamify.app.data.network.YouTubeStreamResolver.extractVideoId(trackToPlay.filepath, trackToPlay.coverArtPath)

    val resolvedTrack = if (isLocalFile || isAlreadyDirectCdn) {
        trackToPlay.copy(ytmVideoId = knownVideoId ?: trackToPlay.ytmVideoId)
    } else {
        try {
            withContext(Dispatchers.IO) {
                val res = com.streamify.app.data.network.YouTubeStreamResolver.resolveStreamJit(trackToPlay)
                val resolved = res.getOrNull()
                resolved?.let { com.streamify.app.media.audio.StreamifyAudioProcessor.currentPreGainDb = it.loudnessDb }
                if (resolved != null && resolved.streamUrl.isNotBlank()) {
                    trackToPlay.copy(filepath = resolved.streamUrl, ytmVideoId = knownVideoId ?: trackToPlay.ytmVideoId)
                } else {
                    trackToPlay.copy(ytmVideoId = knownVideoId ?: trackToPlay.ytmVideoId)
                }
            }
        } catch (ce: kotlinx.coroutines.CancellationException) {
            throw ce // user skipped mid-resolve / job cancelled — NOT a resolution failure
        } catch (t: Throwable) {
            // Resolution threw instead of returning null: route through the same
            // strike system, otherwise exceptions bypass the failure cap entirely.
            SLog.e("PlayerViewModel", "Stream resolution threw for ${track.title}", t)
            withContext(Dispatchers.Main) {
                _playerState.value = _playerState.value.copy(
                    isBuffering = false,
                    isPlaying = false,
                    lastError = "Could not play '${track.title}': ${t.message ?: "resolution error"}"
                )
                UiEventBus.emitEvent(UiEvent.ShowSnackbar("Could not play '${track.title}'. Tap to retry."))
            }
            return
        }
    }

    val isDirectStream = resolvedTrack.filepath.startsWith("http") &&
            !resolvedTrack.filepath.contains("youtube.com/watch") &&
            !resolvedTrack.filepath.contains("music.youtube.com") &&
            !resolvedTrack.filepath.startsWith("ytsearch:")
    val isPlayable = isDirectStream ||
            resolvedTrack.filepath.startsWith("file") ||
            java.io.File(resolvedTrack.filepath).exists()

    if (isPlayable) {
        val mediaItem = buildMediaItem(resolvedTrack)
        withContext(Dispatchers.Main) {
            // Silent-skip guard: a dead/failing MediaSession bind used to fall
            // through here with zero feedback — track docked, no error, ever.
            var sessionBindFailed = false
            try {
                val ctrl = controller ?: try { controllerFuture?.get() } catch (_: Throwable) { null }
                if (ctrl != null) {
                    ctrl.setMediaItem(mediaItem, 0L)
                    ctrl.prepare()
                    ctrl.play()
                } else {
                    sessionBindFailed = true
                }
            } catch (e: Throwable) {
                SLog.st("PlayerViewModel", "PlayerViewModel.playTrackInternal failed", e)
            }
            _playerState.value = _playerState.value.copy(
                currentTrack = resolvedTrack,
                isBuffering = false,
                lastError = if (sessionBindFailed) "Player engine unavailable" else null
            )
            if (sessionBindFailed) {
                SLog.e("PlayerViewModel", "controller NULL after resolve — MediaSession bind failed, cannot start '${track.title}'")
                UiEventBus.emitEvent(UiEvent.ShowSnackbar("Player engine unavailable — restart the app"))
            }
        }

        // 2. Arm background lookahead pre-buffer for slot 1 (track N+1)
        armLookaheadPreBuffer(index + 1, queue)

        // 3. Fire-and-Forget Asynchronous Database Registration (Moved OFF critical playback start path)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val registered = repository.registerStreamedTrack(track, appContext)
                if (registered.id > 0) {
                    withContext(Dispatchers.Main) {
                        val cur = _playerState.value.currentTrack
                        if (cur != null && cur.title == track.title && cur.artist == track.artist) {
                            val finalVid = registered.ytmVideoId ?: track.ytmVideoId ?: resolvedTrack.ytmVideoId
                            _playerState.value = _playerState.value.copy(
                                currentTrack = registered.copy(
                                    filepath = resolvedTrack.filepath,
                                    ytmVideoId = finalVid
                                )
                            )
                            maybeFetchLyricsForTrack(_playerState.value.currentTrack)
                        }
                    }
                }
            } catch (e: Exception) {
                SLog.st("PlayerViewModel", "PlayerViewModel.playTrackInternal failed", e)
            }
        }
    } else {
        SLog.e("PlayerViewModel", "Track stream unresolvable for ${track.title}")
        withContext(Dispatchers.Main) {
            _playerState.value = _playerState.value.copy(
                isBuffering = false,
                isPlaying = false,
                lastError = "Could not resolve '${track.title}' — all resolver tiers exhausted"
            )
            UiEventBus.emitEvent(UiEvent.ShowSnackbar("Could not resolve '${track.title}'"))
        }
    }
}

internal fun PlayerViewModel.handleAutomaticTimelineTransition() {
    seekTimeoutJob?.cancel()
    isOptimisticSeeking = false
    pendingSeekTargetMs = null

    val curState = _playerState.value
    val queue = curState.queue
    val nextIndex = curState.currentIndex + 1
    if (nextIndex < queue.size) {
        val activeTrack = queue[nextIndex]
        _playerState.value = _playerState.value.copy(
            currentTrack = activeTrack,
            currentIndex = nextIndex,
            currentPosition = 0L,
            isPlaying = true,
            isBuffering = false,
            isVideoMode = false
        )
        controller?.let { ctrl ->
            if (ctrl.mediaItemCount > 1) {
                ctrl.removeMediaItem(0)
            }
        }
        armLookaheadPreBuffer(nextIndex + 1, queue)
        viewModelScope.launch(Dispatchers.IO) {
            com.streamify.app.media.playback.QueueEngine.ensureQueueDepth(this@handleAutomaticTimelineTransition)
        }
    } else {
        advanceQueue(isUserSkip = false)
    }
}

/**
 * HISTORY OVERHAUL: drops every queue entry BEFORE the current index.
 * Now-playing and Up Next are untouched; indices re-base to zero so the
 * History section can be wiped without disturbing live playback.
 */

internal fun PlayerViewModel.armLookaheadPreBuffer(nextIndex: Int, queue: List<Track>) {
    if (nextIndex >= queue.size) return
    val nextTrack = queue[nextIndex]
    lookaheadJob?.cancel()
    lookaheadJob = viewModelScope.launch(Dispatchers.IO) {
        // If upcoming queue is low (<= 2 songs remaining), proactively prefetch next radio batch
        if (queue.size - nextIndex <= 2 && _playerState.value.isAutoPlayEnabled) {
            try {
                val seed = queue.lastOrNull() ?: nextTrack
                val fresh = com.streamify.app.radio.UniversalCandidateBroker.fetchCandidates(
                    seedTrack = seed,
                    activeQueue = queue,
                    targetCount = 15
                )
                if (fresh.isNotEmpty()) {
                    withContext(Dispatchers.Main) {
                        val liveQ = _playerState.value.queue.toMutableList()
                        for (ft in fresh) {
                            val isDup = liveQ.any {
                                com.streamify.app.data.discovery.FuzzyTitleMatcher.isSameSongVariation(it.title, it.artist, ft.title, ft.artist)
                            }
                            if (!isDup) {
                                liveQ.add(ft)
                            }
                        }
                        _playerState.value = _playerState.value.copy(queue = liveQ)
                    }
                }
            } catch (e: Exception) {
                // Non-fatal prefetch error
            }
        }

        try {
            val res = com.streamify.app.data.network.YouTubeStreamResolver.resolveStreamJit(nextTrack)
            val resolved = res.getOrNull()
            if (resolved != null && resolved.streamUrl.isNotBlank()) {
                val vid = com.streamify.app.data.network.YouTubeStreamResolver.extractVideoId(resolved.streamUrl)
                    ?: nextTrack.ytmVideoId
                val warmTrack = nextTrack.copy(filepath = resolved.streamUrl, ytmVideoId = vid ?: nextTrack.ytmVideoId)
                val lookaheadItem = buildMediaItem(warmTrack)
                withContext(Dispatchers.Main) {
                    val ctrl = controller ?: try { controllerFuture?.get() } catch (_: Throwable) { null }
                    ctrl?.let { c ->
                        if (c.mediaItemCount == 1) {
                            c.addMediaItem(lookaheadItem)
                        } else if (c.mediaItemCount > 1) {
                            c.replaceMediaItem(1, lookaheadItem)
                        }
                    }
                }
            }

            appContext?.let { ctx ->
                try {
                    val upcomingSlice = queue.subList(nextIndex, queue.size)
                    com.streamify.app.media.cache.PredictivePreBufferManager(ctx).preBufferUpcomingTracks(upcomingSlice)
                } catch (e: Exception) {
                    // Non-fatal pre-buffer error
                }
            }
        } catch (e: Exception) {
            // Non-fatal background lookahead error
        }
    }
}

internal fun PlayerViewModel.preResolveLookaheadTrack(currentIndex: Int, queue: List<Track>) {
    if (currentIndex < 0 || currentIndex >= queue.size - 1) return
    val nextTrack = queue[currentIndex + 1]
    val trackKey = "${nextTrack.title}_${nextTrack.artist}".lowercase()
    if (preResolvingTrackKey == trackKey) return

    val needsResolution = nextTrack.filepath.isBlank() ||
            nextTrack.filepath.startsWith("online://") ||
            nextTrack.filepath.startsWith("ytsearch:") ||
            (nextTrack.filepath.startsWith("http") && !nextTrack.filepath.contains("googlevideo.com")) ||
            (nextTrack.filepath.contains("googlevideo.com") && isCdnExpired(nextTrack.filepath))

    if (needsResolution) {
        preResolvingTrackKey = trackKey
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val res = com.streamify.app.data.network.YouTubeStreamResolver.resolveStreamJit(nextTrack)
                val resolved = res.getOrNull()
                if (resolved != null && resolved.streamUrl.isNotBlank()) {
                    val vid = com.streamify.app.data.network.YouTubeStreamResolver.extractVideoId(resolved.streamUrl)
                        ?: nextTrack.ytmVideoId
                    val warmTrack = nextTrack.copy(filepath = resolved.streamUrl, ytmVideoId = vid ?: nextTrack.ytmVideoId)
                    withContext(Dispatchers.Main) {
                        val currentQ = _playerState.value.queue
                        if (currentIndex + 1 < currentQ.size && (currentQ[currentIndex + 1].id == nextTrack.id || currentQ[currentIndex + 1].title == nextTrack.title)) {
                            val updatedQ = currentQ.toMutableList()
                            updatedQ[currentIndex + 1] = warmTrack
                            _playerState.value = _playerState.value.copy(queue = updatedQ)
                            val ctrl = controller ?: try { controllerFuture?.get() } catch (_: Throwable) { null }
                            ctrl?.let { c ->
                                val warmItem = buildMediaItem(warmTrack)
                                if (c.mediaItemCount > 1) {
                                    c.replaceMediaItem(1, warmItem)
                                } else if (c.mediaItemCount == 1) {
                                    c.addMediaItem(warmItem)
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                // Ignore background pre-resolve error
            } finally {
                if (preResolvingTrackKey == trackKey) preResolvingTrackKey = null
            }
        }
    }
}

internal fun PlayerViewModel.hydrateContinuumRadio(seedTrack: Track) {
    if (!_playerState.value.isAutoPlayEnabled) return
    hydrateJob?.cancel()
    hydrateJob = viewModelScope.launch(Dispatchers.Default) {
        try {
            val currentQ = _playerState.value.queue

            // Harvest full 25+ candidate batch across Innertube, Spotify, and Local
            val radioTracks = com.streamify.app.radio.UniversalCandidateBroker.fetchCandidates(
                seedTrack = seedTrack,
                activeQueue = currentQ,
                targetCount = 25
            )

            if (radioTracks.isNotEmpty()) {
                // O(1) Root Hash & Session History Deduplication: Skip already played songs
                val uniqueCandidates = radioTracks.filter { candidate ->
                    val hash = com.streamify.app.data.discovery.FuzzyTitleMatcher.extractRootHash(candidate.title)
                    if (hash == 0L || processedTitleHashes.contains(hash) || sessionPlayedTrackIds.contains(candidate.id)) {
                        false
                    } else {
                        processedTitleHashes.add(hash)
                        if (candidate.id != 0) sessionPlayedTrackIds.add(candidate.id)
                        true
                    }
                }

                if (uniqueCandidates.isNotEmpty()) {
                    withContext(Dispatchers.Main) {
                        val currentQueue = _playerState.value.queue.toMutableList()
                        for (rt in uniqueCandidates) {
                            val isDup = currentQueue.any {
                                com.streamify.app.data.discovery.FuzzyTitleMatcher.isSameSongVariation(it.title, it.artist, rt.title, rt.artist)
                            }
                            if (!isDup) {
                                currentQueue.add(rt)
                            }
                        }
                        _playerState.value = _playerState.value.copy(queue = currentQueue)
                    }
                }
            }
        } catch (e: Exception) {
            SLog.st("PlayerViewModel", "PlayerViewModel.hydrateContinuumRadio failed", e)
        }
    }
}
