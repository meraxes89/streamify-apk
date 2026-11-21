package com.streamify.sovereign.player

import com.maxrave.domain.data.entities.NewFormatEntity
import com.maxrave.domain.data.model.browse.album.Track
import com.maxrave.domain.data.model.mediaService.SponsorSkipSegments
import com.maxrave.domain.data.player.GenericCastState
import com.maxrave.domain.data.player.GenericCommandButton
import com.maxrave.domain.data.player.GenericMediaItem
import com.maxrave.domain.data.player.PlayerError
import com.maxrave.domain.mediaservice.handler.ControlState
import com.maxrave.domain.mediaservice.handler.MediaPlayerHandler
import com.maxrave.domain.mediaservice.handler.NowPlayingTrackState
import com.maxrave.domain.mediaservice.handler.PlayerEvent
import com.maxrave.domain.mediaservice.handler.QueueData
import com.maxrave.domain.mediaservice.handler.RepeatState
import com.maxrave.domain.mediaservice.handler.SimpleMediaState
import com.maxrave.domain.mediaservice.handler.SleepTimerState
import com.maxrave.domain.mediaservice.handler.ToastType
import com.maxrave.domain.mediaservice.player.MediaPlayerInterface
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// ─────────────────────────────────────────────────────────────────────────────
// Test doubles: a recording MediaPlayerHandler (every member counts its
// calls) and a recording Sovereign lane. Both are PURE — no platform, no
// Koin — so the suite runs on every target the module builds for.
// ─────────────────────────────────────────────────────────────────────────────

internal class RecordingHandler : MediaPlayerHandler {
    var onPlayerEventCalls = 0
    var releaseCalls = 0
    var lastEvent: PlayerEvent? = null

    override val player: MediaPlayerInterface = NoopPlayer
    override val simpleMediaState: StateFlow<SimpleMediaState> =
        MutableStateFlow(SimpleMediaState.Initial).asStateFlow()
    override val nowPlaying: StateFlow<GenericMediaItem?> =
        MutableStateFlow(null).asStateFlow()
    override val queueData: StateFlow<QueueData?> = MutableStateFlow(null).asStateFlow()
    override val controlState: StateFlow<ControlState> = MutableStateFlow(
        ControlState(false, false, RepeatState.None, false, false, false, false, 1f),
    ).asStateFlow()
    override val nowPlayingState: StateFlow<NowPlayingTrackState> =
        MutableStateFlow(NowPlayingTrackState.initial()).asStateFlow()
    override val sleepTimerState: StateFlow<SleepTimerState> =
        MutableStateFlow(SleepTimerState(false, 0)).asStateFlow()
    override val skipSegments: StateFlow<List<SponsorSkipSegments>?> =
        MutableStateFlow(null).asStateFlow()
    override val format: StateFlow<NewFormatEntity?> = MutableStateFlow(null).asStateFlow()
    override val currentSongIndex: StateFlow<Int> = MutableStateFlow(0).asStateFlow()
    override val castState: StateFlow<GenericCastState> =
        MutableStateFlow(GenericCastState(false, null)).asStateFlow()

    override var onUpdateNotification: (List<GenericCommandButton>) -> Unit = {}
    override var pushPlayerError: (PlayerError) -> Unit = {}
    override var showToast: (ToastType) -> Unit = {}

    override suspend fun onPlayerEvent(playerEvent: PlayerEvent) {
        onPlayerEventCalls += 1
        lastEvent = playerEvent
    }

