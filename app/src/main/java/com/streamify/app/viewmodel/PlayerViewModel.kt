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

class PlayerViewModel(internal val repository: com.streamify.app.data.repository.TrackRepositoryApi = com.streamify.app.data.repository.TrackRepository) : ViewModel(),
    com.streamify.app.jam.JamEngine.Bridge {

    // ── JamEngine.Bridge: live-player facade for the Lockstep protocol ──
    override fun loadTrack(track: Track, positionMs: Long, play: Boolean) {
        playTrack(track, listOf(track), autoHydrateRadio = false)
        if (positionMs > 0L) seekTo(positionMs)
        if (!play) pause()
    }

    override fun setPlaying(play: Boolean) {
        if (play) this@PlayerViewModel.play() else this@PlayerViewModel.pause()
    }

    internal val _playerState = MutableStateFlow(PlayerState())

    val playerState: StateFlow<PlayerState> = _playerState.asStateFlow()

    // ── HOT POSITION FLOWS ──────────────────────────────────────────
    // The 200ms poller writes ONLY these. `playerState` (track/queue/flags)
    // stays stable between discrete events, so collectors at the composition
    // root no longer recompose the whole tree five times per second.
    // Leaves (seekbars, time labels) collect these locally and redraw alone.

    internal val _positionMs = MutableStateFlow(0L)

    val positionMs: StateFlow<Long> = _positionMs.asStateFlow()

    internal val _durationMs = MutableStateFlow(0L)

    val durationMs: StateFlow<Long> = _durationMs.asStateFlow()

    val currentTrack: StateFlow<Track?> = _playerState
        .map { it.currentTrack }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val progressFraction: StateFlow<Float> = combine(_positionMs, _durationMs) { pos, dur ->
        if (dur > 0L) (pos.toFloat() / dur.toFloat()).coerceIn(0f, 1f) else 0f
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0f)

    internal var controllerFuture: ListenableFuture<MediaController>? = null

    internal var controller: MediaController? = null

    internal var appContext: Context? = null

    internal var positionPollingJob: Job? = null

    private var sleepTimerJob: Job? = null

    private var lastPlayedTrackId: Int? = null

    internal var preResolvingTrackKey: String? = null

    internal var lookaheadJob: Job? = null

    private var playJob: Job? = null

    // Bounded auto-advance: consecutive stream-resolution failures before playback
    // halts instead of churning the queue forever behind a pinned spinner.
    // Exactly three write sites: reset in playTrack(), reset on success,
    // incremented in registerResolutionFailure(). Nothing else may touch it.

    internal var hydrateJob: Job? = null

    internal var pendingSeekTargetMs: Long? = null

    internal var isOptimisticSeeking: Boolean = false

    internal var seekTimeoutJob: Job? = null

    internal val processedTitleHashes = java.util.Collections.synchronizedSet(mutableSetOf<Long>())

    internal val sessionPlayedTrackIds = java.util.Collections.synchronizedSet(mutableSetOf<Int>())
    // Dedup guard for lyric fetches: key = "trackId:videoIdOrEmpty" so a retry is only
    // allowed when the video identity actually improved (e.g. after DB registration).

    internal val lyricsFetchAttempts = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())

    private var consecutiveDeadSkips = 0

    private val urlRetryAttempts = java.util.concurrent.ConcurrentHashMap<String, Int>()

    init {
        com.streamify.app.jam.JamEngine.attachBridge(this)
    }

    private val isAdvancing = java.util.concurrent.atomic.AtomicBoolean(false)

    internal var playbackStartTimeMs: Long = 0L

    fun getController(): MediaController? = controller

    private val isVideoSwitching = java.util.concurrent.atomic.AtomicBoolean(false)

    fun toggleVideoMode(enabled: Boolean) {
        _playerState.value = _playerState.value.copy(isVideoMode = enabled)
        val ctrl = controller ?: return
        val currentT = _playerState.value.currentTrack ?: return
        val currentPos = ctrl.currentPosition
        val wasPlaying = ctrl.isPlaying
        val currentIndex = ctrl.currentMediaItemIndex

        if (isVideoSwitching.compareAndSet(false, true)) {
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    if (enabled) {
                        val videoStream = com.streamify.app.data.network.YouTubeStreamResolver.resolveVideoStreamUrl(currentT)
                        if (videoStream != null && videoStream.streamUrl.isNotBlank()) {
                            withContext(Dispatchers.Main) {
                                val currentItem = ctrl.currentMediaItem ?: return@withContext
                                val videoMediaItem = currentItem.buildUpon()
                                    .setUri(android.net.Uri.parse(videoStream.streamUrl))
                                    .setMimeType(androidx.media3.common.MimeTypes.VIDEO_MP4)
                                    .build()
                                
                                if (currentIndex in 0 until ctrl.mediaItemCount) {
                                    ctrl.replaceMediaItem(currentIndex, videoMediaItem)
                                    ctrl.seekTo(currentIndex, currentPos)
                                    if (wasPlaying) ctrl.play()
                                }
                            }
                        } else {
                            withContext(Dispatchers.Main) {
                                UiEventBus.emitEvent(UiEvent.ShowSnackbar("Music video stream unavailable for this track"))
                            }
                        }
                    } else {
                        val audioStream = com.streamify.app.data.network.YouTubeStreamResolver.resolveTrackStream(currentT)
                        val audioUrl = audioStream?.streamUrl ?: currentT.filepath
                        if (audioUrl.isNotBlank()) {
                            withContext(Dispatchers.Main) {
                                val currentItem = ctrl.currentMediaItem ?: return@withContext
                                val audioMediaItem = currentItem.buildUpon()
                                    .setUri(android.net.Uri.parse(audioUrl))
                                    .setMimeType(audioStream?.mimeType ?: androidx.media3.common.MimeTypes.AUDIO_WEBM)
                                    .build()
                                
                                if (currentIndex in 0 until ctrl.mediaItemCount) {
                                    ctrl.replaceMediaItem(currentIndex, audioMediaItem)
                                    ctrl.seekTo(currentIndex, currentPos)
                                    if (wasPlaying) ctrl.play()
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    SLog.st("PlayerViewModel", "PlayerViewModel.toggleVideoMode failed", e)
                } finally {
                    isVideoSwitching.set(false)
                }
            }
        }
    }

    fun initialize(context: Context) {
        val appCtx = context.applicationContext
        appContext = appCtx
        // NOTE: TrackRepository.appContext is owned by AppGraph.initialize
        // (single-writer rule) — it is already bound before any ViewModel exists.
        if (controllerFuture != null) return

        val sessionToken = SessionToken(appCtx, ComponentName(appCtx, PlaybackService::class.java))
        controllerFuture = MediaController.Builder(appCtx, sessionToken).buildAsync()
        controllerFuture?.addListener({
            controller = controllerFuture?.get()
            setupController(appCtx)
            restorePlayerState(appCtx)
            com.streamify.app.data.persistence.SmartOfflineVaultEngine.initialize(appCtx)
        }, MoreExecutors.directExecutor())
    }

    fun savePlayerState(context: Context) {
        val prefs = context.getSharedPreferences("player_state", Context.MODE_PRIVATE)
        val state = _playerState.value
        if (state.queue.isNotEmpty()) {
            val queueIds = state.queue.map { it.id }.joinToString(",")
            val currentId = state.currentTrack?.id ?: -1
            prefs.edit()
                .putString("saved_queue", queueIds)
                .putInt("saved_current_id", currentId)
                .putLong("saved_position", state.currentPosition)
                .apply()
        }
    }

    internal val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {

            _playerState.value = _playerState.value.copy(isPlaying = isPlaying)
            if (isPlaying) startPollingPosition() else stopPollingPosition()
            if (!isApplyingJamSync && com.streamify.app.jam.JamEngine.activeSession() != null) {
                broadcastJamAction(if (isPlaying) "PLAY" else "PAUSE", isPlaying = isPlaying)
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            val d = controller?.duration ?: 0L
            if (d > 0) {
                val curr = _playerState.value.currentTrack
                val updated = if (curr != null && curr.durationSec <= 0) {
                    curr.copy(durationSec = (d / 1000).toInt())
                } else curr
                _positionMs.value = controller?.currentPosition?.coerceAtLeast(0L) ?: 0L
                _durationMs.value = d
                _playerState.value = _playerState.value.copy(
                    duration = d,
                    currentPosition = controller?.currentPosition?.coerceAtLeast(0L) ?: 0L,
                    currentTrack = updated
                )
            }

            when (playbackState) {
                Player.STATE_ENDED -> {
                    // Failsafe: Reached end of physical timeline without pre-buffered slot ready
                    if (!isAdvancing.get()) {
                        advanceQueue(isUserSkip = false)
                    }
                }
                Player.STATE_BUFFERING -> {
                    _playerState.value = _playerState.value.copy(isBuffering = true)
                }
                Player.STATE_READY -> {
                    consecutiveDeadSkips = 0
                    urlRetryAttempts.clear()
                    isOptimisticSeeking = false
                    pendingSeekTargetMs = null
                    seekTimeoutJob?.cancel()
                    _playerState.value = _playerState.value.copy(isBuffering = false)
                }
                else -> {}
            }
        }

        override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
            val d = controller?.duration ?: 0L
            if (d > 0) {
                val curr = _playerState.value.currentTrack
                val updated = if (curr != null && curr.durationSec <= 0) {
                    curr.copy(durationSec = (d / 1000).toInt())
                } else curr
                _positionMs.value = controller?.currentPosition?.coerceAtLeast(0L) ?: 0L
                _durationMs.value = d
                _playerState.value = _playerState.value.copy(
                    duration = d,
                    currentPosition = controller?.currentPosition?.coerceAtLeast(0L) ?: 0L,
                    currentTrack = updated
                )
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            runCatching {
                com.streamify.app.data.NativeBridge.nativeResetAudioDSP()
            }

            seekTimeoutJob?.cancel()
            isOptimisticSeeking = false
            pendingSeekTargetMs = null

            when (reason) {
                Player.MEDIA_ITEM_TRANSITION_REASON_AUTO -> {
                    // Pre-buffered follower in physical timeline slot 1 transitioned automatically
                    handleAutomaticTimelineTransition()
                }
                Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED -> {
                    val curState = _playerState.value
                    val activeTrack = curState.currentTrack
                    val expectedTrack = curState.queue.getOrNull(curState.currentIndex)
                    if (activeTrack != null && expectedTrack != null && (activeTrack.id == expectedTrack.id || (activeTrack.title == expectedTrack.title && activeTrack.artist == expectedTrack.artist))) {
                        // Already aligned with current queue index, ignore secondary playlist mutation event
                    } else {
                        updateCurrentTrackFromMediaItem(mediaItem)
                    }
                }
                else -> {
                    updateCurrentTrackFromMediaItem(mediaItem)
                }
            }
            
            val currentT = _playerState.value.currentTrack
            val newTrackId = mediaItem?.mediaId?.removePrefix("trk_")?.toIntOrNull() ?: currentT?.id?.takeIf { it > 0 }
            if (currentT != null && newTrackId != null) {
                viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    try {
                        val registered = repository.registerStreamedTrack(currentT, appContext)
                        val validId = if (registered.id > 0) registered.id else newTrackId ?: 0
                        if (validId > 0) {
                            repository.updateSessionVector(validId, 0.45f)
                            repository.recordTrackPlay(validId)
                        }
                        appContext?.let { ctx ->
                            com.streamify.app.data.repository.EdgeMeshRepository.getInstance(ctx).scheduleOpportunisticCompute(
                                context = ctx,
                                trackId = (if (validId > 0) validId else currentT.id).toString(),
                                trackTitle = currentT.title,
                                trackArtist = currentT.artist,
                                audioPath = currentT.filepath
                            )
                        }
                    } catch (e: Exception) {
                        SLog.st("PlayerViewModel", "PlayerViewModel.onMediaItemTransition failed", e)
                    }
                }
            }
            
            // Project Chronos AI & Circadian Event Logging: Track change
            if (lastPlayedTrackId != null && newTrackId != null && lastPlayedTrackId != newTrackId) {
                val wasSkipped = reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK || 
                                 reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO
                val currentHour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
                val posSec = ((controller?.currentPosition ?: 0L) / 1000L).toInt()
                val durSec = if ((controller?.duration ?: 0L) > 0) ((controller?.duration ?: 0L) / 1000L).toInt() else (_playerState.value.currentTrack?.durationSec ?: 0)
                val ratio = if (durSec > 0) (posSec.toFloat() / durSec.toFloat()).coerceIn(0f, 1f) else 0.5f

                // Real-Time Cloud Telemetry Sync
                val currentTrackObj = _playerState.value.currentTrack
                val prevTrackId = lastPlayedTrackId
                if (currentTrackObj != null && posSec >= 10) {
                    viewModelScope.launch(Dispatchers.IO) {
                        try {
                            val cleanSig = (currentTrackObj.title.trim().lowercase() + "_" + currentTrackObj.artist.trim().lowercase())
                            val cloudId = "trk_${kotlin.math.abs(cleanSig.hashCode())}"
                            val eventJson = org.json.JSONObject().apply {
                                put("track_id", cloudId)
                                put("track_title", currentTrackObj.title)
                                put("track_artist", currentTrackObj.artist)
                                put("duration_sec", posSec.toLong())
                                put("completion_ratio", ratio.toDouble())
                                put("hour_of_day", currentHour)
                                put("action_type", if (wasSkipped && ratio < 0.85f) "SKIP" else "PLAY")
                            }
                            com.streamify.app.data.supabase.SupabaseClient.ingestTelemetryBatch(listOf(eventJson))
                        } catch (e: Exception) {
                            // Non-blocking telemetry failure
                        }
                    }
                }

                if (prevTrackId != null) {
                    viewModelScope.launch(Dispatchers.IO) {
                        try {
                            if (wasSkipped && ratio < 0.85f) {
                                repository.logSkipEvent(prevTrackId, newTrackId)
                            } else {
                                repository.logPlayEvent(prevTrackId, newTrackId)
                            }
                        } catch (e: Exception) {
                            // Non-blocking
                        }
                    }
                }
            }
            lastPlayedTrackId = newTrackId
            
            // Automatic Dual-Engine Lyrics Scraping & Sync Cache Dispatch.
            // PlayerViewModel is the SINGLE lyric network fetch owner: UI surfaces only
            // read from cache, preventing duplicate races and unverified fuzzy matches.
            maybeFetchLyricsForTrack(_playerState.value.currentTrack)
            
            // Smart Acoustic EQ Profile auto-adaptation
            val currentPlaying = _playerState.value.currentTrack
            if (currentPlaying != null) {
                viewModelScope.launch(Dispatchers.IO) {
                    try {
                        com.streamify.app.data.network.SmartAcousticEngine.getSmartEqProfile(currentPlaying)
                    } catch (e: Exception) {}
                }
            }
            
            if (_playerState.value.sleepTimerEndTrack && reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                controller?.pause()
                _playerState.value = _playerState.value.copy(sleepTimerEndTrack = false, sleepTimerMinutesLeft = null)
            }

            // CONTINUUM INFINITE RADIO: When approaching the end of the queue (or queue size <= 2), fetch next radio batch
            if (_playerState.value.isAutoPlayEnabled) {
                val currentQueue = _playerState.value.queue
                val currentIdx = _playerState.value.currentIndex

                if (currentIdx >= currentQueue.size - 2) {
                    viewModelScope.launch(Dispatchers.IO) {
                        val currentT = _playerState.value.currentTrack
                        if (currentT != null) {
                            val continuumRecs = com.streamify.app.radio.UniversalCandidateBroker.fetchCandidates(
                                seedTrack = currentT,
                                activeQueue = currentQueue,
                                targetCount = 15
                            )
                            if (continuumRecs.isNotEmpty()) {
                                val currentQ = _playerState.value.queue.toMutableList()
                                for (track in continuumRecs) {
                                    val isDup = currentQ.any {
                                        com.streamify.app.data.discovery.FuzzyTitleMatcher.isSameSongVariation(it.title, it.artist, track.title, track.artist)
                                    }
                                    if (!isDup) {
                                        currentQ.add(track)
                                    }
                                }
                                val newQueue = currentQ.distinctBy { it.id }
                                withContext(kotlinx.coroutines.Dispatchers.Main) {
                                    _playerState.value = _playerState.value.copy(queue = newQueue)
                                }
                            }
                        }
                    }
                }
            }
        }
        
        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
            _playerState.value = _playerState.value.copy(isShuffleActive = shuffleModeEnabled)
        }
        
        override fun onRepeatModeChanged(repeatMode: Int) {
            _playerState.value = _playerState.value.copy(isRepeatActive = repeatMode != Player.REPEAT_MODE_OFF)
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int
        ) {
            if (reason == Player.DISCONTINUITY_REASON_SEEK) {
                // Hardware confirmed seek completion
                pendingSeekTargetMs = null
                isOptimisticSeeking = false
                _playerState.value = _playerState.value.copy(currentPosition = newPosition.positionMs)
                _positionMs.value = newPosition.positionMs.coerceAtLeast(0L)
            }

            if (_playerState.value.isAutoPlayEnabled) {
                val currentIdx = _playerState.value.currentIndex
                val currentQueue = _playerState.value.queue
                if (currentQueue.isNotEmpty() && currentQueue.size - currentIdx <= 2) {
                    viewModelScope.launch(Dispatchers.IO) {
                        val currentT = _playerState.value.currentTrack
                        if (currentT != null) {
                            val newTracks = com.streamify.app.radio.UniversalCandidateBroker.fetchCandidates(
                                seedTrack = currentT,
                                activeQueue = currentQueue,
                                targetCount = 15
                            )
                            if (newTracks.isNotEmpty()) {
                                val currentQ = _playerState.value.queue.toMutableList()
                                for (track in newTracks) {
                                    val isDup = currentQ.any {
                                        com.streamify.app.data.discovery.FuzzyTitleMatcher.isSameSongVariation(it.title, it.artist, track.title, track.artist)
                                    }
                                    if (!isDup) {
                                        currentQ.add(track)
                                    }
                                }
                                withContext(kotlinx.coroutines.Dispatchers.Main) {
                                    _playerState.value = _playerState.value.copy(queue = currentQ)
                                }
                            }
                        }
                    }
                }
            }
        }

        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            val resumePos = controller?.currentPosition?.coerceAtLeast(0L) ?: 0L
            pendingSeekTargetMs = null
            isOptimisticSeeking = false

            // If PlaybackService is actively renewing the CDN token in-place, defer to it
            val renewalMediaId = com.streamify.app.media.playback.PlaybackService.lastRenewalMediaId
            val renewalAt = com.streamify.app.media.playback.PlaybackService.lastRenewalAtMs
            if (renewalMediaId != null && (System.currentTimeMillis() - renewalAt) < 3000L) {
                return
            }

            val currentT = _playerState.value.currentTrack
            if (currentT != null) {
                val trackKey = currentT.ytmVideoId ?: "${currentT.title}:${currentT.artist}"
                val attempts = urlRetryAttempts[trackKey] ?: 0
                if (attempts >= 2) {
                    urlRetryAttempts.remove(trackKey)
                    _playerState.value = _playerState.value.copy(
                        isBuffering = false,
                        isPlaying = false,
                        lastError = "Playback failed for '${currentT.title}' after ${attempts + 1} attempts (player error)"
                    )
                    UiEventBus.emitEvent(UiEvent.ShowSnackbar("Playback failed for '${currentT.title}'. Tap to retry."))
                    return
                }
                urlRetryAttempts[trackKey] = attempts + 1

                currentT.ytmVideoId?.let { com.streamify.app.data.network.StreamEdgeCache.evictStream(it) }

                viewModelScope.launch(Dispatchers.IO) {
                    try {
                        val resolved = com.streamify.app.data.network.YouTubeStreamResolver.resolveTrackStream(currentT, forceFresh = true)
                        if (resolved != null && resolved.streamUrl.isNotBlank()) {
                            val updated = currentT.copy(filepath = resolved.streamUrl)
                            withContext(kotlinx.coroutines.Dispatchers.Main) {
                                val currentQueue = _playerState.value.queue.toMutableList()
                                val idx = currentQueue.indexOfFirst {
                                    it.id == currentT.id || (it.title == currentT.title && it.artist == currentT.artist)
                                }
                                if (idx >= 0) {
                                    currentQueue[idx] = updated
                                    _playerState.value = _playerState.value.copy(queue = currentQueue, currentTrack = updated)
                                    val mediaItem = buildMediaItem(updated)
                                    controller?.setMediaItem(mediaItem, resumePos)
                                    controller?.prepare()
                                    controller?.play()
                                }
                            }
                        } else {
                            withContext(kotlinx.coroutines.Dispatchers.Main) {
                                _playerState.value = _playerState.value.copy(isBuffering = false, isPlaying = false)
                                UiEventBus.emitEvent(UiEvent.ShowSnackbar("Could not play '${currentT.title}'. Tap to retry."))
                            }
                        }
                    } catch (e: Exception) {
                        SLog.st("PlayerViewModel", "PlayerViewModel.onPlayerError failed", e)
                        withContext(kotlinx.coroutines.Dispatchers.Main) {
                            _playerState.value = _playerState.value.copy(isBuffering = false, isPlaying = false)
                            UiEventBus.emitEvent(UiEvent.ShowSnackbar("Could not play '${currentT.title}'. Tap to retry."))
                        }
                    }
                }
            } else {
                _playerState.value = _playerState.value.copy(isBuffering = false, isPlaying = false)
            }
        }
    }

    fun toggleAutoPlay() {
        _playerState.value = _playerState.value.copy(isAutoPlayEnabled = !_playerState.value.isAutoPlayEnabled)
    }

    fun playNext(track: Track) {
        val currentQueue = _playerState.value.queue.toMutableList()
        val currentIndex = _playerState.value.currentIndex
        val insertIndex = (currentIndex + 1).coerceAtMost(currentQueue.size)

        currentQueue.add(insertIndex, track)
        _playerState.value = _playerState.value.copy(queue = currentQueue)
        armLookaheadPreBuffer(currentIndex + 1, currentQueue)
    }

    fun addToQueue(track: Track) {
        val currentQueue = _playerState.value.queue.toMutableList()
        currentQueue.add(track)
        _playerState.value = _playerState.value.copy(queue = currentQueue)
        val currentIndex = _playerState.value.currentIndex
        if (currentIndex + 1 == currentQueue.size - 1) {
            armLookaheadPreBuffer(currentIndex + 1, currentQueue)
        }
    }

    internal var jamTickerJob: kotlinx.coroutines.Job? = null

    fun playFromSearch(tappedTrack: Track, searchContext: List<Track> = listOf(tappedTrack)) {
        playTrack(tappedTrack, searchContext.ifEmpty { listOf(tappedTrack) }, autoHydrateRadio = true)
    }

    var isApplyingJamSync: Boolean = false

    /**
     * Party Mode audio discipline (Phase 1, Gap #13): when the room runs
     * SINGLE_RENDER, guests suppress LOCAL audio output — the host speaker
     * is the only renderer. Implemented as player volume 0 (NOT pausing the
     * decoder): the PLL + resampler keep running silently so a mid-party
     * flip back to MULTI_RENDER re-locks audio with zero handshake latency.
     *
     * Restores the pre-suppression volume on un-suppress.
     */
    private var jamSuppressedVolumeFraction: Float? = null

    fun setJamRenderSuppressed(suppressed: Boolean) {
        val ctrl = controller ?: return
        try {
            if (suppressed) {
                if (jamSuppressedVolumeFraction == null) {
                    val current = ctrl.volume
                    if (current > 0f) jamSuppressedVolumeFraction = current
                }
                if (ctrl.volume > 0f) ctrl.volume = 0f
            } else {
                jamSuppressedVolumeFraction?.let { restore ->
                    ctrl.volume = restore
                }
                jamSuppressedVolumeFraction = null
            }
        } catch (e: Exception) {
            com.streamify.app.util.SLog.st("PlayerViewModel", "setJamRenderSuppressed failed", e)
        }
    }

    /**
     * Live playhead with controller-first fallback to the hot position flow.
     * `playerState.currentPosition` is now a cold, event-time field (seek
     * confirmations only) — never read it for "current" position.
     */

    fun currentPositionMs(): Long =
        controller?.currentPosition?.coerceAtLeast(0L) ?: _positionMs.value

    fun broadcastJamAction(
        action: String,
        track: Track? = _playerState.value.currentTrack,
        positionMs: Long = currentPositionMs(),
        isPlaying: Boolean = _playerState.value.isPlaying
    ) {
        if (isApplyingJamSync) return
        if (com.streamify.app.jam.JamEngine.activeSession() == null) return
        // Lockstep routing: hosts emit authoritative epochs, members emit
        // policy-checked intents — receiver-side gates enforce authority.
        com.streamify.app.jam.JamEngine.onLocalPlaybackAction(
            action = action, track = track, positionMs = positionMs, isPlaying = isPlaying
        )
    }

    fun playSingleTrack(track: Track) {
        com.streamify.app.media.playback.QueueEngine.playSingle(track, this)
    }

    fun playCollection(tracks: List<Track>, startIndex: Int = 0) {
        com.streamify.app.media.playback.QueueEngine.playCollection(tracks, startIndex, this)
    }

    fun updateQueueSilently(newQueue: List<Track>, newIndex: Int = _playerState.value.currentIndex) {
        val hydrated = newQueue.map { repository.hydrateTrack(it) }
        val safeIndex = newIndex.coerceIn(0, (hydrated.size - 1).coerceAtLeast(0))
        _playerState.value = _playerState.value.copy(queue = hydrated, currentIndex = safeIndex)
    }

    fun appendToQueue(tracks: List<Track>) {
        if (tracks.isEmpty()) return
        val hydrated = tracks.map { repository.hydrateTrack(it) }
        val currentQ = _playerState.value.queue.toMutableList()
        val existingKeys = currentQ.map { "${it.title}:::${it.artist}".lowercase() }.toSet()
        val uniqueNew = hydrated.filterNot { existingKeys.contains("${it.title}:::${it.artist}".lowercase()) }
        if (uniqueNew.isNotEmpty()) {
            currentQ.addAll(uniqueNew)
            _playerState.value = _playerState.value.copy(queue = currentQ)
        }
    }

    fun playTrack(track: Track, queue: List<Track> = listOf(track), autoHydrateRadio: Boolean = true) {
        // Manual entry point = fresh user intent → fresh failure budget.
        val hydratedTrack = repository.hydrateTrack(track)
        val hydratedQueue = queue.map { qTrack ->
            if (qTrack.id == track.id || (qTrack.title.equals(track.title, ignoreCase = true) && qTrack.artist.equals(track.artist, ignoreCase = true))) {
                hydratedTrack
            } else {
                repository.hydrateTrack(qTrack)
            }
        }
        val targetIndex = hydratedQueue.indexOfFirst {
            // Identity-safe matching: a bare title collision must never hijack the
            // queue slot of a different song (different artists, covers, remixes).
            (it.id != 0 && it.id == hydratedTrack.id) ||
                (it.filepath.isNotBlank() && it.filepath == hydratedTrack.filepath) ||
                (it.title == hydratedTrack.title && it.artist == hydratedTrack.artist)
        }.takeIf { it >= 0 } ?: 0

        // 1. Immediately update UI state and pause old track for instantaneous tactile response
        _playerState.value = _playerState.value.copy(
            currentTrack = hydratedTrack,
            currentIndex = targetIndex,
            queue = hydratedQueue,
            isPlaying = true,
            isBuffering = true
        )
        controller?.pause()

        // Broadcast Track Change to active Jam room
        broadcastJamAction("TRACK_CHANGE", track = hydratedTrack, positionMs = 0L, isPlaying = true)

        // 2. Tracked Single Job - cancel previous resolution jobs to prevent race conditions
        playJob?.cancel()
        playJob = viewModelScope.launch {
            try {
                // Register track and prime hash deduplication set without clearing history
                sessionPlayedTrackIds.add(hydratedTrack.id)
                val playedH = com.streamify.app.data.discovery.FuzzyTitleMatcher.extractRootHash(hydratedTrack.title)
                if (playedH != 0L) processedTitleHashes.add(playedH)

                for (t in hydratedQueue) {
                    val h = com.streamify.app.data.discovery.FuzzyTitleMatcher.extractRootHash(t.title)
                    if (h != 0L) processedTitleHashes.add(h)
                    if (t.id != 0) sessionPlayedTrackIds.add(t.id)
                }

                com.streamify.app.data.ingestion.NeuroQueueManager.onTrackStarted(hydratedTrack)
                playTrackInternal(hydratedTrack, targetIndex, hydratedQueue)

                // Asynchronously hydrate upcoming continuum radio queue seeded directly from the tapped track
                if (autoHydrateRadio) {
                    hydrateContinuumRadio(hydratedTrack)
                }
            } catch (ce: kotlinx.coroutines.CancellationException) {
                throw ce // superseded by a newer playTrack / VM teardown — never a failure
            } catch (t: Throwable) {
                // Safety net: an unknown throw between the optimistic buffering=true above
                // and completion must never strand the spinner. Known resolution failures
                // are already routed through registerResolutionFailure() inside
                // playTrackInternal(); unknown bugs get a safe stop, no auto-advance.
                SLog.e("PlayerViewModel", "playTrack coroutine died", t)
                _playerState.value = _playerState.value.copy(isBuffering = false, isPlaying = false)
                UiEventBus.emitEvent(UiEvent.ShowSnackbar("Playback error — please try again"))
            }
        }
    }

    fun clearPlayedHistory() {
        val cur = _playerState.value
        if (cur.currentIndex <= 0) return
        val remaining = cur.queue.drop(cur.currentIndex)
        _playerState.value = cur.copy(queue = remaining, currentIndex = 0)
    }

    fun advanceQueue(isUserSkip: Boolean = false) {
        if (!isAdvancing.compareAndSet(false, true)) return
        viewModelScope.launch {
            try {
                lookaheadJob?.cancel()

                // JAM LOCKSTEP: room advancement belongs to the host alone.
                // Host pops the shared queue head; guests deliberately idle —
                // personal radio must never hijack a live session.
                if (com.streamify.app.jam.JamEngine.interceptAdvance()) return@launch
                val curState = _playerState.value
                val queue = curState.queue
                if (queue.isEmpty()) return@launch

                // Fast Skip Guard: Only trigger emergency comfort track if queue is exhausted and no upcoming tracks exist
                if (isUserSkip && curState.currentTrack != null && curState.currentIndex + 1 >= queue.size && !curState.isAutoPlayEnabled) {
                    val dwellTime = System.currentTimeMillis() - playbackStartTimeMs
                    if (dwellTime in 1..9999L) {
                        val comfortTrack = withContext(Dispatchers.IO) {
                            repository.getEmergencyComfortTrack()
                        }
                        if (comfortTrack != null && comfortTrack.id != curState.currentTrack?.id) {
                            SLog.d("PlayerViewModel", "⚡ Fast-skip (<10s) on exhausted queue! Triggering comfort anchor: ${comfortTrack.title}")
                            playTrackInternal(comfortTrack, 0, listOf(comfortTrack) + queue.filter { it.id != comfortTrack.id })
                            return@launch
                        }
                    }
                }

                val currentIndex = curState.currentIndex
                val isAutoplayEnabled = curState.isAutoPlayEnabled
                val isRepeatActive = curState.isRepeatActive
                val ctrl = controller

                if (isRepeatActive && ctrl?.repeatMode == Player.REPEAT_MODE_ONE && !isUserSkip) {
                    ctrl.seekTo(0L)
                    ctrl.play()
                    return@launch
                }

                val nextIndex = currentIndex + 1
                when {
                    // Path A: Next track exists within current queue bounds
                    nextIndex < queue.size -> {
                        playTrackInternal(queue[nextIndex], nextIndex, queue)
                        viewModelScope.launch(Dispatchers.IO) {
                            com.streamify.app.media.playback.QueueEngine.ensureQueueDepth(this@PlayerViewModel)
                        }
                    }
                    // Path B: End of queue reached with REPEAT_ALL enabled
                    nextIndex >= queue.size && isRepeatActive && queue.isNotEmpty() -> {
                        playTrackInternal(queue.first(), 0, queue)
                    }
                    // Path C: End of queue reached with AUTOPLAY enabled (Continuum Radio Engine)
                    nextIndex >= queue.size && isAutoplayEnabled && queue.isNotEmpty() -> {
                        _playerState.value = _playerState.value.copy(isBuffering = true)
                        val seedTrack = queue.last()
                        val freshCandidates = withContext(Dispatchers.IO) {
                            com.streamify.app.radio.OnlineRadioEngine.fetchCandidates(
                                seedTrack = seedTrack,
                                activeQueue = queue,
                                targetCount = 15
                            )
                        }
                        if (freshCandidates.isNotEmpty()) {
                            val updatedQueue = queue.toMutableList()
                            for (cand in freshCandidates) {
                                val isDup = updatedQueue.any {
                                    com.streamify.app.data.discovery.FuzzyTitleMatcher.isSameSongVariation(it.title, it.artist, cand.title, cand.artist)
                                }
                                if (!isDup) {
                                    updatedQueue.add(cand)
                                }
                            }
                            _playerState.value = _playerState.value.copy(queue = updatedQueue)
                            playTrackInternal(freshCandidates.first(), nextIndex, updatedQueue)
                        } else {
                            ctrl?.pause()
                            _playerState.value = _playerState.value.copy(isPlaying = false, isBuffering = false)
                        }
                    }
                    // Path D: Queue fully exhausted
                    else -> {
                        ctrl?.pause()
                        _playerState.value = _playerState.value.copy(isPlaying = false, isBuffering = false)
                    }
                }
            } finally {
                isAdvancing.set(false)
            }
        }
    }

    /**
     * Single-owner lyric network fetch. Resolves the strongest available YouTube video
     * identity and forwards it to the verified LyricsResolver pipeline (exact pinned
     * video → same-song-gated provider race). Results are persisted to:
     *  1. The canonical app-private LRU cache (shared by every UI surface), and
     *  2. A user-accessible .lrc mirror under Downloads/.Streamify/lyrics (best effort).
     * Attempts are deduplicated per (trackId, videoId): a retry is only permitted when
     * the resolved video identity improves (e.g. after async DB registration pins it).
     */

    fun togglePlayPause() {
        val ctrl = controller ?: return
        if (ctrl.isPlaying) {
            ctrl.pause()
            broadcastJamAction("PAUSE", isPlaying = false)
        } else {
            ctrl.play()
            broadcastJamAction("PLAY", isPlaying = true)
        }
    }

    fun play() {
        controller?.play()
        broadcastJamAction("PLAY", isPlaying = true)
    }

    fun pause() {
        controller?.pause()
        broadcastJamAction("PAUSE", isPlaying = false)
    }

    fun setPlaybackSpeed(speed: Float) {
        val ctrl = controller ?: return
        val currentSpeed = ctrl.playbackParameters.speed
        if (kotlin.math.abs(currentSpeed - speed) > 0.001f) {
            ctrl.playbackParameters = androidx.media3.common.PlaybackParameters(speed, 1.0f)
        }
    }

    /** Live playback speed — lets the PLL avoid redundant IPC on HOLD. */

    fun playbackSpeed(): Float =
        try { controller?.playbackParameters?.speed ?: 1.0f } catch (_: Throwable) { 1.0f }

    fun getAcousticPositionMs(): Long {
        val rawPos = controller?.currentPosition ?: _playerState.value.currentPosition
        return com.streamify.app.media.playback.PlaybackService.syncAudioProcessor.getAcousticPositionMs(rawPos)
    }

    fun scheduleAtomicPlayback(
        track: Track,
        targetAtomicTimestampMs: Long,
        startPositionMs: Long = 0L,
        precisionProtocol: com.streamify.app.media.sync.PrecisionTimeProtocol
    ) {
        val ctrl = controller ?: return
        val scheduler = com.streamify.app.media.sync.ScheduledAudioScheduler(ctrl, precisionProtocol)
        _playerState.value = _playerState.value.copy(currentTrack = track, queue = listOf(track))
        scheduler.scheduleAtomicPlayback(track, targetAtomicTimestampMs, startPositionMs) {
            _playerState.value = _playerState.value.copy(isPlaying = true)
        }
    }

    fun seekRelative(deltaMs: Long) {
        val currentPos = currentPositionMs()
        seekTo(currentPos + deltaMs)
    }

    fun seekTo(positionMs: Long) {
        val ctrl = controller ?: return
        val currentT = _playerState.value.currentTrack
        val maxDurationMs = if (_playerState.value.duration > 0) _playerState.value.duration else ((currentT?.durationSec ?: 0) * 1000L)
        val validPos = if (maxDurationMs > 0) positionMs.coerceIn(0L, maxDurationMs) else positionMs.coerceAtLeast(0L)
        
        // 1. Enter optimistic seeking state to prevent poller snapback
        isOptimisticSeeking = true
        pendingSeekTargetMs = validPos
        _playerState.value = _playerState.value.copy(currentPosition = validPos)
        _positionMs.value = validPos

        seekTimeoutJob?.cancel()
        seekTimeoutJob = viewModelScope.launch {
            kotlinx.coroutines.delay(1200)
            isOptimisticSeeking = false
            pendingSeekTargetMs = null
        }

        // 2. Dispatch IPC seek command to ExoPlayer
        ctrl.seekTo(validPos)
        broadcastJamAction("SEEK", positionMs = validPos)
        
        if (currentT != null && currentT.id > 0) {
            NativeBridge.pushTelemetryEvent(NativeBridge.EVENT_SCRUB_SEEK, currentT.id.toLong(), validPos.toFloat())
        }
    }

    fun logLyricsDwell(dwellSeconds: Int) {
        val currentT = _playerState.value.currentTrack
        if (currentT != null && currentT.id > 0 && dwellSeconds > 0) {
            NativeBridge.pushTelemetryEvent(NativeBridge.EVENT_LYRICS_DWELL, currentT.id.toLong(), dwellSeconds.toFloat())
        }
    }

    fun logVolumeFlare() {
        val currentT = _playerState.value.currentTrack
        if (currentT != null && currentT.id > 0) {
            NativeBridge.pushTelemetryEvent(NativeBridge.EVENT_VOLUME_CHANGE, currentT.id.toLong(), 1.0f)
        }
    }

    fun skipNext() {
        advanceQueue(isUserSkip = true)
    }

    fun skipPrevious() {
        val ctrl = controller
        if (ctrl != null && ctrl.currentPosition > 3000L) {
            ctrl.seekTo(0L)
            return
        }
        viewModelScope.launch {
            val curState = _playerState.value
            val queue = curState.queue
            val currentIndex = curState.currentIndex
            val prevIndex = (currentIndex - 1).coerceAtLeast(0)
            if (queue.isNotEmpty() && prevIndex != currentIndex) {
                playTrackInternal(queue[prevIndex], prevIndex, queue)
            } else {
                ctrl?.seekTo(0L)
            }
        }
    }

    fun toggleShuffle() {
        val ctrl = controller ?: return
        ctrl.shuffleModeEnabled = !ctrl.shuffleModeEnabled
    }

    fun toggleRepeat() {
        val ctrl = controller ?: return
        ctrl.repeatMode = if (ctrl.repeatMode == Player.REPEAT_MODE_OFF) Player.REPEAT_MODE_ALL else Player.REPEAT_MODE_OFF
    }

    fun toggleLike(trackToToggle: Track? = null, context: android.content.Context? = null) {
        val currentTrack = trackToToggle ?: _playerState.value.currentTrack ?: return

        // 1. Launch authoritative DB toggle operation
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val actualIsLiked = repository.toggleLike(currentTrack.id, track = currentTrack)
                withContext(Dispatchers.Main) {
                    val updatedQueue = _playerState.value.queue.map { item ->
                        if ((item.id != 0 && item.id == currentTrack.id) || 
                            (item.filepath.isNotBlank() && item.filepath == currentTrack.filepath) ||
                            (item.title.equals(currentTrack.title, ignoreCase = true) && item.artist.equals(currentTrack.artist, ignoreCase = true))) {
                            item.copy(isLiked = actualIsLiked)
                        } else item
                    }
                    val updatedCurrent = if (_playerState.value.currentTrack?.let { 
                        (it.id != 0 && it.id == currentTrack.id) || 
                        (it.filepath.isNotBlank() && it.filepath == currentTrack.filepath) ||
                        (it.title.equals(currentTrack.title, ignoreCase = true) && it.artist.equals(currentTrack.artist, ignoreCase = true))
                    } == true) {
                        _playerState.value.currentTrack?.copy(isLiked = actualIsLiked)
                    } else {
                        _playerState.value.currentTrack
                    }

                    _playerState.value = _playerState.value.copy(
                        queue = updatedQueue,
                        currentTrack = updatedCurrent
                    )

                    val msg = if (actualIsLiked) "Added to Liked Songs" else "Removed from Liked Songs"
                    UiEventBus.emitEvent(UiEvent.ShowSnackbar(msg))
                }

                if (actualIsLiked && currentTrack.filepath.isNotBlank()) {
                    com.streamify.app.media.cache.AudioCacheManager.markStickyTrack(currentTrack.filepath)
                }

                // Auto-download liked online songs if setting is enabled
                if (actualIsLiked && (currentTrack.filepath.startsWith("http") || currentTrack.source.contains("online", ignoreCase = true))) {
                    try {
                        com.streamify.app.viewmodel.IngestionViewModel.enqueueDownloadDirect(
                            url = if (currentTrack.filepath.startsWith("http")) currentTrack.filepath else "https://www.youtube.com/watch?v=${currentTrack.id}",
                            title = currentTrack.title,
                            artist = currentTrack.artist,
                            album = "Streamify",
                            quality = "320"
                        )
                    } catch (e: Exception) {
                        SLog.st("PlayerViewModel", "PlayerViewModel.toggleLike failed", e)
                    }
                }
            } catch (e: Exception) {
                SLog.st("PlayerViewModel", "PlayerViewModel.toggleLike failed", e)
            }
        }
    }

    fun removeFromQueue(trackId: Int) {
        val curState = _playerState.value
        val currentQueue = curState.queue.toMutableList()
        val index = currentQueue.indexOfFirst { it.id == trackId }
        if (index != -1) {
            currentQueue.removeAt(index)
            val newCurrentIndex = when {
                index < curState.currentIndex -> curState.currentIndex - 1
                index == curState.currentIndex -> curState.currentIndex.coerceAtMost(currentQueue.size - 1)
                else -> curState.currentIndex
            }
            _playerState.value = curState.copy(
                queue = currentQueue,
                currentIndex = newCurrentIndex.coerceAtLeast(0)
            )
            try {
                controller?.removeMediaItem(index)
            } catch (e: Exception) {
                SLog.st("PlayerViewModel", "PlayerViewModel.removeFromQueue failed", e)
            }
        }
    }

    fun reorderQueue(fromIndex: Int, toIndex: Int) {
        val curState = _playerState.value
        val currentQueue = curState.queue.toMutableList()
        if (fromIndex in currentQueue.indices && toIndex in currentQueue.indices && fromIndex != toIndex) {
            val item = currentQueue.removeAt(fromIndex)
            currentQueue.add(toIndex, item)
            val oldCurrent = curState.currentIndex
            val newCurrentIndex = when {
                oldCurrent == fromIndex -> toIndex
                fromIndex < oldCurrent && toIndex >= oldCurrent -> oldCurrent - 1
                fromIndex > oldCurrent && toIndex <= oldCurrent -> oldCurrent + 1
                else -> oldCurrent
            }
            _playerState.value = curState.copy(
                queue = currentQueue,
                currentIndex = newCurrentIndex
            )
            try {
                controller?.moveMediaItem(fromIndex, toIndex)
            } catch (e: Exception) {
                SLog.st("PlayerViewModel", "PlayerViewModel.reorderQueue failed", e)
            }
        }
    }

    fun clearQueue() {
        _playerState.value = _playerState.value.copy(queue = emptyList())
        try {
            controller?.clearMediaItems()
        } catch (e: Exception) {
            SLog.st("PlayerViewModel", "PlayerViewModel.clearQueue failed", e)
        }
    }

    fun startSongRadio(seedTrack: Track? = null) {
        val currentT = _playerState.value.currentTrack
        val target = seedTrack ?: currentT ?: return
        val isCurrentPlaying = currentT != null && (target.id == currentT.id || (target.title == currentT.title && target.artist == currentT.artist))

        viewModelScope.launch {
            val radioTracks = com.streamify.app.radio.UniversalCandidateBroker.fetchCandidates(
                seedTrack = target,
                activeQueue = if (isCurrentPlaying) _playerState.value.queue else emptyList(),
                targetCount = 20
            )
            if (radioTracks.isNotEmpty()) {
                if (isCurrentPlaying) {
                    // Seamlessly append to active queue without interrupting or restarting current track timestamp!
                    val currentQueue = _playerState.value.queue
                    val existingIds = currentQueue.map { it.id }.toSet()
                    val existingTitles = currentQueue.map { "${it.title}_${it.artist}".lowercase() }.toSet()
                    val newTracks = radioTracks.filter {
                        (it.id == 0 || !existingIds.contains(it.id)) &&
                                !existingTitles.contains("${it.title}_${it.artist}".lowercase())
                    }

                    if (newTracks.isNotEmpty()) {
                        val updatedQueue = currentQueue + newTracks
                        _playerState.value = _playerState.value.copy(queue = updatedQueue)
                        val newMediaItems = newTracks.map { buildMediaItem(it) }
                        controller?.addMediaItems(newMediaItems)
                        UiEventBus.emitEvent(UiEvent.ShowSnackbar("Appended ${newTracks.size} Radio tracks to queue 📻"))
                    } else {
                        UiEventBus.emitEvent(UiEvent.ShowSnackbar("Radio tracks already in queue"))
                    }
                } else {
                    val fullList = listOf(target) + radioTracks.filter { it.id != target.id }
                    playTrack(target, fullList)
                    UiEventBus.emitEvent(UiEvent.ShowSnackbar("Started ${target.title} Radio 📻"))
                }
            } else {
                UiEventBus.emitEvent(UiEvent.ShowSnackbar("Could not load radio for this track"))
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Phase 3 — Smart Shuffle: interleave algorithmic recommendations into
    // the ACTIVE queue with distinct badges + reshuffle trigger. The pure
    // interleave math lives in player/SmartShuffleEngine (unit-tested);
    // this section owns state + the glitch-free controller sync.
    // ═══════════════════════════════════════════════════════════════════════

    private val _smartShuffleInjectedIds = MutableStateFlow<Set<Int>>(emptySet())
    /** Queue items with these ids render the "Recommended" badge in QueueScreen. */
    val smartShuffleInjectedIds: StateFlow<Set<Int>> = _smartShuffleInjectedIds.asStateFlow()

    private var smartShuffleJob: Job? = null

    val isSmartShuffleActive: Boolean
        get() = _smartShuffleInjectedIds.value.isNotEmpty()

    /** Toggle: enable when idle, strip + disable when active. */
    fun toggleSmartShuffle() {
        if (isSmartShuffleActive) disableSmartShuffle() else enableSmartShuffle()
    }

    fun enableSmartShuffle() {
        val state = _playerState.value
        val seed = state.currentTrack ?: state.queue.firstOrNull() ?: return
        if (state.queue.isEmpty()) return

        smartShuffleJob?.cancel()
        smartShuffleJob = viewModelScope.launch {
            val recommendations = com.streamify.app.radio.UniversalCandidateBroker.fetchCandidates(
                seedTrack = seed,
                activeQueue = state.queue,
                targetCount = 24
            )
            if (recommendations.isEmpty()) {
                UiEventBus.emitEvent(UiEvent.ShowSnackbar("No recommendations for Smart Shuffle"))
                return@launch
            }
            val result = com.streamify.app.player.SmartShuffleEngine.interleave(
                queue = state.queue,
                currentIndex = state.currentIndex,
                recommendations = recommendations
            )
            if (result.injectedCount == 0) {
                UiEventBus.emitEvent(UiEvent.ShowSnackbar("Queue already covers your recommendations"))
                return@launch
            }
            applySmartShuffleResult(result, previousQueue = state.queue)
            UiEventBus.emitEvent(UiEvent.ShowSnackbar("Smart Shuffle added ${result.injectedCount} recommendations ✨"))
        }
    }

    /** Reshuffle: strip the previous injection, interleave a fresh batch. */
    fun reshuffleSmartShuffle() {
        val state = _playerState.value
        val seed = state.currentTrack ?: state.queue.firstOrNull() ?: return
        if (state.queue.isEmpty()) return

        smartShuffleJob?.cancel()
        smartShuffleJob = viewModelScope.launch {
            val recommendations = com.streamify.app.radio.UniversalCandidateBroker.fetchCandidates(
                seedTrack = seed,
                activeQueue = state.queue.filter { it.id !in _smartShuffleInjectedIds.value },
                targetCount = 24
            )
            val result = com.streamify.app.player.SmartShuffleEngine.reshuffle(
                queue = state.queue,
                currentIndex = state.currentIndex,
                previousInjectedIds = _smartShuffleInjectedIds.value,
                freshRecommendations = recommendations
            )
            if (result.injectedCount == 0) {
                UiEventBus.emitEvent(UiEvent.ShowSnackbar("No fresh recommendations right now"))
                return@launch
            }
            applySmartShuffleResult(result, previousQueue = state.queue)
            UiEventBus.emitEvent(UiEvent.ShowSnackbar("Reshuffled in ${result.injectedCount} new picks ✨"))
        }
    }

    /** Strips all injected recommendations; playback of the current track never breaks. */
    fun disableSmartShuffle() {
        val injected = _smartShuffleInjectedIds.value
        if (injected.isEmpty()) return
        val state = _playerState.value
        val playingId = state.currentTrack?.id
        val userQueue = state.queue.filterIndexed { index, track ->
            (index == state.currentIndex) || (track.id !in injected)
        }
        val newIndex = playingId?.let { id -> userQueue.indexOfFirst { it.id == id } } ?: -1
        applyQueueToControllerTail(
            newQueue = userQueue,
            currentIndex = newIndex.coerceAtLeast(0),
            oldQueueSize = state.queue.size
        )
        _playerState.value = _playerState.value.copy(queue = userQueue, currentIndex = newIndex.coerceAtLeast(0))
        _smartShuffleInjectedIds.value = emptySet()
        UiEventBus.emitEvent(UiEvent.ShowSnackbar("Smart Shuffle off"))
    }

    /**
     * Applies an engine result: state update + glitch-free controller sync.
     * Only the queue TAIL beyond the protected window (current + next) is
     * rebuilt — the playing item is never re-prepared, so no audible seam.
     */
    private fun applySmartShuffleResult(
        result: com.streamify.app.player.SmartShuffleEngine.Result,
        previousQueue: List<Track>
    ) {
        val state = _playerState.value
        applyQueueToControllerTail(
            newQueue = result.queue,
            currentIndex = state.currentIndex,
            oldQueueSize = previousQueue.size
        )
        _playerState.value = _playerState.value.copy(queue = result.queue)
        _smartShuffleInjectedIds.value = result.injectedIds
    }

    /**
     * Rebuilds the controller's queue tail after [protectedUntil] = current
     * + next. Falls back to a full setMediaItems only when the controller and
     * the logical queue have drifted (defensive — should not happen).
     */
    private fun applyQueueToControllerTail(
        newQueue: List<Track>,
        currentIndex: Int,
        oldQueueSize: Int
    ) {
        val ctrl = controller ?: return
        val protectedUntil = (currentIndex + 1 + com.streamify.app.player.SmartShuffleEngine.MIN_PROTECTED_AHEAD)
            .coerceAtMost(newQueue.size)
        try {
            val itemCount = ctrl.mediaItemCount
            if (itemCount == oldQueueSize || itemCount >= protectedUntil) {
                // Surgical tail rebuild: drop everything beyond the protected
                // window, then insert the new tail in queue order.
                if (itemCount > protectedUntil) {
                    ctrl.removeMediaItems(protectedUntil, itemCount)
                }
                if (newQueue.size > protectedUntil) {
                    val tailItems = newQueue.drop(protectedUntil).map { buildMediaItem(it) }
                    ctrl.addMediaItems(protectedUntil, tailItems)
                }
            } else {
                // Drifted controller: full rebuild anchored at the current track.
                val positionMs = ctrl.currentPosition.coerceAtLeast(0L)
                ctrl.setMediaItems(newQueue.map { buildMediaItem(it) }, currentIndex, positionMs)
                ctrl.prepare()
                if (_playerState.value.isPlaying) ctrl.play()
            }
        } catch (e: Exception) {
            SLog.st("PlayerViewModel", "PlayerViewModel.applyQueueToControllerTail failed", e)
        }
    }

    fun setSleepTimer(minutes: Int?, endOfTrack: Boolean = false) {
        sleepTimerJob?.cancel()
        _playerState.value = _playerState.value.copy(
            sleepTimerMinutesLeft = minutes,
            sleepTimerEndTrack = endOfTrack
        )
        if (minutes != null) {
            sleepTimerJob = viewModelScope.launch {
                var remaining = minutes
                while (remaining > 0) {
                    delay(60000L) // 1 minute
                    remaining--
                    _playerState.value = _playerState.value.copy(sleepTimerMinutesLeft = remaining)
                }
                controller?.pause()
                _playerState.value = _playerState.value.copy(sleepTimerMinutesLeft = null, sleepTimerEndTrack = false)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        com.streamify.app.media.playback.PlaybackService.onSeekNextListener = null
        com.streamify.app.media.playback.PlaybackService.onSeekPrevListener = null
        controller?.removeListener(playerListener)
        controller = null
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
    }

}
