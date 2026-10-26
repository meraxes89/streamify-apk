package com.streamify.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import coil.Coil
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.streamify.app.data.supabase.AuthManager
import com.streamify.app.ui.motion.LiquidMorphController
import com.streamify.app.ui.motion.SharedCoverMorphLayer
import com.streamify.app.data.supabase.AuthState
import com.streamify.app.navigation.AppNavGraph
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import com.streamify.app.ui.components.LocalDockPosition
import com.streamify.app.ui.components.LocalQuantumController
import com.streamify.app.ui.components.MiniPlayerBar
import com.streamify.app.ui.components.QuantumSonicTokenController
import com.streamify.app.ui.components.QuantumSonicTokenOverlay
import com.streamify.app.ui.components.yt.YtBottomNavBar
import com.streamify.app.ui.screens.FullPlayerSheet
import com.streamify.app.ui.screens.PrismaticSplashScreen
import com.streamify.app.ui.screens.YtOnboardingScreen
import com.streamify.app.ui.theme.*
import com.streamify.app.viewmodel.PlayerViewModel
import com.streamify.app.viewmodel.UiEvent
import com.streamify.app.viewmodel.UiEventBus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        com.streamify.app.ui.motion.TouchTelemetryLogger.log(ev)
        return super.dispatchTouchEvent(ev)
    }

    // activity 1.9.x: ComponentActivity.onNewIntent takes a non-null Intent.
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        com.streamify.app.navigation.SpotifyDeepLinkHandler.handle(this, intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        com.streamify.app.navigation.SpotifyDeepLinkHandler.handle(this, intent)
        com.streamify.app.data.models.AppMode.initialize(this)
        // Gap #31 — follow graph: load persisted follow edges + count seeds
        // before any FollowButton reads the flows (null-safe on failure).
        com.streamify.app.data.social.FollowGraphStore.initialize(this)
        // Gap #12 — Join Fabric: process-level attach (app context for BLE /
        // LAN rails + audio-route watch for the speaker prompt). Torn down in
        // onDestroy — every scanner, listener and coroutine dies with the UI.
        com.streamify.app.jam.JoinFabric.attach(this)

        setContent {
            // Media ingestion entry (prefs read + runtime permissions + scan
            // enqueue) lives in its own single-responsibility composable.
            com.streamify.app.media.MediaScanPermissionGate(this@MainActivity)

            val authState by AuthManager.authState.collectAsState()


            LaunchedEffect(authState) {
                val user = com.streamify.app.data.supabase.SupabaseClient.currentUser.value
                if (user != null) {
                    com.streamify.app.data.supabase.SupabaseClient.startRealtimeSync(user.id)
                } else {
                    com.streamify.app.data.supabase.SupabaseClient.stopRealtimeSync()
                }
            }

            StreamifyTheme {
                val navController = rememberNavController()
                val playerViewModel: PlayerViewModel = viewModel()
                val connectViewModel: com.streamify.app.connect.ConnectViewModel = viewModel()
                val playerState by playerViewModel.playerState.collectAsState()
                val scope = rememberCoroutineScope()
                val context = LocalContext.current

                // ── Phase 4 Connect runtime: coordinator + silent-controller hooks ─
                LaunchedEffect(Unit) {
                    com.streamify.app.connect.ConnectRuntime.initialize(this@MainActivity)
                }
                LaunchedEffect(playerViewModel) {
                    com.streamify.app.connect.ConnectRuntime.playbackHooks =
                        object : com.streamify.app.connect.LocalPlaybackHooks {
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
                    com.streamify.app.cast.CastMediaManager.queueProvider = {
                        val ctrl = playerViewModel.getController()
                        if (ctrl != null && ctrl.mediaItemCount > 0) {
                            com.streamify.app.cast.CastPlaybackState(
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

                    // ── Phase 4 WearOS: wrist transport, volume, Jam voting ──
                    com.streamify.app.wear.WearSessionManager.inputHandler =
                        com.streamify.app.wear.WearInputHandler(
                            object : com.streamify.app.wear.WearActionSink {
                                override fun playPause() { playerViewModel.togglePlayPause() }
                                override fun skipNext() { playerViewModel.skipNext() }
                                override fun skipPrevious() { playerViewModel.skipPrevious() }

                                override fun volumeStep(delta: Float): Boolean {
                                    val ctrl = playerViewModel.getController() ?: return false
                                    return runCatching {
                                        ctrl.setVolume((ctrl.volume + delta).coerceIn(0f, 1f))
                                        true
                                    }.getOrDefault(false)
                                }

                                override fun jamUpvote(trackId: Int): Boolean {
                                    val track = com.streamify.app.jam.JamEngine.queue.value
                                        .find { it.id == trackId } ?: return false
                                    return runCatching {
                                        com.streamify.app.jam.JamEngine.castUpvote(track)
                                    }.getOrDefault(false)
                                }

                                override fun queueAdd(videoId: String): Boolean {
                                    if (!com.streamify.app.jam.JamEngine.isActive()) return false
                                    val stub = com.streamify.app.data.models.Track(
                                        id = videoId.hashCode(),
                                        title = videoId,
                                        artist = "",
                                        ytmVideoId = videoId
                                    )
                                    return runCatching {
                                        com.streamify.app.jam.JamEngine.addToQueue(stub, "Watch")
                                    }.getOrDefault(false)
                                }

                                override fun launchQuickPlaylist(): Boolean {
                                    return runCatching {
                                        val liked = com.streamify.app.data.repository.TrackRepository.likedTracks.value
                                        if (liked.isEmpty()) return false
                                        playerViewModel.playCollection(liked.shuffled())
                                        true
                                    }.getOrDefault(false)
                                }
                            }
                        )

                    // Compact Now Playing mirror to the wrist (5s cadence,
                    // IO transport; Jam rows carry live vote counts).
                    while (true) {
                        kotlinx.coroutines.delay(5000L)
                        val state = playerViewModel.playerState.value
                        val track = state.currentTrack ?: continue
                        val jamActive = com.streamify.app.jam.JamEngine.isActive()
                        val queueRows = if (jamActive) {
                            com.streamify.app.jam.JamEngine.queue.value.take(3).map { t ->
                                com.streamify.app.wear.WearQueueEntry(
                                    trackId = t.id,
                                    title = t.title,
                                    artist = t.artist,
                                    votes = com.streamify.app.jam.JamEngine.voteCountFor(t)
                                )
                            }
                        } else {
                            emptyList()
                        }
                        com.streamify.app.wear.WearSessionManager.publishNowPlaying(
                            com.streamify.app.wear.WearNowPlayingState(
                                trackTitle = track.title,
                                artist = track.artist,
                                artworkUrl = track.coverArtPath,
                                isPlaying = state.isPlaying,
                                positionMs = playerViewModel.positionMs.value,
                                durationMs = state.duration,
                                jamActive = jamActive,
                                jamQueueTop = queueRows,
                                updatedAtMs = System.currentTimeMillis()
                            )
                        )
                    }
                }
                val connectSnapshotProvider: () -> com.streamify.app.connect.PlaybackSnapshot = {
                    com.streamify.app.connect.PlaybackSnapshot(
                        queueTitles = playerState.queue.map { it.title },
                        currentIndex = playerState.currentIndex,
                        positionMs = playerViewModel.positionMs.value,
                        isPlaying = playerState.isPlaying
                    )
                }

                var targetColor by remember { mutableStateOf(Color(0xFF212121)) }
                val dominantColor by animateColorAsState(
                    targetValue = targetColor,
                    animationSpec = tween(800),
                    label = "dominantColor"
                )

                var isSplashDone by remember { mutableStateOf(false) }

                // POST-FIRST-FRAME INIT: heavy subsystem hydration happens
                // AFTER the splash hands off, so time-to-interactive is bounded
                // by the brand animation alone — not library scans or network.
                LaunchedEffect(isSplashDone) {
                    if (!isSplashDone) return@LaunchedEffect
                    withContext(Dispatchers.IO) {
                        playerViewModel.initialize(this@MainActivity)
                        com.streamify.app.data.repository.PlaylistRepository.init(this@MainActivity)
                        com.streamify.app.data.repository.TrackRepository.getAllTracks()
                    }
                    com.streamify.app.data.update.StreamifyUpdateManager.checkForUpdates(this@MainActivity)
                }

                // Dynamic Full-Player Overlay & Dock State
                var isPlayerExpanded by remember { mutableStateOf(false) }

                // ── Liquid morph controller (MiniPlayer <-> FullPlayer) ──────
                // Single funnel for the shared-element sheet morph: dock
                // drag-up, sheet drag-down (and Android 14+ predictive back in
                // the next commit) all drive the same progress Animatable; the
                // composition target flips ONLY at settle boundaries so the
                // 120Hz morph stream never recomposes this activity tree.
                val morphScope = rememberCoroutineScope()
                val morphController = remember {
                    LiquidMorphController(scope = morphScope) { expanded ->
                        isPlayerExpanded = expanded
                    }
                }

                // Quantum Sonic Token 3D Physics Engine
                val quantumController = remember { QuantumSonicTokenController() }
                // Weft plane teardown (I6): revoke the Triad channels when the
                // hosting scope exits, so a late producer publish becomes a
                // DROPPED_REVOKED no-op instead of a write into an unowned buffer.
                DisposableEffect(quantumController) {
                    onDispose { quantumController.dispose() }
                }
                val dockPositionState = remember { mutableStateOf(Offset.Zero) }
                val contextMenuController = remember { com.streamify.app.ui.components.TrackContextMenuController() }

                // --- Root Back Policy: professional stack-walking navigation ---
                // Priority order:
                //   1. Full player sheet open      -> collapse the sheet
                //   2. Deeper in the back stack    -> natural popBackStack() walk
                //      (e.g. artist -> search -> home, instead of snapping to home)
                //   3. Already at root destination -> double-back-to-exit guard
                var lastBackPressedTime by remember { mutableStateOf(0L) }
                LaunchedEffect(Unit) {
                    val dm = this@MainActivity.resources.displayMetrics
                    quantumController.initMetrics(dm.widthPixels.toFloat(), dm.heightPixels.toFloat(), dm.density)
                    morphController.calibrate(
                        screenW = dm.widthPixels.toFloat(),
                        screenH = dm.heightPixels.toFloat(),
                        density = dm.density
                    )
                }

                val navBackStackEntry by navController.currentBackStackEntryAsState()
                val currentRoute = navBackStackEntry?.destination?.route

                BackHandler(enabled = true) {
                    when {
                        isPlayerExpanded -> morphController.collapse()
                        navController.previousBackStackEntry != null -> navController.popBackStack()
                        else -> {
                            val now = System.currentTimeMillis()
                            if (now - lastBackPressedTime < 2000L) {
                                this@MainActivity.finish()
                            } else {
                                lastBackPressedTime = now
                                android.widget.Toast.makeText(this@MainActivity, "Press back again to exit", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }

                // Dock visibility policy (professional music apps):
                //   Nav tabs     -> only on top-level tab destinations
                //   Mini-player  -> everywhere except immersive full-screen routes
                val topLevelRoutes = remember { setOf("home", "search", "library", "downloads") }
                val immersiveRoutes = remember { setOf("queue", "lyrics", "jam", "profile_selection") }

                // Jam invite deep links (streamify://jam/CODE) jump straight into the room.
                LaunchedEffect(Unit) {
                    com.streamify.app.jam.JamEngine.inviteNavigationEvents.collect {
                        navController.navigate("jam")
                    }
                }
                var miniDockDismissedForTrack by remember { mutableStateOf<Int?>(null) }
                LaunchedEffect(playerState.currentTrack?.id) {
                    miniDockDismissedForTrack = null
                }

                LaunchedEffect(playerState.currentTrack) {
                    if (playerState.currentTrack != null && quantumController.stage == com.streamify.app.ui.components.TokenStage.FLYING) {
                        quantumController.onTrackReady()
                    }
                }

                LaunchedEffect(playerState.currentTrack?.coverArtPath) {
                    val path = playerState.currentTrack?.coverArtPath
                    if (path != null) {
                        // PERF v2 B2 — SINGLE DECODE: reuse the flight token's
                        // pre-decoded artwork when it belongs to this track.
                        val cached = quantumController.consumeArtBitmapIfMatched(path)
                        val bitmap = cached ?: run {
                            val request = ImageRequest.Builder(context)
                                .data(path)
                                .allowHardware(false)
                                // Palette only samples ~112²; decoding full-res art
                                // (up to 36MB software bitmap) caused GC cliffs on
                                // exactly the moment the player opens.
                                .size(128)
                                .build()
                            val result = (Coil.imageLoader(context).execute(request) as? SuccessResult)?.drawable
                            (result as? android.graphics.drawable.BitmapDrawable)?.bitmap
                        }
                        if (bitmap != null) {
                            androidx.palette.graphics.Palette.from(bitmap)
                                .resizeBitmapArea(112 * 112)
                                .generate { palette ->
                                    palette?.dominantSwatch?.rgb?.let { colorInt ->
                                        targetColor = Color(colorInt)
                                    } ?: palette?.mutedSwatch?.rgb?.let { colorInt ->
                                        targetColor = Color(colorInt)
                                    }
                                }
                        }
                    }
                }

                if (!isSplashDone) {
                    PrismaticSplashScreen(
                        onPreWarmComplete = {
                            // CRITICAL PATH ONLY: everything here must finish
                            // before the first interactive frame. Library scans,
                            // repo hydration and the update network check are
                            // deferred to post-first-frame background work.
                            val prefs = getSharedPreferences("audio_settings", android.content.Context.MODE_PRIVATE)
                            com.streamify.app.media.audio.CrossfadeAudioProcessor.crossfadeDurationMs =
                                (prefs.getFloat("crossfade_val", 0f) * 1000).toLong()
                            AuthManager.init(this@MainActivity)
                        },
                        onAnimationComplete = {
                            isSplashDone = true
                        }
                    )
                } else if (authState !is AuthState.Authenticated) {
                    YtOnboardingScreen(
                        onComplete = {
                            // Automatically advances to Authenticated
                        }
                    )
                } else {
                    val snackbarHostState = remember { SnackbarHostState() }

                    LaunchedEffect(Unit) {
                        UiEventBus.events.collect { event ->
                            when (event) {
                                is UiEvent.ShowSnackbar -> {
                                    snackbarHostState.showSnackbar(
                                        message = event.message,
                                        duration = SnackbarDuration.Short
                                    )
                                }
                            }
                        }
                    }

                    val hasTrack = playerState.currentTrack != null

                    // 2. GPU Fade for the Unified Dock during expansion
                    val dockAlpha by animateFloatAsState(
                        targetValue = if (isPlayerExpanded) 0f else 1f,
                        animationSpec = tween(durationMillis = 180),
                        label = "dockAlpha"
                    )

                    // PERF: position/progress are HOT (5Hz). They are passed as
                    // flows and collected at the LEAF nodes only — reading them
                    // here would recompose this entire tree every tick.

                    CompositionLocalProvider(
                        LocalQuantumController provides quantumController,
                        LocalDockPosition provides dockPositionState,
                        com.streamify.app.ui.components.LocalContextMenuController provides contextMenuController
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(BgBase)
                        ) {
                            // --- LAYER 1: Master Scaffold & Unified Dock ---
                            Scaffold(
                                snackbarHost = {
                                    SnackbarHost(hostState = snackbarHostState) { data ->
                                        Snackbar(
                                            snackbarData = data,
                                            containerColor = Primary,
                                            contentColor = Color.White
                                        )
                                    }
                                },
                                bottomBar = {
                                    val showNavTabs = (currentRoute ?: "home") in topLevelRoutes
                                    // Swipe-down dismissal is scoped to the current track:
                                    // the dock auto-restores when a new track starts.
                                    val dismissedForTrack = miniDockDismissedForTrack != null &&
                                            miniDockDismissedForTrack == playerState.currentTrack?.id
                                    val showMiniPlayerDock = hasTrack && !dismissedForTrack &&
                                            (currentRoute == null || currentRoute !in immersiveRoutes)
                                    if (showNavTabs || showMiniPlayerDock) {
                                        Column(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .graphicsLayer { this.alpha = dockAlpha }
                                                .windowInsetsPadding(WindowInsets.navigationBars)
                                                .centerInLargeScreen()
                                                .onGloballyPositioned { coordinates ->
                                                    val pos = coordinates.positionInWindow()
                                                    dockPositionState.value = Offset(
                                                        pos.x + (coordinates.size.width / 2f),
                                                        pos.y + 28f
                                                    )
                                                }
                                        ) {
                                            // Docked Mini-Player (Directly above BottomNav with zero overlap)
                                            AnimatedVisibility(
                                                visible = showMiniPlayerDock && quantumController.dockReadyForUI,
                                                enter = slideInVertically(initialOffsetY = { it }) + fadeIn(animationSpec = tween(200)),
                                                exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(animationSpec = tween(200))
                                            ) {
                                                Column {
                                                    // "Listening on <device>" — one tap opens the
                                                    // Connect picker; hidden while purely local.
                                                    val connectSession by com.streamify.app.connect.ConnectRuntime.coordinator.state.collectAsState()
                                                    if (connectSession.isRemoteActive || connectSession.phase == com.streamify.app.connect.ConnectSessionPhase.CONNECTING) {
                                                        Row(
                                                            modifier = Modifier
                                                                .padding(start = 16.dp, bottom = 4.dp)
                                                        ) {
                                                            com.streamify.app.connect.ConnectStatusPill(compact = true)
                                                        }
                                                    }
                                                    MiniPlayerBar(
                                                    track = playerState.currentTrack,
                                                    isPlaying = playerState.isPlaying,
                                                    progressFlow = playerViewModel.progressFraction,
                                                    isBuffering = playerState.isBuffering,
                                                    onPlayPause = { playerViewModel.togglePlayPause() },
                                                    onNext = { playerViewModel.skipNext() },
                                                    onPrevious = { playerViewModel.skipPrevious() },
                                                    onExpand = { morphController.expand() },
                                                    onToggleLike = { playerViewModel.toggleLike() },
                                                    onSwipeDown = {
                                                        miniDockDismissedForTrack = playerState.currentTrack?.id
                                                    },
                                                    tokenController = quantumController,
                                                    morphController = morphController,
                                                    nextTrackTitle = playerState.queue.getOrNull(
                                                        playerState.currentIndex + 1
                                                    )?.title,
                                                    previousTrackTitle = playerState.queue.getOrNull(
                                                        playerState.currentIndex - 1
                                                    )?.title
                                                )
                                                }
                                            }

                                            // Docked Bottom Navigation (top-level tab destinations only)
                                            if (showNavTabs) {
                                                YtBottomNavBar(
                                                    currentRoute = currentRoute,
                                                    onNavigate = { route ->
                                                        navController.navigate(route) {
                                                            popUpTo(navController.graph.startDestinationId) { saveState = true }
                                                            launchSingleTop = true
                                                            restoreState = true
                                                        }
                                                    }
                                                )
                                            }
                                        }
                                    }
                                },
                                containerColor = BgBase
                            ) { paddingValues ->
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .windowInsetsPadding(WindowInsets.statusBars)
                                        .padding(paddingValues)
                                        .centerInLargeScreen()
                                ) {
                                    AppNavGraph(
                                        navController = navController,
                                        playerViewModel = playerViewModel,
                                        dominantColor = dominantColor
                                    )
                                }
                            }

                            // --- LAYER 2: Quantum Sonic Token 3D Levitation Overlay ---
                            QuantumSonicTokenOverlay(controller = quantumController)

                            // --- LAYER 2b: Global Track Context Menu Host ---
                            com.streamify.app.ui.components.GlobalTrackContextMenuHost(
                                controller = contextMenuController,
                                playerViewModel = playerViewModel,
                                onGoToArtist = { artist ->
                                    navController.navigate("artist/${android.net.Uri.encode(artist)}")
                                },
                                onGoToAlbum = { album ->
                                    navController.navigate("album/${android.net.Uri.encode(album)}")
                                }
                            )

                            // --- LAYER 3: Liquid-Morph Full-Player Overlay (120Hz) ---
                            // Composition gate flips only at settle boundaries
                            // (controller callback). While composed, the sheet's
                            // transform + the shared cover morph run entirely in
                            // the layout/draw phases via lambda state reads.
                            // NOTE: the morph transform (translation/scale/alpha)
                            // is applied ONCE, inside FullPlayerSheet's root —
                            // the wrapper here must stay transform-free or the
                            // two nested graphicsLayers compound (2× translation,
                            // s² scale, α² alpha) and diverge from the geometry
                            // spec mid-morph. zIndex only.
                            if (isPlayerExpanded && hasTrack) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .zIndex(10f)
                                ) {
                                    FullPlayerSheet(
                                track = playerState.currentTrack,
                                isPlaying = playerState.isPlaying,
                                positionFlow = playerViewModel.positionMs,
                                progressFlow = playerViewModel.progressFraction,
                                isBuffering = playerState.isBuffering,
                                durationMs = playerState.duration,
                                isShuffleActive = playerState.isShuffleActive,
                                isRepeatActive = playerState.isRepeatActive,
                                dominantColor = dominantColor,
                                onCollapse = { morphController.collapse() },
                                onPlayPause = { playerViewModel.togglePlayPause() },
                                onNext = { playerViewModel.skipNext() },
                                onPrevious = { playerViewModel.skipPrevious() },
                                onSeek = { f ->
                                    val dur = if (playerState.duration > 0) playerState.duration else ((playerState.currentTrack?.durationSec ?: 0) * 1000L)
                                    if (dur > 0) {
                                        val targetMs = (f * dur).toLong().coerceIn(0L, dur)
                                        playerViewModel.seekTo(targetMs)
                                    }
                                },
                                onShuffleToggle = { playerViewModel.toggleShuffle() },
                                onRepeatToggle = { playerViewModel.toggleRepeat() },
                                onToggleLike = { playerViewModel.toggleLike() },
                                onRadioClick = {
                                    playerViewModel.startSongRadio(playerState.currentTrack)
                                },
                                onJamClick = {
                                    // Route through the controller (NOT a direct
                                    // isPlayerExpanded = false): the gate must flip
                                    // at the collapse-settle boundary so the morph
                                    // progress and the composition stay in sync.
                                    // Setting the flag directly left progress at 1f
                                    // while the sheet was gone → the mini dock
                                    // computed alpha 0 and stayed invisible until
                                    // the next expand/collapse cycle.
                                    morphController.collapse()
                                    navController.navigate("jam")
                                },
                                onQueueClick = {
                                    morphController.collapse()
                                    navController.navigate("queue")
                                },
                                onLyricsClick = {
                                    morphController.collapse()
                                    navController.navigate("lyrics")
                                },
                                isAutoPlayEnabled = playerState.isAutoPlayEnabled,
                                onAutoPlayToggle = { playerViewModel.toggleAutoPlay() },
                                morphController = morphController
                                    )
                                    // Active-device pill pinned over the full player's
                                    // top edge — same one-tap picker entry as the dock.
                                    Box(
                                        modifier = Modifier
                                            .align(Alignment.TopCenter)
                                            .zIndex(12f)
                                            .padding(top = 10.dp)
                                    ) {
                                        com.streamify.app.connect.ConnectStatusPill()
                                    }
                                }
                            }

                            // ── LAYER 3b: Shared-element cover morph (Gap: liquid
                            // sheet morphing) — renders the artwork once at hero
                            // resolution above everything while the morph is in
                            // flight; self-hides at both stable endpoints. ──
                            if (isPlayerExpanded && hasTrack) {
                                SharedCoverMorphLayer(
                                    controller = morphController,
                                    coverArtPath = playerState.currentTrack?.coverArtPath,
                                    title = playerState.currentTrack?.title ?: "",
                                    artist = playerState.currentTrack?.artist ?: "",
                                    modifier = Modifier.fillMaxSize()
                                )
                            }

                            // ── LAYER 4: Connect device picker (Gap #52) ─────────────
                    val connectPickerVisible by com.streamify.app.connect.ConnectRuntime.pickerVisible.collectAsState()
                    if (connectPickerVisible) {
                        com.streamify.app.connect.ConnectDeviceSheet(
                            viewModel = connectViewModel,
                            snapshotProvider = connectSnapshotProvider,
                            onDismiss = { com.streamify.app.connect.ConnectRuntime.closeDevicePicker() }
                        )
                    }
                }
            }
        }
    }
    }
}

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_RUNNING_LOW) {
            com.streamify.app.media.sync.ThermalGovernorManager.handleLowMemory(this)
        }
    }

    override fun onDestroy() {
        try {
            val dbPath = getDatabasePath("streamify_universal.db").absolutePath
            com.streamify.app.data.NativeBridge.shutdown(dbPath)
        } catch (e: Throwable) {
            // Ignore
        }
        // Gap #12 — Join Fabric full teardown: BLE advertiser/scanner, LAN
        // beacon listener, speaker watch — zero leaked jobs on process death.
        com.streamify.app.jam.JoinFabric.detach()
        super.onDestroy()
    }
}