    override fun toggleRadio() {}
    override fun toggleLike() {}
    override fun like(liked: Boolean) {}
    override fun resetSongAndQueue() {}
    override fun sleepStart(minutes: Int) {}
    override fun sleepStop() {}
    override fun removeMediaItem(position: Int) {}
    override fun addMediaItem(
        mediaItem: GenericMediaItem,
        playWhenReady: Boolean,
    ) {}
    override fun clearMediaItems() {}
    override fun addMediaItemList(mediaItemList: List<GenericMediaItem>) {}
    override fun playMediaItemInMediaSource(index: Int) {}
    override fun currentSongIndex(): Int = 0
    override fun currentOrderIndex(): Int = 0
    override suspend fun swap(
        from: Int,
        to: Int,
    ) {}
    override fun resetCrossfade() {}
    override fun shufflePlaylist(randomTrackIndex: Int) {}
    override fun loadMore() {}
    override fun getRelated(videoId: String) {}
    override fun setQueueData(queueData: QueueData.Data) {}
    override fun getCurrentMediaItem(): GenericMediaItem? = null
    override suspend fun moveItemUp(position: Int) {}
    override suspend fun moveItemDown(position: Int) {}
    override suspend fun moveItemToPlayNext(position: Int) {}
    override fun addFirstMediaItemToIndex(
        mediaItem: GenericMediaItem?,
        index: Int,
    ) {}
    override fun reset() {}
    override suspend fun load(
        downloaded: Int,
        index: Int?,
    ) {}
    override suspend fun loadMoreCatalog(
        listTrack: ArrayList<Track>,
        isAddToQueue: Boolean,
    ) {}
    override suspend fun updateCatalog(
        downloaded: Int,
        index: Int?,
    ): Boolean = true
    override fun addQueueToPlayer() {}
    override fun loadPlaylistOrAlbum(index: Int?) {}
    override fun setCurrentSongIndex(index: Int) {}
    override suspend fun playNext(track: Track) {}
    override suspend fun <T> loadMediaItem(
        anyTrack: T,
        type: String,
        index: Int?,
    ) {}
    override fun getPlayerDuration(): Long = 0L
    override fun getProgress(): Long = 0L
    override fun startProgressUpdate() {}
    override fun stopProgressUpdate() {}
    override fun startBufferedUpdate() {}
    override fun stopBufferedUpdate() {}
    override fun mayBeNormalizeVolume() {}
    override fun mayBeSaveRecentSong(runBlocking: Boolean) {}
    override fun mayBeSavePlaybackState() {}
    override fun mayBeRestoreQueue() {}
    override suspend fun restoreQueueAndPlay(): Boolean = false
    override fun shouldReleaseOnTaskRemoved(): Boolean = false
    override fun release() {
        releaseCalls += 1
    }
}

internal object NoopPlayer : MediaPlayerInterface {
    override fun play() {}
    override fun pause() {}
    override fun stop() {}
    override fun seekTo(positionMs: Long) {}
    override fun seekTo(
        mediaItemIndex: Int,
        positionMs: Long,
    ) {}
    override fun seekBack() {}
    override fun seekForward() {}
    override fun seekToNext() {}
    override fun seekToPrevious() {}
    override fun seekToPreviousMediaItem() {}
    override fun prepare() {}
    override fun setMediaItem(mediaItem: GenericMediaItem) {}
    override fun addMediaItem(mediaItem: GenericMediaItem) {}
    override fun addMediaItem(
        index: Int,
        mediaItem: GenericMediaItem,
    ) {}
    override fun removeMediaItem(index: Int) {}
    override fun moveMediaItem(
        fromIndex: Int,
        toIndex: Int,
    ) {}
    override fun clearMediaItems() {}
    override fun replaceMediaItem(
        index: Int,
        mediaItem: GenericMediaItem,
    ) {}
    override fun getMediaItemAt(index: Int): GenericMediaItem? = null
    override fun getCurrentMediaTimeLine(): List<GenericMediaItem> = emptyList()
    override fun getUnshuffledIndex(shuffledIndex: Int): Int = shuffledIndex
    override val isPlaying: Boolean get() = false
    override val currentPosition: Long get() = 0L
    override val duration: Long get() = 0L
    override val bufferedPosition: Long get() = 0L
    override val bufferedPercentage: Int get() = 0
    override val currentMediaItem: GenericMediaItem? get() = null
    override val currentMediaItemIndex: Int get() = 0
    override val mediaItemCount: Int get() = 0
    override val contentPosition: Long get() = 0L
    override val playbackState: Int get() = 0
    override fun hasNextMediaItem(): Boolean = false
    override fun hasPreviousMediaItem(): Boolean = false
    override var shuffleModeEnabled: Boolean = false
    override var repeatMode: Int = 0
    override var playWhenReady: Boolean = true
    override var playbackParameters: com.maxrave.domain.data.player.GenericPlaybackParameters =
        com.maxrave.domain.data.player.GenericPlaybackParameters(1f, 1f)
    override val audioSessionId: Int get() = 0
    override var volume: Float = 1f
    override var sleepFadeFactor: Float = 1f
    override var crossfadeSuppressed: Boolean = false
    override var albumTrackIds: Set<String> = emptySet()
    override var skipSilenceEnabled: Boolean = false
    override fun addListener(listener: com.maxrave.domain.mediaservice.player.MediaPlayerListener) {}
    override fun removeListener(listener: com.maxrave.domain.mediaservice.player.MediaPlayerListener) {}
    override fun release() {}
}

