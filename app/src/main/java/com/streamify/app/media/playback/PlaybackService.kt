package com.streamify.app.media.playback
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.streamify.app.media.audio.AudioRoutePolicy
import com.streamify.app.media.audio.AudioTrackPullOutput
import com.streamify.app.media.audio.NativeFloatSinkEdge
import com.streamify.app.media.audio.RustAudioPipeline
import com.streamify.app.media.audio.RustMedia3AudioSink
import com.streamify.app.data.network.YouTubeStreamResolver
import com.streamify.app.data.uniffi.MediaCommand
import com.streamify.app.data.uniffi.StreamifyEngineBridge
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.streamify.app.util.SLog
import com.streamify.app.media.audio.StreamifyAudioProcessor
import com.streamify.app.media.audio.SyncAudioProcessor
import com.streamify.app.media.audio.CrossfadeAudioProcessor
import com.streamify.app.media.audio.DolbySpatialManager
import com.streamify.app.media.audio.EqualizerManager
import com.streamify.app.media.audio.AudioDeviceManager
import com.streamify.app.media.cache.AudioCacheManager
import com.streamify.app.media.cache.PredictivePreBufferManager

class PlaybackService : MediaSessionService() {

    private var jamServiceScope: kotlinx.coroutines.CoroutineScope? = null

    /** Scope for the Phase 3 media-command debounce actor (MP-C03/C17). */
    private var mediaCommandScope: kotlinx.coroutines.CoroutineScope? = null
    // Scope for one-shot error recovery work (CDN re-resolution etc). Lives
    // with the service so recovery jobs never outlive it, unlike the previous
    // ad-hoc CoroutineScope(...).launch per error which leaked unmanaged jobs.
    private var errorRecoveryScope: kotlinx.coroutines.CoroutineScope? = null

    // ── STRICT ARCHITECTURE FIREWALL (Phase 4) ──
    //
    // 1. ZERO UI POLLING — playback position, buffer fullness and DSP
    //    telemetry are pure StateFlow consumers: PlayerTicker publishes
    //    positionMs/durationMs into dedicated flows (the UI root never
    //    recomposes for them), and buffer telemetry now advances purely
    //    from the engine's PUSH snapshots (Phase 3 MP-C16:
    //    EngineEventListener → StreamifyEngineBridge.onEngineSnapshot —
    //    the 200ms control-plane refreshBufferStats pull is deleted),
    //    with PlayerViewModel.bufferHealth mirroring the bridge flow for
    //    the UI. No Composable or ViewModel calls a blocking native
    //    getter in a render loop.
    //
    // 2. AUDIO-FOCUS TEARDOWN — the AudioFocusGovernor owns focus decisions
    //    (ExoPlayer's internal handling is disabled below): permanent
    //    AUDIOFOCUS_LOSS transitions the player to STOPPED atomically and
    //    stops the foreground service cleanly — no zombie session.
    //
    // 3. CLEAN RELEASE — onDestroy invokes StreamifyEngineBridge.release()
    //    after the session/player teardown so no native buffer allocation
    //    outlives the service.
    //
    // 4. CLOCK DOMAIN (Phase 3, MP-DEC-03 — directive §D): NO wall-clock
    //    progress extrapolation. Audit result: every remaining
    //    System.currentTimeMillis() site in this service is stats
    //    accounting (listening-seconds dwell windows, play-count dedup,
    //    CDN-renewal windows) — never progress-bar math. Position
    //    derivation is scaffolded through PlaybackPositionCalculator so
    //    the Phase 4a DacPresentationClock cutover (AAudio DAC callbacks
    //    behind USE_RUST_AUDIO) swaps the position SOURCE, not the math.
    private val audioFocusGovernor = AudioFocusGovernor()
    private val focusTeardownLock = Any()

    private val audioFocusListener = android.media.AudioManager.OnAudioFocusChangeListener { focusChange ->
        // Registered with the main-thread handler — safe to touch the
        // player and the service lifecycle directly.
        executeFocusActions(audioFocusGovernor.onFocusChange(focusChange))
    }

    companion object {

        /**
         * Phase 4a (MP-HAL05): dogfood ring capacity - 16384 frames
         * ~= 341 ms @ 48 kHz. Deep enough to absorb Media3's bursty
         * handleBuffer cadence, shallow enough for the low-latency
         * regime; power-of-two per the native SPSC contract.
         */
        const val RUST_SINK_RING_CAPACITY_FRAMES = 16384
        // LEGACY objects kept alive only for non-chain consumers:
        //  - syncAudioProcessor: Jam lockstep hardware-latency compensation
        //    (PlayerViewModel.getAcousticPositionMs)
        //  - crossfadeAudioProcessor companion: user's crossfade pref storage
        // They are NO LONGER in the render chain — see streamifyProcessor.
        val syncAudioProcessor: SyncAudioProcessor = SyncAudioProcessor()
        val crossfadeAudioProcessor: CrossfadeAudioProcessor = CrossfadeAudioProcessor()

        /**
         * THE render-path processor: one fused native pass per buffer.
         * Old chain was [RustDsp, MeshPcm, Crossfade, Sync] = 5 copies +
         * 3 JNI crossings + a Kotlin sample loop on the real-time thread.
         */
        val streamifyProcessor: StreamifyAudioProcessor = StreamifyAudioProcessor()

        // ── CIRCUIT BREAKER (single-owner error recovery) ──
        // Set when THIS service initiates a CDN token renewal for the current
        // item (position-preserving replaceMediaItem). The ViewModel-level
        // error listener checks these to stay hands-off instead of running its
        // own re-resolve-and-reset-to-zero path concurrently (the old dual
        // engine ping-ponged progress resets on flaky CDN URLs).
        @Volatile var lastRenewalMediaId: String? = null
        @Volatile var lastRenewalAtMs: Long = 0L

        val isBuffering = kotlinx.coroutines.flow.MutableStateFlow(false)
        @Volatile var onSeekNextListener: (() -> Unit)? = null
        @Volatile var onSeekPrevListener: (() -> Unit)? = null
    }