private class RecordingLane : SovereignPlaybackLane {
    var events = 0
    var releases = 0
    override val player: MediaPlayerInterface = NoopPlayer
    override suspend fun onPlayerEvent(
        event: PlayerEvent,
        legacy: MediaPlayerHandler,
    ) {
        events += 1
    }
    override fun release() {
        releases += 1
    }
}

private class FixedProbe(
    private val available: Boolean,
    private val reason: String,
) : SovereignHalProbe {
    override fun isBracketAvailable(): Boolean = available
    override fun lastUnavailableReason(): String = reason
}

// ─────────────────────────────────────────────────────────────────────────────
// The route ladder (pure, no platform).
// ─────────────────────────────────────────────────────────────────────────────

class SovereignRoutePolicyTest {
    @Test
    fun flag_off_delegates_everything() {
        SovereignHalFlags.setEnabled(false)
        val policy = SovereignRoutePolicy(FixedProbe(true, ""))
        assertEquals(SovereignRoute.FlagOff, policy.decide())
        assertFalse(policy.shouldUseSovereign())
    }

    @Test
    fun flag_on_healthy_probe_routes_sovereign() {
        SovereignHalFlags.setEnabled(true)
        try {
            val policy = SovereignRoutePolicy(FixedProbe(true, ""))
            assertEquals(SovereignRoute.SovereignRing, policy.decide())
            assertTrue(policy.shouldUseSovereign())
        } finally {
            SovereignHalFlags.setEnabled(false)
        }
    }

    @Test
    fun flag_on_dead_probe_falls_back_with_reason() {
        SovereignHalFlags.setEnabled(true)
        try {
            val policy = SovereignRoutePolicy(FixedProbe(false, "bracket not wired"))
            val decision = policy.decide()
            assertTrue(decision is SovereignRoute.LegacyFallback)
            assertEquals("bracket not wired", (decision as SovereignRoute.LegacyFallback).reason)
            assertFalse(policy.shouldUseSovereign())
        } finally {
            SovereignHalFlags.setEnabled(false)
        }
    }

    @Test
    fun toggling_the_flag_live_re_routes() {
        val policy = SovereignRoutePolicy(FixedProbe(true, ""))
        SovereignHalFlags.setEnabled(false)
        assertEquals(SovereignRoute.FlagOff, policy.decide())
        SovereignHalFlags.setEnabled(true)
        assertEquals(SovereignRoute.SovereignRing, policy.decide())
        SovereignHalFlags.setEnabled(false)
        assertEquals(SovereignRoute.FlagOff, policy.decide())
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// The wrapper: delegation vs Sovereign routing + the release contract.
// ─────────────────────────────────────────────────────────────────────────────

class SovereignMediaPlayerHandlerTest {
    @Test
    fun flag_off_delegates_events_and_release() = runTest {
        SovereignHalFlags.setEnabled(false)
        val legacy = RecordingHandler()
        val lane = RecordingLane()
        val handler = SovereignMediaPlayerHandler(
            delegate = legacy,
            routePolicy = SovereignRoutePolicy(FixedProbe(true, "")),
            monitor = SovereignHalMonitor(),
            sovereignLane = lane,
        )
        handler.onPlayerEvent(PlayerEvent.PlayPause)
        assertEquals(1, legacy.onPlayerEventCalls)
        assertEquals(0, lane.events)
        handler.release()
        assertEquals(1, legacy.releaseCalls)
    }

    @Test
    fun flag_on_routes_events_to_the_lane_not_the_delegate() = runTest {
        SovereignHalFlags.setEnabled(true)
        try {
            val legacy = RecordingHandler()
            val lane = RecordingLane()
            val handler = SovereignMediaPlayerHandler(
                delegate = legacy,
                routePolicy = SovereignRoutePolicy(FixedProbe(true, "")),
                monitor = SovereignHalMonitor(),
                sovereignLane = lane,
            )
            handler.onPlayerEvent(PlayerEvent.PlayPause)
            assertEquals(1, lane.events)
            assertEquals(0, legacy.onPlayerEventCalls, "the audio control rides the ring lane")
        } finally {
            SovereignHalFlags.setEnabled(false)
        }
    }

    @Test
    fun probe_failure_mid_session_degrades_to_delegate() = runTest {
        // A bracket that dies mid-session: the ladder re-evaluates on the
        // NEXT command (the zero-crash guarantee's runtime half).
        val flaky = object : SovereignHalProbe {
            var healthy = true
            override fun isBracketAvailable(): Boolean = healthy
            override fun lastUnavailableReason(): String = "bracket died mid-session"
        }
        SovereignHalFlags.setEnabled(true)
        try {
            val legacy = RecordingHandler()
            val lane = RecordingLane()
            val monitor = SovereignHalMonitor()
            val handler = SovereignMediaPlayerHandler(
                delegate = legacy,
                routePolicy = SovereignRoutePolicy(flaky),
                monitor = monitor,
                sovereignLane = lane,
            )
            handler.onPlayerEvent(PlayerEvent.PlayPause)
            assertEquals(1, lane.events)

            flaky.healthy = false
            handler.onPlayerEvent(PlayerEvent.PlayPause)
            assertEquals(1, lane.events, "no new lane events after the probe died")
            assertEquals(1, legacy.onPlayerEventCalls, "degraded to the legacy chain")
            assertEquals(1, monitor.fallbacks.value)
        } finally {
            SovereignHalFlags.setEnabled(false)
        }
    }

    @Test
    fun release_tears_down_both_lanes() = runTest {
        SovereignHalFlags.setEnabled(true)
        try {
            val legacy = RecordingHandler()
            val lane = RecordingLane()
            val handler = SovereignMediaPlayerHandler(
                delegate = legacy,
                routePolicy = SovereignRoutePolicy(FixedProbe(true, "")),
                monitor = SovereignHalMonitor(),
                sovereignLane = lane,
            )
            handler.release()
            assertEquals(1, lane.releases)
            assertEquals(1, legacy.releaseCalls, "the delegate (fallback owner) is always released")
        } finally {
            SovereignHalFlags.setEnabled(false)
        }
    }

    @Test
    fun player_surface_follows_the_route() {
        SovereignHalFlags.setEnabled(true)
        try {
            val legacy = RecordingHandler()
            val lane = RecordingLane()
            val handler = SovereignMediaPlayerHandler(
                delegate = legacy,
                routePolicy = SovereignRoutePolicy(FixedProbe(true, "")),
                monitor = SovereignHalMonitor(),
                sovereignLane = lane,
            )
            assertTrue(handler.player === lane.player, "sovereign route exposes the lane player")
            SovereignHalFlags.setEnabled(false)
            assertTrue(handler.player === legacy.player, "flag-off exposes the delegate player")
        } finally {
            SovereignHalFlags.setEnabled(false)
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// The monitor (the badge's truth source).
// ─────────────────────────────────────────────────────────────────────────────

class SovereignHalMonitorTest {
    @Test
    fun records_routes_and_fallback_reasons() {
        val monitor = SovereignHalMonitor()
        monitor.recordRoute(SovereignRoute.FlagOff)
        assertEquals(SovereignRoute.FlagOff, monitor.route.value)
        assertEquals(0, monitor.fallbacks.value)

        monitor.recordRoute(SovereignRoute.SovereignRing)
        assertEquals(SovereignRoute.SovereignRing, monitor.route.value)

        monitor.recordRoute(SovereignRoute.LegacyFallback("probe failed"))
        assertEquals(1, monitor.fallbacks.value)
        assertEquals("probe failed", monitor.lastFallbackReason.value)
    }

    @Test
    fun ingests_ring_telemetry() {
        val monitor = SovereignHalMonitor()
        monitor.recordRingTelemetry(framesPulled = 48_000L, underruns = 0L)
        assertEquals(48_000L, monitor.framesPulled.value)
        assertEquals(0L, monitor.underruns.value)
    }
}