    private var player: ExoPlayer? = null
    private var mediaSession: MediaSession? = null

    /**
     * PHASE 4a (MP-HAL05/07): the Rust f32 pull pipeline, live ONLY when
     * [AudioRoutePolicy] resolved the dogfood route (USE_RUST_AUDIO on
     * AND the native sink edges present). Null on the legacy Media3
     * route - the exact same player construction then builds the exact
     * same DefaultAudioSink chain as before this phase. OnDestroy stops
     * it (idempotent, leak-free - the pipeline joins its feeder,
     * releases the AudioTrack and destroys the native ring handle).
     */
    private var rustAudioPipeline: RustAudioPipeline? = null

    /**
     * The exact engine instance THIS service published to
     * [SharedPlaybackEngine] (MEDIA-01). OnDestroy withdraws by this tracked
     * identity instead of re-reading mediaSession.player — robust even if
     * the session field was already torn down, and the identity guard in
     * SharedPlaybackEngine.withdraw() makes a stale value harmless.
     */
    private var publishedPlayer: androidx.media3.common.Player? = null

    override fun onCreate() {
        super.onCreate()
        try {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
        } catch (_: Throwable) {}
        DolbySpatialManager.init(this)
        AudioDeviceManager.init(this)

        // PHASE 4a ROUTE BRANCH (MP-HAL05 + MP-HAL07): resolve the dogfood
        // route ONCE per service generation, BEFORE the renderers factory
        // builds. RUST_AUDIO_PULL routes Media3's decoded PCM through the
        // RustMedia3AudioSink adapter into the native f32 SPSC ring; the
        // urgent-priority feeder pulls it back out into a strictly-48kHz /
        // 2ch / ENCODING_PCM_FLOAT / PERFORMANCE_MODE_LOW_LATENCY
        // AudioTrack, with the DAC presentation clock feeding
        // record_dac_clock (MP-HAL06). LEGACY (flag off OR edges absent OR
        // pipeline failed to start) keeps the untouched DefaultAudioSink
        // chain below - graceful fallback, never a broken player.
        val audioRoute = AudioRoutePolicy.resolve()
        // PHASE 5 (MP-CUT01) — ZERO-CRASH DOGFOOD ARMING: the entire Rust
        // pipeline construction is wrapped in a catch-all, so ANY failure
        // on the native lane (ring factory rejection, feeder spawn error,
        // even a Throwable from code we have not imagined yet) degrades to
        // the byte-identical legacy DefaultAudioSink chain with ONE logged
        // reason and ONE telemetry counter — never a crash, never silence.
        // RustAudioPipeline.start() already guards its own factories; this
        // belt-and-braces layer completes the guarantee the directive
        // mandates for unrecoverable initialization errors.
        rustAudioPipeline = if (audioRoute.route == AudioRoutePolicy.Route.RUST_AUDIO_PULL) {
            try {
                RustAudioPipeline(
                    ringFactory = {
                        // The seam binding is guaranteed present here: the
                        // route resolved RUST_AUDIO_PULL, which requires
                        // probeEdges() to have reported the full f32 pull
                        // path (a present binding is a precondition of that
                        // report).
                        val jni = requireNotNull(com.streamify.app.media.audio.RustAudioSinkJni.binding) {
                            "RUST_AUDIO_PULL resolved but the sink JNI binding is absent"
                        }
                        val handle = jni.createSink(
                            AudioTrackPullOutput.CANONICAL_SAMPLE_RATE_HZ,
                            AudioTrackPullOutput.CANONICAL_CHANNELS,
                            RUST_SINK_RING_CAPACITY_FRAMES,
                        )
                        check(handle >= 0L) {
                            "nativeCreateSink rejected the dogfood configuration " +
                                "(handle=$handle)"
                        }
                        NativeFloatSinkEdge(
                            handle,
                            AudioTrackPullOutput.CANONICAL_CHANNELS,
                        )
                    },
                    outputFactory = { AudioTrackPullOutput() },
                    clockRecorder = { clock -> StreamifyEngineBridge.recordDacClock(clock) },
                ).takeIf { it.start() }
            } catch (e: Throwable) {
                com.streamify.app.media.audio.DogfoodTelemetry.recordLegacyFallback(
                    "pipeline init failed: ${e.javaClass.simpleName}: ${e.message}"
                )
                null
            }
        } else {
            null
        }
        if (audioRoute.route == AudioRoutePolicy.Route.RUST_AUDIO_PULL && rustAudioPipeline == null) {
            SLog.w(
                "PlaybackService",
                "RUST_AUDIO_PULL route requested but the pipeline failed to start - " +
                    "falling back to the legacy DefaultAudioSink chain"
            )
        }
        // MP-CUT01: the dogfood window's measurement surface collects the
        // live pipeline's telemetry + DAC clock pushes (route decisions
        // were recorded inside AudioRoutePolicy.resolve above).
        rustAudioPipeline?.let { dogfood ->
            com.streamify.app.media.audio.DogfoodTelemetry.attachPipeline(dogfood)
        }

        // ─── AUDIO RENDER CHAIN (DIRECT CDN STREAMING) ──────────────────────────────
        // JAM v4 (mission §C): SyncAudioProcessor rides the AudioSink render
        // chain as the lockstep drift actuator. Outside a Jam session it is a
        // strict bit-exact passthrough (zero transformation, zero latency
        // cost), so the pristine-CDN guarantee below holds for solo listening.
        // NOTE: Custom fused StreamifyAudioProcessor (loudnessDb pre-gain →
        // LUFS normalization → Soft-Knee limiter → Rust parametric EQ) is
        // TEMPORARILY DISABLED.
        //
        // WHY DISABLED:
        // 1. Hardcoded biquad low-shelf (+6dB @ 80Hz) and Haas 3D delay effect
        //    in `rust/src/audio_dsp.rs` introduce severe phase cancellation,
        //    mono-compatibility issues, and comb filtering artifacts on modern masters.
        // 2. Dynamic LUFS RMS normalizer requires recalibration of attack/release
        //    envelopes and true-peak calculation to avoid pumping and harmonic distortion.
        //
        // CURRENT STATE:
        // Streams are fed directly from MediaCodec into DefaultAudioSink → AudioTrack.
        // Delivers pristine, unaltered 100% bit-exact CDN audio with zero latency
        // and universal device/Bluetooth compatibility.
        //
        // TODO (DSP V2 DEVELOPMENT ROADMAP):
        // - Strip static low-shelf / Haas spatializer from `audio_dsp.rs`; make EQ purely user-driven.
        // - Re-implement ITU-R BS.1770-4 K-weighting LUFS metering in C++20 / Rust SIMD.
        // - Introduce an opt-in "Audiophile Mastering" toggle in Settings before re-enabling.
        // ─────────────────────────────────────────────────────────────────────────────
        // Media3 1.2.x: custom AudioProcessors enter the render chain through
        // DefaultAudioSink.Builder (DefaultRenderersFactory no longer exposes
        // setAudioProcessors). Overriding buildAudioSink() keeps the exact
        // pipeline DefaultRenderersFactory would have built otherwise.
        val renderersFactory = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(
                context: android.content.Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean
            ): androidx.media3.exoplayer.audio.AudioSink {
                // PHASE 4a (MP-HAL05/07) + PHASE 5 (MP-CUT01): dogfood
                // branch, zero-crash hardened. The pipeline is non-null
                // ONLY when AudioRoutePolicy resolved RUST_AUDIO_PULL and
                // start() succeeded above - every other path (flag off,
                // native edges unresolved, start failure) builds the
                // byte-identical legacy chain. A RustMedia3AudioSink
                // construction failure (unrecoverable initialization error)
                // likewise falls through to the legacy sink with ONE
                // telemetry counter — the automatic, zero-crash fallback
                // the MP-CUT01 directive mandates.
                rustAudioPipeline?.let { dogfood ->
                    try {
                        return RustMedia3AudioSink(dogfood)
                    } catch (e: Throwable) {
                        com.streamify.app.media.audio.DogfoodTelemetry.recordLegacyFallback(
                            "RustMedia3AudioSink init failed: " +
                                "${e.javaClass.simpleName}: ${e.message}"
                        )
                    }
                }
                return DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(enableFloatOutput)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .setAudioProcessors(arrayOf(syncAudioProcessor))
                    .build()
            }
        }

        val audioLoadControl = androidx.media3.exoplayer.DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ 2500,
                /* maxBufferMs = */ 30000,
                /* bufferForPlaybackMs = */ 500,
                /* bufferForPlaybackAfterRebufferMs = */ 1000
            )
            .setTargetBufferBytes(C.LENGTH_UNSET)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        val httpDataSourceFactory = androidx.media3.datasource.okhttp.OkHttpDataSource.Factory(
            com.streamify.app.data.network.NetworkEngine.exoPlayerClient
        ).setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")

        // Progressive 250MB Audio LRU Cache (Zero-latency seeking & offline replaying)
        val audioCache = AudioCacheManager.getCache(this)
        val cacheDataSourceFactory = androidx.media3.datasource.cache.CacheDataSource.Factory()
            .setCache(audioCache)
            .setUpstreamDataSourceFactory(httpDataSourceFactory)
            .setFlags(androidx.media3.datasource.cache.CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

        val dataSourceFactory = androidx.media3.datasource.DefaultDataSource.Factory(this, cacheDataSourceFactory)

        val mediaSourceFactory = DefaultMediaSourceFactory(this)
            .setDataSourceFactory(dataSourceFactory)

        val exoPlayer = ExoPlayer.Builder(this, renderersFactory, mediaSourceFactory)
            .setAudioAttributes(
                AudioAttributes.Builder()
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .setUsage(C.USAGE_MEDIA)
                .setSpatializationBehavior(C.SPATIALIZATION_BEHAVIOR_AUTO)
                .build(),
                // FIREWALL (Phase 4): internal Media3 focus handling is OFF —
                // it only pauses on permanent loss. The AudioFocusGovernor
                // takes explicit ownership so permanent AUDIOFOCUS_LOSS gets
                // the full atomic teardown instead of a zombie pause.
                /* handleAudioFocus = */ false
            )
            .setLoadControl(audioLoadControl)
            .setHandleAudioBecomingNoisy(true)
            .setPauseAtEndOfMediaItems(false)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()

        // adb-logcat-grade player telemetry: TerminalPlayerListener mirrors
        // state transitions, PLAYER_ERROR cause chains, LOAD_ERRORs and media
        // transitions into SLog -> admin terminal (and onward to system logcat).
        exoPlayer.addAnalyticsListener(TerminalPlayerListener())

        player = exoPlayer
        exoPlayer.setSkipSilenceEnabled(true)

        AudioDeviceManager.onHeadsetDisconnectedListener = {
            if (exoPlayer.isPlaying) {
                exoPlayer.pause()
            }
        }
        
        preBufferManager = PredictivePreBufferManager(this)
        // Jam Phase-1: shadow pre-buffer hook for host NEXT_IS intents.
        PredictivePreBufferManager.JamPreBuffer.install(
            requireNotNull(preBufferManager) { "preBufferManager must be initialized before Jam shadow install" }
        )

        // PHASE 4 (U1): tether the Jam distributed loops to THIS foreground
        // service. They survive navigation and hold network priority while
        // music plays — viewModelScope would die on the first swipe-away.
        jamServiceScope = kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO
        )
        com.streamify.app.jam.JamEngine.attachRuntimeScope(
            requireNotNull(jamServiceScope) { "jamServiceScope must be initialized before JamEngine tether" }
        )
        errorRecoveryScope = kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO
        )
        if (com.streamify.app.jam.JamEngine.isActive()) {
            com.streamify.app.jam.JamEngine.startRuntime()
        }

        exoPlayer.addListener(object : androidx.media3.common.Player.Listener {
            private var lastPlayStartMs: Long = 0L
            private var lastCountedMediaId: String? = null
            private var lastCountedAtMs: Long = 0L

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                val now = System.currentTimeMillis()
                if (isPlaying) {
                    lastPlayStartMs = now
                } else if (lastPlayStartMs > 0L) {
                    val deltaSec = ((now - lastPlayStartMs) / 1000L).coerceAtLeast(0L)
                    if (deltaSec > 0) {
                        com.streamify.app.data.telemetry.YtStatsTelemetryEngine.recordListeningSeconds(deltaSec)
                    }
                    lastPlayStartMs = 0L
                }
                // Phase 4 — Now Playing widgets: transport-flag pushes ride the
                // throttled Glance store (re-renders at most ~1/s).
                val meta = exoPlayer.currentMediaItem?.mediaMetadata
                com.streamify.app.widget.NowPlayingWidgetStateStore.update(
                    context = this@PlaybackService,
                    title = meta?.title?.toString(),
                    artist = meta?.artist?.toString(),
                    artwork = meta?.artworkUri?.toString(),
                    isPlaying = isPlaying,
                    jamActive = com.streamify.app.jam.JamEngine.isActive()
                )
                // MEDIA-02 (UFFI-04 telemetry): the playing flag refines
                // READY into Playing/Paused — onPlaybackStateChanged(READY)
                // may fire before the flag flips, so the authoritative
                // playing/paused record rides this callback. BUFFERING/IDLE
                // states are already recorded by onPlaybackStateChanged.
                when {
                    isPlaying -> StreamifyEngineBridge.recordPlaybackState(
                        StreamifyEngineBridge.PlaybackState.Playing
                    )
                    exoPlayer.playbackState == androidx.media3.common.Player.STATE_READY ||
                        exoPlayer.playbackState == androidx.media3.common.Player.STATE_ENDED ->
                        StreamifyEngineBridge.recordPlaybackState(
                            StreamifyEngineBridge.PlaybackState.Paused
                        )
                }

                // FIREWALL (Phase 4): explicit focus ownership — request on
                // playback start, abandon when it ends (the governor retains
                // focus across its own transient pause/duck so the ending
                // GAIN still reaches us).
                if (isPlaying) {
                    executeFocusActions(audioFocusGovernor.onPlaybackStarted())
                } else if (!audioFocusGovernor.isTransientPauseActive()) {
                    executeFocusActions(audioFocusGovernor.onPlaybackStopped())
                }
            }

            override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
                runCatching {
                    com.streamify.app.data.NativeBridge.nativeResetAudioDSP()
                }

                val now = System.currentTimeMillis()
                if (lastPlayStartMs > 0L) {
                    val deltaSec = ((now - lastPlayStartMs) / 1000L).coerceAtLeast(0L)
                    if (deltaSec > 0) {
                        com.streamify.app.data.telemetry.YtStatsTelemetryEngine.recordListeningSeconds(deltaSec)
                    }
                    lastPlayStartMs = if (exoPlayer.isPlaying) now else 0L
                }

                if (mediaItem != null) {
                    val title = mediaItem.mediaMetadata.title?.toString() ?: ""
                    val artist = mediaItem.mediaMetadata.artist?.toString() ?: ""
                    val cover = mediaItem.mediaMetadata.artworkUri?.toString() ?: ""
                    val path = mediaItem.localConfiguration?.uri?.toString() ?: ""
                    // Phase 4 — Now Playing widgets: track transitions push
                    // the fresh identity into the throttled widget store.
                    com.streamify.app.widget.NowPlayingWidgetStateStore.update(
                        context = this@PlaybackService,
                        title = title,
                        artist = artist,
                        artwork = cover,
                        isPlaying = exoPlayer.isPlaying,
                        jamActive = com.streamify.app.jam.JamEngine.isActive()
                    )
                    // STATS OVERHAUL: this listener is the SINGLE writer for
                    // listening seconds AND play counts. Real listen length is
                    // passed so sub-10s blips never inflate Top Songs, and
                    // same-track re-preparations (CDN 403 renewal) are deduped.
                    val nowMs = System.currentTimeMillis()
                    val isRenewalReplay = mediaItem.mediaId == lastCountedMediaId &&
                            (nowMs - lastCountedAtMs) < 1500L
                    if (title.isNotBlank() && !isRenewalReplay) {
                        lastCountedMediaId = mediaItem.mediaId
                        lastCountedAtMs = nowMs
                        val track = com.streamify.app.data.models.Track(
                            id = mediaItem.mediaId.toIntOrNull() ?: 0,
                            title = title,
                            artist = artist,
                            filepath = path,
                            coverArtPath = cover
                        )
                        val listenedForThisPlay =
                            ((nowMs - (lastPlayStartMs.takeIf { it > 0 } ?: nowMs)) / 1000L)
                                .coerceIn(0L, 3600L)
                        com.streamify.app.data.telemetry.YtStatsTelemetryEngine.recordTrackPlay(track, listenedForThisPlay)
                    }
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                isBuffering.value = (playbackState == androidx.media3.common.Player.STATE_BUFFERING)
                // MEDIA-02 (UFFI-04 telemetry): mirror the Media3 lifecycle
                // into the engine bridge. STATE_READY's playing/paused
                // refinement rides onIsPlayingChanged (Media3 may deliver
                // READY before the isPlaying flag flips); STATE_ENDED maps to
                // Paused — buffered data retained, consumption suspended.
                StreamifyEngineBridge.recordPlaybackState(
                    when (playbackState) {
                        androidx.media3.common.Player.STATE_BUFFERING ->
                            StreamifyEngineBridge.PlaybackState.Buffering
                        androidx.media3.common.Player.STATE_READY ->
                            if (exoPlayer.isPlaying) {
                                StreamifyEngineBridge.PlaybackState.Playing
                            } else {
                                StreamifyEngineBridge.PlaybackState.Paused
                            }
                        androidx.media3.common.Player.STATE_ENDED ->
                            StreamifyEngineBridge.PlaybackState.Paused
                        else -> StreamifyEngineBridge.PlaybackState.Idle
                    }
                )
            }

            override fun onVolumeChanged(volume: Float) {
                // MEDIA-02: route output-volume changes to the core engine
                // (UDL set_volume, clamped in the bridge and again in Rust).
                // Result ignored — the telemetry push is best-effort and
                // degraded backends already fail silently inside the bridge.
                StreamifyEngineBridge.setVolume(volume)
            }

            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                super.onAudioSessionIdChanged(audioSessionId)
                EqualizerManager.init(this@PlaybackService, audioSessionId)
            }


            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                super.onPlayerError(error)
                SLog.e("PlaybackService", "onPlayerError: playback failed", error)

                // MEDIA-02: a fatal player error is the terminal lifecycle
                // state for the current item — record it before recovery
                // runs (Media3 follows with STATE_IDLE, which resets the
                // session-scoped underrun tally).
                StreamifyEngineBridge.recordPlaybackState(
                    StreamifyEngineBridge.PlaybackState.Error
                )

                // Engine 3: JIT CDN Token Auto-Renewer (403/410 Forbidden Shield)
                val isExpiredOrBadHttp = error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ||
                        error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
                        error.message?.contains("403") == true || error.message?.contains("410") == true

                if (isExpiredOrBadHttp) {
                    val currentItem = exoPlayer.currentMediaItem
                    val currentPos = exoPlayer.currentPosition
                    val mediaUri = currentItem?.localConfiguration?.uri?.toString() ?: ""
                    val mediaId = currentItem?.mediaId ?: mediaUri

                    if (mediaId.isNotBlank()) {
                        // Announce ownership BEFORE the async renewal so the
                        // ViewModel listener defers to this path.
                        lastRenewalMediaId = mediaId
                        lastRenewalAtMs = System.currentTimeMillis()

                        val recoveryScope = errorRecoveryScope ?: return
                        recoveryScope.launch {
                            try {
                                val fresh = YouTubeStreamResolver.resolveStreamUrl(mediaId, forceFresh = true)
                                if (fresh != null && fresh.streamUrl.isNotBlank()) {
                                    withContext(kotlinx.coroutines.Dispatchers.Main) {
                                        val updatedItem = (currentItem ?: return@withContext).buildUpon()
                                            .setUri(android.net.Uri.parse(fresh.streamUrl))
                                            .build()
                                        val curIdx = exoPlayer.currentMediaItemIndex
                                        exoPlayer.replaceMediaItem(curIdx, updatedItem)
                                        exoPlayer.seekTo(curIdx, currentPos)
                                        exoPlayer.prepare()
                                        exoPlayer.play()
                                    }
                                }
                            } catch (e: Exception) {
                                SLog.st("PlaybackService", "onPlayerError CDN token renewal failed", e)
                            }
                        }
                    }
                }
            }
        })

        val forwardingPlayer = object : androidx.media3.common.ForwardingPlayer(exoPlayer) {

            // ── Phase 3 (MP-C03/C17 — directive §C.2): hardware command tap ──
            // Media3 routes every hardware-origin transport (Bluetooth
            // headset buttons, car steering-wheel controls, Assistant voice
            // actions, notification/lockscreen actions) through this session
            // player's transport methods — the Media3-era equivalent of the
            // legacy MediaSessionCompat.Callback surface. Each override
            // mirrors the intent into the debounced MediaCommandDispatcher
            // BEFORE the local player applies it, so the Rust core's reverse
            // control plane observes the same intent (50–150ms burst
            // coalescing inside the dispatcher; Media3 stays the local
            // audio authority until the Phase 5 cutover).

            override fun play() {
                MediaCommandDispatcher.dispatchMediaCommand(MediaCommand.Play)
                super.play()
            }

            override fun pause() {
                MediaCommandDispatcher.dispatchMediaCommand(MediaCommand.Pause)
                super.pause()
            }

            override fun stop() {
                MediaCommandDispatcher.dispatchMediaCommand(MediaCommand.Stop)
                super.stop()
            }

            override fun seekTo(positionMs: Long) {
                MediaCommandDispatcher.dispatchMediaCommand(MediaCommand.Seek(positionMs))
                super.seekTo(positionMs)
            }

            override fun getAvailableCommands(): androidx.media3.common.Player.Commands {
                return super.getAvailableCommands().buildUpon()
                    .add(androidx.media3.common.Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
                    .add(androidx.media3.common.Player.COMMAND_SEEK_TO_MEDIA_ITEM)
                    .add(androidx.media3.common.Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                    .add(androidx.media3.common.Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                    .add(androidx.media3.common.Player.COMMAND_SEEK_TO_NEXT)
                    .add(androidx.media3.common.Player.COMMAND_SEEK_TO_PREVIOUS)
                    .add(androidx.media3.common.Player.COMMAND_SEEK_BACK)
                    .add(androidx.media3.common.Player.COMMAND_SEEK_FORWARD)
                    .build()
            }

            override fun isCommandAvailable(command: Int): Boolean {
                return when (command) {
                    androidx.media3.common.Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
                    androidx.media3.common.Player.COMMAND_SEEK_TO_MEDIA_ITEM,
                    androidx.media3.common.Player.COMMAND_SEEK_TO_NEXT,
                    androidx.media3.common.Player.COMMAND_SEEK_TO_PREVIOUS,
                    androidx.media3.common.Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                    androidx.media3.common.Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                    androidx.media3.common.Player.COMMAND_SEEK_BACK,
                    androidx.media3.common.Player.COMMAND_SEEK_FORWARD -> true
                    else -> super.isCommandAvailable(command)
                }
            }

            override fun seekToNext() {
                MediaCommandDispatcher.dispatchMediaCommand(MediaCommand.Next)
                if (onSeekNextListener != null) {
                    onSeekNextListener?.invoke()
                } else {
                    super.seekToNext()
                }
            }

            override fun seekToNextMediaItem() {
                MediaCommandDispatcher.dispatchMediaCommand(MediaCommand.Next)
                if (onSeekNextListener != null) {
                    onSeekNextListener?.invoke()
                } else {
                    super.seekToNextMediaItem()
                }
            }

            override fun seekToPrevious() {
                MediaCommandDispatcher.dispatchMediaCommand(MediaCommand.Previous)
                if (onSeekPrevListener != null) {
                    onSeekPrevListener?.invoke()
                } else {
                    super.seekToPrevious()
                }
            }

            override fun seekToPreviousMediaItem() {
                MediaCommandDispatcher.dispatchMediaCommand(MediaCommand.Previous)
                if (onSeekPrevListener != null) {
                    onSeekPrevListener?.invoke()
                } else {
                    super.seekToPreviousMediaItem()
                }
            }
        }

        // Phase 4 — widget state fan-out (Glance renders on transition +
        // transport changes via the throttled store).
        com.streamify.app.widget.NowPlayingWidgetStateStore.updater =
            com.streamify.app.widget.GlanceWidgetUpdater()

        val sessionCallback = object : MediaSession.Callback {
            override fun onConnect(
                session: MediaSession,
                controller: MediaSession.ControllerInfo
            ): MediaSession.ConnectionResult {
                val availablePlayerCommands = MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS.buildUpon()
                    .add(androidx.media3.common.Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
                    .add(androidx.media3.common.Player.COMMAND_SEEK_TO_MEDIA_ITEM)
                    .add(androidx.media3.common.Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                    .add(androidx.media3.common.Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                    .add(androidx.media3.common.Player.COMMAND_SEEK_TO_NEXT)
                    .add(androidx.media3.common.Player.COMMAND_SEEK_TO_PREVIOUS)
                    .add(androidx.media3.common.Player.COMMAND_SEEK_BACK)
                    .add(androidx.media3.common.Player.COMMAND_SEEK_FORWARD)
                    .build()

                return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                    .setAvailablePlayerCommands(availablePlayerCommands)
                    .build()
            }

            /**
             * Phase 4 — Gap #56 voice actions + Auto leaf taps: controllers
             * hand over mediaId-only items (Assistant search queries arrive
             * as RequestMetadata.searchQuery). Resolution runs entirely on
             * the error-recovery IO scope; the binder thread only builds
             * the future.
             */
            override fun onAddMediaItems(
                session: MediaSession,
                controller: MediaSession.ControllerInfo,
                mediaItems: List<androidx.media3.common.MediaItem>
            ): com.google.common.util.concurrent.ListenableFuture<List<androidx.media3.common.MediaItem>> {
                val future = com.google.common.util.concurrent.SettableFuture
                    .create<List<androidx.media3.common.MediaItem>>()
                val scope = errorRecoveryScope
                if (scope == null) {
                    future.set(emptyList())
                    return future
                }
                scope.launch {
                    val resolved = mediaItems.mapNotNull { item ->
                        val voice = com.streamify.app.media.voice.MediaSessionVoiceHandler
                            .resolve(item.requestMetadata.searchQuery)
                        if (voice != null) {
                            com.streamify.app.media.voice.VoicePlaybackResolver.resolve(voice)
                        } else {
                            listOfNotNull(
                                com.streamify.app.media.voice.VoicePlaybackResolver
                                    .resolveMediaIdItem(item)
                            )
                        }
                    }.flatten()
                    future.set(resolved)
                }
                return future
            }
        }


        mediaSession = MediaSession.Builder(this, forwardingPlayer)
            .setCallback(sessionCallback)
            .build()

        // Phase 4 — Android Auto: expose the engine to the Auto browse
        // service so head-unit and phone share ONE queue + session.
        // MEDIA-01: remember the exact instance so onDestroy withdraws it
        // by identity even if the session field is already gone.
        publishedPlayer = forwardingPlayer
        SharedPlaybackEngine.publish(forwardingPlayer)

        // ─── Phase 3 (MP-C16/MP-C17 — directive §A/§C) ──────────────────
        // Arm the hardware media-command debounce actor and bind the
        // engine's push event listener. Both degrade silently on unwired
        // backends (typed failures inside the bridge — the generated push
        // contract arrives with Alpha's MP-C02 codegen; until then the
        // app-side reactive mirror keeps the UI fed).
        mediaCommandScope = kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default
        )
        MediaCommandDispatcher.start(mediaCommandScope!!)
        StreamifyEngineBridge.bindEngineEvents()
            .onFailure { err ->
                SLog.st(
                    "PlaybackService",
                    "engine push binding degraded (expected until Alpha's MP-C02 codegen lands)",
                    err
                )
            }
    }

    private var preBufferManager: PredictivePreBufferManager? = null

    // ── Audio-focus execution (the governor decides, this executes) ──────

    private fun executeFocusActions(actions: List<AudioFocusGovernor.Action>) {
        for (action in actions) {
            when (action) {
                AudioFocusGovernor.Action.RequestFocus -> requestAudioFocusNow()
                AudioFocusGovernor.Action.AbandonFocus -> abandonAudioFocusNow()
                is AudioFocusGovernor.Action.Duck -> player?.volume = action.gain
                AudioFocusGovernor.Action.Unduck -> player?.volume = 1f
                AudioFocusGovernor.Action.Pause -> player?.pause()
                AudioFocusGovernor.Action.Resume -> player?.play()
                AudioFocusGovernor.Action.TeardownPlayback -> teardownForPermanentFocusLoss()
            }
        }
    }

    private fun requestAudioFocusNow() {
        try {
            val request = audioFocusRequest ?: android.media.AudioFocusRequest
                .Builder(android.media.AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setOnAudioFocusChangeListener(
                    audioFocusListener,
                    android.os.Handler(android.os.Looper.getMainLooper())
                )
                .build()
                .also { audioFocusRequest = it }
            audioManager.requestAudioFocus(request)
        } catch (_: Throwable) {
            // Focus is best-effort — playback proceeds regardless; the
            // teardown contract only guards what happens once focus IS ours
            // and then permanently lost.
        }
    }

    private fun abandonAudioFocusNow() {
        try {
            audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        } catch (_: Throwable) {}
    }

    private val audioManager: android.media.AudioManager
        get() = getSystemService(AUDIO_SERVICE) as android.media.AudioManager

    @Volatile private var audioFocusRequest: android.media.AudioFocusRequest? = null

    /**
     * FIREWALL (Phase 4) — permanent AUDIOFOCUS_LOSS: the ATOMIC transition
     * to STOPPED. One synchronized block pauses AND stops the player and
     * records the terminal Idle state on the engine bridge (the
     * session-scoped underrun tally resets) — no observer can catch a
     * mid-state. Foreground state is then dropped cleanly and the service
     * stops: no zombie notification, no idle FGS holding priority another
     * app's player needs.
     */
    private fun teardownForPermanentFocusLoss() {
        synchronized(focusTeardownLock) {
            try {
                player?.pause()
                player?.stop()
            } catch (_: Throwable) {}
            // Deterministic terminal record inside the lock — the
            // onPlaybackStateChanged(IDLE) hook lands the same value right
            // after; duplicate records are idempotent.
            StreamifyEngineBridge.recordPlaybackState(
                StreamifyEngineBridge.PlaybackState.Idle
            )
        }
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        stopSelf()
        SLog.i("PlaybackService", "permanent audio-focus loss — session stopped, foreground dropped")
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    /**
     * MEDIA-01: the user swiped the task away. Keep the FGS alive only
     * while something is actually worth keeping alive (media queued and
     * playWhenReady); otherwise drop the service instead of zombie-idling
     * a foreground notification nobody controls anymore (documented
     * MediaSessionService pattern).
     */
    override fun onTaskRemoved(rootIntent: android.content.Intent) {
        val sessionPlayer = mediaSession?.player
        if (sessionPlayer == null ||
            !sessionPlayer.playWhenReady ||
            sessionPlayer.mediaItemCount == 0
        ) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        // PHASE 5 (MP-CUT01): detach the dogfood telemetry collectors
        // before the pipeline stops (they ride the pipeline's own conflated
        // StateFlows; the stop below completes the generation).
        com.streamify.app.media.audio.DogfoodTelemetry.detachPipeline()
        // PHASE 4a (MP-HAL07 clean-switching law): tear the dogfood pull
        // pipeline down FIRST - its feeder/publisher threads and the
        // AudioTrack must die before the engine FFI plane below, and the
        // stop() contract (join feeder, release track, destroy native
        // ring handle, all idempotent) guarantees zero leaked native
        // streams or thread handles across service recreations.
        rustAudioPipeline?.stop()
        rustAudioPipeline = null
        jamServiceScope?.cancel()
        errorRecoveryScope?.cancel()
        // Phase 3 (MP-C03): disarm the hardware media-command debounce
        // actor BEFORE the FFI teardown — a dying session's tail command
        // is meaningless, and the actor must never dispatch onto a
        // released backend.
        MediaCommandDispatcher.stop()
        mediaCommandScope?.cancel()
        mediaCommandScope = null
        com.streamify.app.jam.JamEngine.attachRuntimeScope(null)
        // FIREWALL (Phase 4): release the audio focus the governor holds —
        // another media app must never wait on a dying service.
        abandonAudioFocusNow()
        // MEDIA-01: AudioDeviceManager is a process-lifetime object — its
        // headset-disconnect listener captured THIS service's ExoPlayer.
        // Clear it before teardown or the dead player (and everything it
        // retains) leaks for the whole process lifetime, and a later
        // BECOMING_NOISY broadcast would poke a released player.
        AudioDeviceManager.onHeadsetDisconnectedListener = null
        AudioDeviceManager.release(this)
        EqualizerManager.release()
        preBufferManager?.release()
        preBufferManager = null
        // Phase 4 / MEDIA-01 — withdraw the shared engine BEFORE releasing
        // the session, by the tracked published identity. withdraw() clears
        // the holder reference and synchronously notifies engine listeners
        // (the Auto service drops its MediaLibrarySession) so no consumer
        // ever wraps a player that is about to be released.
        publishedPlayer?.let { SharedPlaybackEngine.withdraw(it) }
        publishedPlayer = null
        // Free the DSP native handles. Only StreamifyAudioProcessor holds any
        // (custom release()); Sync/Crossfade are pure-Kotlin helpers with
        // process lifetime — media3's AudioProcessor interface has no release().
        runCatching { streamifyProcessor.release() }
        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }
        player = null
        // FIREWALL (Phase 4) — the final FFI-plane teardown: LAST, after the
        // session and player are gone. Idempotent; dispatches the backend
        // release (engine-side buffer teardown when the contract carries
        // it) and returns the bridge telemetry surface to the dormant state
        // so no native allocation or stale session tally outlives the service.
        StreamifyEngineBridge.release()
        super.onDestroy()
    }
}

/**
 * Compact AnalyticsListener feeding playback internals into SLog so the admin
 * terminal mirrors what `adb logcat` would show: state transitions, errors,
 * load failures and track transitions.
 */
private class TerminalPlayerListener : androidx.media3.exoplayer.analytics.AnalyticsListener {

    private fun stateName(state: Int): String = when (state) {
        androidx.media3.common.Player.STATE_IDLE -> "IDLE"
        androidx.media3.common.Player.STATE_BUFFERING -> "BUFFERING"
        androidx.media3.common.Player.STATE_READY -> "READY"
        androidx.media3.common.Player.STATE_ENDED -> "ENDED"
        else -> "?$state"
    }

    override fun onPlaybackStateChanged(
        eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
        state: Int
    ) {
        val periodId = eventTime.mediaPeriodId?.periodUid?.toString() ?: eventTime.mediaPeriodId?.toString()
        com.streamify.app.util.SLog.d(
            "ExoEvent",
            "playbackState=${stateName(state)} periodId=$periodId"
        )
    }

    override fun onPlayerError(
        eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
        error: androidx.media3.common.PlaybackException
    ) {
        val causeChain = generateSequence(error.cause) { it.cause }.take(4).joinToString(" <- ") { c ->
            "${c.javaClass.simpleName}: ${c.message ?: ""}"
        }
        com.streamify.app.util.SLog.e(
            "ExoEvent",
            "PLAYER_ERROR code=${error.errorCodeName} msg=${error.message} causes=[$causeChain]"
        )
    }

    override fun onLoadError(
        eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
        loadEventInfo: androidx.media3.exoplayer.source.LoadEventInfo,
        mediaLoadData: androidx.media3.exoplayer.source.MediaLoadData,
        error: java.io.IOException,
        wasCanceled: Boolean
    ) {
        if (!wasCanceled) {
            com.streamify.app.util.SLog.w(
                "ExoEvent",
                "LOAD_ERROR ${error.javaClass.simpleName}: ${error.message ?: ""} uri=${loadEventInfo.uri}"
            )
        }
    }

    override fun onMediaItemTransition(
        eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
        mediaItem: androidx.media3.common.MediaItem?,
        reason: Int
    ) {
        val r = when (reason) {
            androidx.media3.common.Player.MEDIA_ITEM_TRANSITION_REASON_AUTO -> "AUTO"
            androidx.media3.common.Player.MEDIA_ITEM_TRANSITION_REASON_SEEK -> "SEEK"
            androidx.media3.common.Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED -> "PLAYLIST_CHANGED"
            else -> "REPEAT"
        }
        com.streamify.app.util.SLog.i(
            "ExoEvent",
            "transition=$r mediaId=${mediaItem?.mediaId}"
        )
    }
}


