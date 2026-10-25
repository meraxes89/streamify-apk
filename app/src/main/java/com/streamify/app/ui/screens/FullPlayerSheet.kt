package com.streamify.app.ui.screens

import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import kotlinx.coroutines.launch
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.Shuffle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import com.streamify.app.data.repository.TrackRepository
import com.streamify.app.data.models.LyricsData
import com.streamify.app.data.models.LyricsLine
import com.streamify.app.data.models.Track
import com.streamify.app.media.lyrics.LyricOffsetStore
import com.streamify.app.media.lyrics.LyricPlaybackController
import com.streamify.app.ui.components.*
import com.streamify.app.ui.components.yt.*
import com.streamify.app.ui.motion.LiquidMorphController
import com.streamify.app.ui.motion.LiquidMorphGeometry
import com.streamify.app.ui.motion.reportBoundsTo
import com.streamify.app.ui.theme.*
import com.streamify.app.viewmodel.CommunityViewModel
import com.streamify.app.viewmodel.UiEvent
import com.streamify.app.viewmodel.UiEventBus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File
import com.streamify.app.util.SLog

enum class LandscapePlayerTab {
    UP_NEXT, LYRICS, RELATED
}

/**
 * Signature swipe-down-to-collapse zone (1:1 finger tracking, fling dismiss).
 * File-level extension: a previous local-fun definition was not resolvable
 * from every pane that needs it.
 */
private fun Modifier.collapseDragZone(
    morphController: LiquidMorphController?,
    collapseDragY: Animatable<Float, androidx.compose.animation.core.AnimationVector1D>,
    sheetGestureScope: kotlinx.coroutines.CoroutineScope,
    onCollapse: () -> Unit
): Modifier = Modifier.pointerInput(morphController) {
    val velocityTracker = VelocityTracker()
    detectVerticalDragGestures(
        onDragStart = { velocityTracker.resetTracking() },
        onVerticalDrag = { change, dragAmount ->
            change.consume()
            velocityTracker.addPosition(change.uptimeMillis, change.position)
            if (morphController != null) {
                // Drag down collapses: negate dy so progress falls 1:1 with
                // the finger; the shared cover morphs back to the dock.
                morphController.dragBy(-dragAmount)
            } else {
                sheetGestureScope.launch {
                    collapseDragY.snapTo((collapseDragY.value + dragAmount).coerceAtLeast(0f))
                }
            }
        },
        onDragEnd = {
            val velocityY = velocityTracker.calculateVelocity().y
            if (morphController != null) {
                // Downward release velocity is positive -> toward collapse;
                // the controller settles the landing and disposes the sheet.
                morphController.endGesture(velocityPxPerSec = -velocityY)
            } else {
                sheetGestureScope.launch {
                    val dismissPx = 140.dp.toPx()
                    if (collapseDragY.value > dismissPx || velocityY > 2400f) {
                        com.streamify.app.util.StreamifyHapticEngine.tokenImpactDetent()
                        onCollapse()
                        kotlinx.coroutines.delay(500)
                        collapseDragY.snapTo(0f)
                    } else {
                        collapseDragY.animateTo(
                            0f,
                            spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)
                        )
                    }
                }
            }
        },
        onDragCancel = {
            if (morphController != null) {
                morphController.cancelGesture()
            } else {
                sheetGestureScope.launch {
                    collapseDragY.animateTo(
                        0f,
                        spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)
                    )
                }
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun FullPlayerSheet(
    track: Track?,
    isPlaying: Boolean,
    // HOT flows (position ticks ~5Hz): collected only inside leaf nodes
    // (seekbars / lyric clock). Reading them here would recompose the whole
    // sheet on every tick.
    positionFlow: StateFlow<Long>,
    progressFlow: StateFlow<Float>,
    isBuffering: Boolean = false,
    isShuffleActive: Boolean,
    isRepeatActive: Boolean,
    dominantColor: Color,
    durationMs: Long = 0L,
    onCollapse: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onSeek: (Float) -> Unit,
    onShuffleToggle: () -> Unit,
    onRepeatToggle: () -> Unit,
    onToggleLike: () -> Unit,
    onQueueClick: (() -> Unit)? = null,
    onLyricsClick: (() -> Unit)? = null,
    onRadioClick: (() -> Unit)? = null,
    onJamClick: (() -> Unit)? = null,
    isAutoPlayEnabled: Boolean = false,
    onAutoPlayToggle: (() -> Unit)? = null,
    /** Liquid morph driver: when present, the sheet's entrance transform and
     *  collapse gestures route through the shared MiniPlayer <-> FullPlayer
     *  morph instead of the legacy local drag offset. */
    morphController: LiquidMorphController? = null
) {
    if (track == null) return

    val screenConfig = LocalScreenConfiguration.current
    val isLandscape = screenConfig.isLandscape
    val playerViewModel: com.streamify.app.viewmodel.PlayerViewModel = viewModel()

    val playerState by playerViewModel.playerState.collectAsState()
    val isVideoMode = playerState.isVideoMode

    // PERF: the pulse animation is composed ONLY while buffering. An
    // unconditional rememberInfiniteTransition keeps a Choreographer frame
    // loop alive for the entire sheet lifetime, blocking frame-clock idle and
    // draining battery during long listening sessions.
    // Consumers read .value inside graphicsLayer{} blocks -> draw-phase-only
    // invalidation; zero recomposition even while pulsing.
    val heroPulseAlpha: androidx.compose.runtime.State<Float> = if (isBuffering && !isVideoMode) {
        androidx.compose.animation.core.rememberInfiniteTransition(label = "HeroBufferingPulse").animateFloat(
            initialValue = 1.0f,
            targetValue = 0.55f,
            animationSpec = androidx.compose.animation.core.infiniteRepeatable(
                animation = androidx.compose.animation.core.tween(durationMillis = 750, easing = androidx.compose.animation.core.FastOutSlowInEasing),
                repeatMode = androidx.compose.animation.core.RepeatMode.Reverse
            ),
            label = "HeroPulseAlpha"
        )
    } else {
        remember { androidx.compose.runtime.mutableFloatStateOf(1f) }
    }

    val playbackButtonState = when {
        isBuffering -> com.streamify.app.viewmodel.PlaybackButtonState.BUFFERING
        isPlaying -> com.streamify.app.viewmodel.PlaybackButtonState.PLAYING
        else -> com.streamify.app.viewmodel.PlaybackButtonState.PAUSED
    }

    var showUpNextSheet by remember { mutableStateOf(false) }
    var showLyricsSheet by remember { mutableStateOf(false) }
    var showCommentsSheet by remember { mutableStateOf(false) }
    val communityViewModel: CommunityViewModel = viewModel()
    var showRelatedSheet by remember { mutableStateOf(false) }
    var landscapeTab by remember { mutableStateOf(LandscapePlayerTab.UP_NEXT) }

    // ── Phase 3: Canvas 8s loop backdrop (audio-only ↔ Canvas toggle) ─────
    // Resolution is SWR-cached + off-main; a track without canvas renders
    // no toggle at all (never a dead control).
    var canvasEnabled by remember { mutableStateOf(false) }
    val canvasState = com.streamify.app.ui.components.rememberCanvasLoop(
        videoId = track.ytmVideoId,
        trackTitle = track.title,
        trackArtist = track.artist
    )
    val canvasReady = canvasState is com.streamify.app.ui.components.CanvasLoopState.Ready
    // Track changed → drop a stale enabled toggle onto a canvas-less track.
    LaunchedEffect(track.id, canvasReady) {
        if (!canvasReady) canvasEnabled = false
    }

    // ── Android 14+ Predictive Back (Gap: gesture navigation polish) ──────
    // The system edge-swipe drives the liquid morph 1:1: the sheet scales
    // down and translates toward the mini dock proportionally with the
    // gesture. Completing the gesture pops into the mini player (controller
    // collapse); cancelling springs the sheet back to fully expanded.
    // Registered BEFORE the sub-sheet handlers so an open sub-sheet (added
    // later = higher dispatcher priority) still wins the back event.
    //
    // sheetAlive disambiguates gesture-cancel from composition teardown:
    // CancellationException fires for BOTH, but DisposableEffect.onDispose
    // runs synchronously during the disposal pass while the coroutine's
    // catch handler is dispatched — so a torn-down sheet observes
    // sheetAlive == false and skips the re-expand (otherwise disposal
    // mid-gesture — e.g. the track ending — silently re-armed expansion
    // and popped the sheet back in when the next track arrived).
    val sheetAlive = remember { mutableStateOf(true) }
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose { sheetAlive.value = false }
    }
    if (morphController != null) {
        androidx.activity.compose.PredictiveBackHandler(
            enabled = !showUpNextSheet && !showLyricsSheet &&
                    !showCommentsSheet && !showRelatedSheet
        ) { progress ->
            try {
                progress.collect { event ->
                    // System progress 0..1 -> morph progress 1..0: the sheet
                    // container transform (scale/translate/alpha) responds in
                    // the draw phase; the shared cover morphs toward the dock.
                    morphController.snapProgressTo(1f - event.progress)
                }
                // Flow completed normally: the system back gesture COMMITTED.
                morphController.collapse()
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Gesture cancelled (finger returned to the edge): spring back
                // — only while this sheet is still the composed surface.
                if (sheetAlive.value) {
                    morphController.expand()
                }
            }
        }
    }

    // --- PILLAR 2: LIFO Sub-Sheet Back Trapping ---
    BackHandler(enabled = showUpNextSheet) {
        showUpNextSheet = false
    }
    BackHandler(enabled = showLyricsSheet) {
        showLyricsSheet = false
    }
    BackHandler(enabled = showCommentsSheet) {
        showCommentsSheet = false
    }
    BackHandler(enabled = showRelatedSheet) {
        showRelatedSheet = false
    }
    // Pre-Allocated GPU Assets: Zero heap allocation inside Canvas draw phase
    val ambientGlowColors = remember(dominantColor) {
        listOf(
            dominantColor.copy(alpha = 0.35f),
            BgBase.copy(alpha = 0.85f),
            BgBase
        )
    }

    // --- Swipe-Down-to-Collapse Physics (professional sheet dismissal) ---
    // Dragging down on non-scrollable sheet chrome (header / artwork) follows the
    // finger 1:1; releasing past the distance threshold — or flinging fast enough —
    // collapses the player. Anything less springs back with zero bounce.
    val collapseDragY = remember { Animatable(0f) }
    val sheetGestureScope = rememberCoroutineScope()

    // Immersive chrome toggle: single-tap on artwork dims player furniture
    // (top bar / metadata) like professional apps. Tap again to restore.
    var chromeDimmed by remember { mutableStateOf(false) }
    val chromeAlpha by androidx.compose.animation.core.animateFloatAsState(
        // 0.35 floor: dimmed chrome stays discoverable — 0.15 made controls
        // effectively invisible on the dark background (invisible-UI trap).
        targetValue = if (chromeDimmed) 0.35f else 1f,
        animationSpec = tween(220),
        label = "playerChromeAlpha"
    )

    // Auto-recover: dimmed chrome restores itself after 3s so users can never
    // get stuck in a controls-invisible state.
    androidx.compose.runtime.LaunchedEffect(chromeDimmed) {
        if (chromeDimmed) {
            kotlinx.coroutines.delay(3000)
            chromeDimmed = false
        }
    }


    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BgBase)
            .graphicsLayer {
                if (morphController != null) {
                    // Liquid morph: the sheet rises from just above the dock,
                    // bottom-anchored scale growth, S-curve opacity — every
                    // value derived from the controller's progress INSIDE this
                    // lambda: the 120Hz morph stream invalidates layer
                    // properties only, never a composition.
                    val p = morphController.progress.value
                    translationY =
                        LiquidMorphGeometry.sheetTranslationFraction(p) * size.height
                    val s = LiquidMorphGeometry.sheetScale(p)
                    scaleX = s
                    scaleY = s
                    alpha = LiquidMorphGeometry.sheetAlpha(p)
                    transformOrigin = TransformOrigin(0.5f, 1f)
                } else {
                    translationY = collapseDragY.value
                    alpha = 1f - (collapseDragY.value / 1000f).coerceIn(0f, 0.4f)
                }
            }
    ) {
        // 1. Extreme Performance: GPU Radial Gradient Ambient Glow (0.01ms Single Draw Call)
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawRect(
                brush = Brush.radialGradient(
                    colors = ambientGlowColors,
                    center = Offset(
                        if (isLandscape) size.width * 0.22f else size.width / 2,
                        if (isLandscape) size.height / 2 else size.height * 0.28f
                    ),
                    radius = if (isLandscape) size.width * 0.8f else size.width * 1.15f
                )
            )
        }

        // 1b. Phase 3 — Canvas 8s looping video backdrop with ambient glow
        // blending. Layered BEHIND all player furniture; audio stays on the
        // main session (the loop player is muted by construction).
        val readyLoop = (canvasState as? com.streamify.app.ui.components.CanvasLoopState.Ready)?.loop
        if (readyLoop != null && canvasEnabled) {
            com.streamify.app.ui.components.CanvasBackdrop(
                loop = readyLoop,
                enabled = canvasEnabled,
                dominantColor = dominantColor,
                modifier = Modifier.fillMaxSize()
            )
        }

        if (isLandscape) {
            // =========================================================================
            // ADAPTIVE DUAL-PANE LANDSCAPE / TABLET LAYOUT
            // Left Pane (42%): Hero Art / 60FPS Video Surface + Controls
            // Right Pane (58%): Dynamic Tabbed Drawer (Up Next / Lyrics / Related)
            // =========================================================================
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // ─── LEFT PANE: HERO ARTWORK / VIDEO & PLAYBACK CONTROLS (42%) ───
                Column(
                    modifier = Modifier
                        .weight(0.42f)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    // Top Bar: Collapse + Song/Video Switcher + Cast
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .collapseDragZone(morphController, collapseDragY, sheetGestureScope, onCollapse),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = onCollapse) {
                            Icon(
                                imageVector = Icons.Filled.ExpandMore,
                                contentDescription = "Collapse",
                                tint = TextMain,
                                modifier = Modifier.size(28.dp)
                            )
                        }

                        YtSongVideoSwitcher(
                            isVideo = isVideoMode,
                            onToggle = { playerViewModel.toggleVideoMode(it) }
                        )

                        // Phase 3 — Canvas toggle (landscape top bar)
                        com.streamify.app.ui.components.CanvasToggleChip(
                            enabled = canvasEnabled,
                            visible = canvasReady,
                            onToggle = { canvasEnabled = it }
                        )

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { onJamClick?.invoke() }) {
                                Icon(
                                    imageVector = Icons.Filled.Cast,
                                    contentDescription = "Cast",
                                    tint = TextMain,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    val hasVideoStream = remember(track.id, track.filepath, isVideoMode) {
                        track.filepath.endsWith(".mp4") || track.filepath.contains("mime=video") || (track.ytmVideoId != null && isVideoMode)
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(24.dp))
                            .then(
                                if (morphController != null) {
                                    Modifier.reportBoundsTo(morphController.heroCoverRect)
                                } else Modifier
                            )
                            .background(androidx.compose.ui.graphics.Color.Black)
                            .graphicsLayer {
                                if (isBuffering && !isVideoMode) {
                                    val pulse = heroPulseAlpha.value
                                    alpha = pulse
                                    scaleX = 0.98f + (pulse * 0.02f)
                                    scaleY = 0.98f + (pulse * 0.02f)
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        TrackCoverArt(
                            coverArtPath = track.coverArtPath,
                            title = track.title,
                            artist = track.artist,
                            modifier = Modifier.fillMaxSize(),
                            shape = RoundedCornerShape(16.dp)
                        )

                        if (isVideoMode && hasVideoStream && playerViewModel.getController() != null) {
                            AndroidView(
                                factory = { ctx ->
                                    PlayerView(ctx).apply {
                                        player = playerViewModel.getController()
                                        useController = false
                                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                                        setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)
                                    }
                                },
                                update = { view ->
                                    view.player = playerViewModel.getController()
                                },
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Track Title, Artist & Like Button Row
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = track.title,
                                style = LocalAppTypography.current.playerTitle.copy(fontSize = 18.sp),
                                color = TextMain,
                                maxLines = 1,
                                modifier = Modifier.basicMarquee()
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "${track.artist}${if (track.album.isNotBlank() && track.album != "Streamify") " • " + track.album else ""}",
                                style = LocalAppTypography.current.playerArtist.copy(fontSize = 13.sp),
                                color = TextSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        IconButton(onClick = {
                            com.streamify.app.ui.util.HapticFeedbackManager.get()?.likeHeartbeat()
                                ?: com.streamify.app.util.StreamifyHapticEngine.heartbeatFlutter()
                            onToggleLike()
                        }) {
                            Icon(
                                imageVector = if (track.isLiked) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                                contentDescription = if (track.isLiked) "Unlike" else "Like",
                                tint = if (track.isLiked) StreamifyColors.Primary else TextMain,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    // YouTube Music Action Pills (Like, Comments, Radio, Jam)
                    YtPlayerActionPills(
                        isLiked = track.isLiked,
                        onToggleLike = {
                            com.streamify.app.ui.util.HapticFeedbackManager.get()?.likeHeartbeat()
                                ?: com.streamify.app.util.StreamifyHapticEngine.heartbeatFlutter()
                            onToggleLike()
                        },
                        onCommentsClick = { showCommentsSheet = true },
                        onRadioClick = onRadioClick,
                        onJamClick = onJamClick,
                        onDownloadClick = { /* Download */ }
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    // Precision Canvas SeekBar
                    val effectiveDurationMs = if (durationMs > 0) durationMs else (track.durationSec * 1000L)
                    YtPlayerSeekBar(
                        positionFlow = positionFlow,
                        durationMs = effectiveDurationMs,
                        onSeek = onSeek
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    // Playback Controls Row
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = onShuffleToggle) {
                            Icon(
                                imageVector = if (isShuffleActive) Icons.Filled.Shuffle else Icons.Outlined.Shuffle,
                                contentDescription = "Shuffle",
                                tint = if (isShuffleActive) ActiveControl else TextSecondary,
                                modifier = Modifier.size(24.dp)
                            )
                        }

                        IconButton(onClick = onPrevious) {
                            Icon(
                                imageVector = Icons.Filled.SkipPrevious,
                                contentDescription = "Previous",
                                tint = TextMain,
                                modifier = Modifier.size(32.dp)
                            )
                        }

                        Box(
                            modifier = Modifier
                                .size(54.dp)
                                .clip(CircleShape)
                                .background(ActiveControl),
                            contentAlignment = Alignment.Center
                        ) {
                            IconButton(onClick = onPlayPause) {
                                AnimatedContent(
                                    targetState = playbackButtonState,
                                    transitionSpec = { fadeIn(tween(140)) togetherWith fadeOut(tween(140)) },
                                    label = "LandscapePlayPauseAnimatedContent"
                                ) { state ->
                                    when (state) {
                                        com.streamify.app.viewmodel.PlaybackButtonState.BUFFERING -> {
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(24.dp),
                                                color = TextOnActiveChip,
                                                strokeWidth = 2.5.dp,
                                                strokeCap = androidx.compose.ui.graphics.StrokeCap.Round
                                            )
                                        }
                                        com.streamify.app.viewmodel.PlaybackButtonState.PLAYING -> {
                                            Icon(
                                                imageVector = Icons.Filled.Pause,
                                                contentDescription = "PlayPause",
                                                tint = TextOnActiveChip,
                                                modifier = Modifier.size(28.dp)
                                            )
                                        }
                                        com.streamify.app.viewmodel.PlaybackButtonState.PAUSED -> {
                                            Icon(
                                                imageVector = Icons.Filled.PlayArrow,
                                                contentDescription = "PlayPause",
                                                tint = TextOnActiveChip,
                                                modifier = Modifier.size(28.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        IconButton(onClick = onNext) {
                            Icon(
                                imageVector = Icons.Filled.SkipNext,
                                contentDescription = "Next",
                                tint = TextMain,
                                modifier = Modifier.size(32.dp)
                            )
                        }

                        IconButton(onClick = onRepeatToggle) {
                            Icon(
                                imageVector = if (isRepeatActive) Icons.Filled.Repeat else Icons.Outlined.Repeat,
                                contentDescription = "Repeat",
                                tint = if (isRepeatActive) ActiveControl else TextSecondary,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                }

                // ─── RIGHT PANE: DYNAMIC TABBED DRAWER (UP NEXT | LYRICS | RELATED) (58%) ───
                Column(
                    modifier = Modifier
                        .weight(0.58f)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(16.dp))
                        .background(BgSurfaceElevated.copy(alpha = 0.55f))
                        .padding(14.dp)
                ) {
                    // Segmented Capsule Header Tabs
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(24.dp))
                            .background(BgCard)
                            .padding(4.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        val queueCount = playerState.queue.size
                        val tabItems = listOf(
                            LandscapePlayerTab.UP_NEXT to "UP NEXT ($queueCount)",
                            LandscapePlayerTab.LYRICS to "LYRICS",
                            LandscapePlayerTab.RELATED to "RELATED"
                        )

                        tabItems.forEach { (tabEnum, tabTitle) ->
                            val isSelected = landscapeTab == tabEnum
                            Surface(
                                color = if (isSelected) ActiveControl else Color.Transparent,
                                shape = RoundedCornerShape(20.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable { landscapeTab = tabEnum }
                            ) {
                                Text(
                                    text = tabTitle,
                                    style = LocalAppTypography.current.chipText.copy(
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                        fontSize = 12.sp
                                    ),
                                    color = if (isSelected) TextOnActiveChip else TextSecondary,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.padding(vertical = 8.dp)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Stateful AnimatedContent Tab Router (Preserves scroll position & 0-recomputation)
                    AnimatedContent(
                        targetState = landscapeTab,
                        transitionSpec = {
                            fadeIn(animationSpec = tween(150)) togetherWith fadeOut(animationSpec = tween(150))
                        },
                        label = "LandscapeTabTransition",
                        modifier = Modifier.fillMaxSize()
                    ) { activeTab ->
                        when (activeTab) {
                            LandscapePlayerTab.UP_NEXT -> {
                                LandscapeQueuePane(
                                    queue = playerState.queue,
                                    currentIndex = playerState.currentIndex,
                                    currentTrack = playerState.currentTrack,
                                    isPlaying = playerState.isPlaying,
                                    onTrackClick = { clickedTrack ->
                                        playerViewModel.playTrack(clickedTrack, playerState.queue)
                                    }
                                )
                            }
                            LandscapePlayerTab.LYRICS -> {
                                LandscapeLyricsPane(
                                    track = track,
                                    positionFlow = positionFlow,
                                    isPlaying = isPlaying,
                                    onSeek = { posMs ->
                                        if (durationMs > 0) onSeek(posMs.toFloat() / durationMs.toFloat())
                                    }
                                )
                            }
                            LandscapePlayerTab.RELATED -> {
                                LandscapeRelatedPane(
                                    track = track,
                                    playerViewModel = playerViewModel,
                                    onTrackClick = { clickedTrack ->
                                        playerViewModel.playSingleTrack(clickedTrack)
                                    }
                                )
                            }
                        }
                    }
                }
            }
        } else {
            // =========================================================================
            // STANDARD PORTRAIT MOBILE LAYOUT
            // =========================================================================
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .centerInLargeScreen()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    // Escape-hatch scroll: if any section ever grows beyond the
                    // viewport (small screens, font-scale spikes, future children),
                    // controls can no longer be pushed permanently off-screen — they
                    // scroll instead. Child collapse-drag/seek gesture detectors win
                    // touch-slop before the parent scroll, so sheet gestures are
                    // unaffected. When content fits, SpaceBetween still spreads it.
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.SpaceBetween,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // --- TOP BAR (Collapse Chevron, Song/Video Switcher, Actions) ---
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .graphicsLayer { alpha = chromeAlpha }
                        .padding(vertical = 4.dp)
                        .collapseDragZone(morphController, collapseDragY, sheetGestureScope, onCollapse),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onCollapse) {
                        Icon(
                            imageVector = Icons.Filled.ExpandMore,
                            contentDescription = "Collapse",
                            tint = TextMain,
                            modifier = Modifier.size(32.dp)
                        )
                    }

                    YtSongVideoSwitcher(
                        isVideo = isVideoMode,
                        onToggle = { playerViewModel.toggleVideoMode(it) }
                    )

                    // Phase 3 — Canvas toggle (portrait top bar)
                    com.streamify.app.ui.components.CanvasToggleChip(
                        enabled = canvasEnabled,
                        visible = canvasReady,
                        onToggle = { canvasEnabled = it }
                    )

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { onJamClick?.invoke() }) {
                            Icon(
                                imageVector = Icons.Filled.Cast,
                                contentDescription = "Cast",
                                tint = TextMain,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        IconButton(onClick = { /* Additional options */ }) {
                            Icon(
                                imageVector = Icons.Filled.MoreVert,
                                contentDescription = "Options",
                                tint = TextMain,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                }

                var seekRippleSide by remember { mutableStateOf<Int?>(null) }
                val seekRippleScope = rememberCoroutineScope()

                // --- HERO DYNAMIC MORPHING ALBUM ARTWORK / HARDWARE VIDEO SURFACE ---
                // Deterministic sizing: NO weight() — the hero can never consume
                // unbounded remaining height and push controls off-screen.
                Box(
                    modifier = Modifier
                        .fillMaxWidth(if (isVideoMode) 1f else 0.88f)
                        .heightIn(max = if (isVideoMode) 240.dp else 320.dp)
                        .aspectRatio(if (isVideoMode) 16f / 9f else 1f)
                        .clip(RoundedCornerShape(24.dp))
                        .then(
                            if (morphController != null) {
                                Modifier.reportBoundsTo(morphController.heroCoverRect)
                            } else Modifier
                        )
                        .background(androidx.compose.ui.graphics.Color.Black)
                        .graphicsLayer {
                            if (isBuffering && !isVideoMode) {
                                val pulse = heroPulseAlpha.value
                                alpha = pulse
                                scaleX = 0.98f + (pulse * 0.02f)
                                scaleY = 0.98f + (pulse * 0.02f)
                            }
                        }
                        .collapseDragZone(morphController, collapseDragY, sheetGestureScope, onCollapse)
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onTap = { chromeDimmed = !chromeDimmed },
                                onLongPress = {
                                    com.streamify.app.util.StreamifyHapticEngine.magneticQueueGrab()
                                    showLyricsSheet = true
                                },
                                onDoubleTap = { offset: Offset ->
                                    val isRightSide = offset.x > (this@pointerInput.size.width / 2f)
                                    val seekDeltaMs = if (isRightSide) 10_000L else -10_000L
                                    com.streamify.app.util.StreamifyHapticEngine.scrubberTick()
                                    playerViewModel.seekRelative(seekDeltaMs)
                                    seekRippleSide = if (isRightSide) 1 else -1
                                    seekRippleScope.launch {
                                        kotlinx.coroutines.delay(650)
                                        seekRippleSide = null
                                    }
                                }
                            )
                        },
                    contentAlignment = Alignment.Center
                ) {
                    val hasVideoStream = remember(track.id, track.filepath, isVideoMode) {
                        track.filepath.endsWith(".mp4") || track.filepath.contains("mime=video") || (track.ytmVideoId != null && isVideoMode)
                    }

                    TrackCoverArt(
                        coverArtPath = track.coverArtPath,
                        title = track.title,
                        artist = track.artist,
                        modifier = Modifier.fillMaxSize(),
                        shape = RoundedCornerShape(16.dp)
                    )

                    if (isVideoMode && hasVideoStream && playerViewModel.getController() != null) {
                        AndroidView(
                            factory = { ctx ->
                                PlayerView(ctx).apply {
                                    player = playerViewModel.getController()
                                    useController = false
                                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                                    setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)
                                }
                            },
                            update = { view ->
                                view.player = playerViewModel.getController()
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                    }

                    if (seekRippleSide != null) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.Black.copy(alpha = 0.42f)),
                            contentAlignment = if (seekRippleSide == 1) Alignment.CenterEnd else Alignment.CenterStart
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.padding(horizontal = 24.dp)
                            ) {
                                Icon(
                                    imageVector = if (seekRippleSide == 1) Icons.Filled.FastForward else Icons.Filled.FastRewind,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(36.dp)
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = if (seekRippleSide == 1) "+10s" else "-10s",
                                    style = LocalAppTypography.current.songArtist.copy(
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold
                                    ),
                                    color = Color.White
                                )
                            }
                        }
                    }
                }

                // --- METADATA & NEURAL DSP PILL ---
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .graphicsLayer { alpha = chromeAlpha }
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = track.title,
                            style = LocalAppTypography.current.playerTitle,
                            color = TextMain,
                            maxLines = 1,
                            modifier = Modifier.basicMarquee()
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "${track.artist}${if (track.album.isNotBlank() && track.album != "Streamify") " • " + track.album else ""}",
                                style = LocalAppTypography.current.playerArtist,
                                color = TextSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false)
                            )

                            if (track.isProcessed && track.bpm > 0f) {
                                Spacer(modifier = Modifier.width(8.dp))
                                Surface(
                                    shape = RoundedCornerShape(4.dp),
                                    color = BgSurfaceElevated,
                                    modifier = Modifier.padding(top = 1.dp)
                                ) {
                                    Text(
                                        text = "${track.bpm.toInt()} BPM${if (track.key.isNotBlank()) " • " + track.key else ""}",
                                        style = LocalAppTypography.current.chipText.copy(fontSize = 10.sp, fontWeight = FontWeight.SemiBold),
                                        color = ActiveControl,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }
                    }

                    // Explicit Lyrics affordance (was hidden behind a long-press).
                    IconButton(onClick = { showLyricsSheet = true }) {
                        Icon(
                            imageVector = Icons.Filled.Subtitles,
                            contentDescription = "Lyrics",
                            tint = TextSecondary,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }

                // --- YOUTUBE MUSIC ACTION PILLS RAIL ---
                YtPlayerActionPills(
                    isLiked = track.isLiked,
                    onToggleLike = {
                        com.streamify.app.util.StreamifyHapticEngine.heartbeatFlutter()
                        onToggleLike()
                    },
                    onCommentsClick = { showCommentsSheet = true },
                    onRadioClick = onRadioClick,
                    onJamClick = onJamClick,
                    onDownloadClick = { /* Download */ }
                )

                // --- PRECISION CANVAS SEEKBAR & PLAYBACK CONTROLS ---
                Column(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    val effectiveDurationMs = if (durationMs > 0) durationMs else (track.durationSec * 1000L)
                    YtPlayerSeekBar(
                        positionFlow = positionFlow,
                        durationMs = effectiveDurationMs,
                        onSeek = onSeek
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = onShuffleToggle) {
                            Icon(
                                imageVector = if (isShuffleActive) Icons.Filled.Shuffle else Icons.Outlined.Shuffle,
                                contentDescription = "Shuffle",
                                tint = if (isShuffleActive) ActiveControl else TextSecondary,
                                modifier = Modifier.size(26.dp)
                            )
                        }

                        IconButton(onClick = {
                            com.streamify.app.util.StreamifyHapticEngine.magneticDetent()
                            onPrevious()
                        }) {
                            Icon(
                                imageVector = Icons.Filled.SkipPrevious,
                                contentDescription = "Previous",
                                tint = TextMain,
                                modifier = Modifier.size(38.dp)
                            )
                        }

                        // 64dp YouTube Music White Play Button — crisp
                        // confirmation click (Phase 5 haptic engine).
                        Box(
                            modifier = Modifier
                                .size(64.dp)
                                .clip(CircleShape)
                                .background(ActiveControl),
                            contentAlignment = Alignment.Center
                        ) {
                            IconButton(onClick = {
                                com.streamify.app.ui.util.HapticFeedbackManager.get()?.playPauseClick()
                                    ?: com.streamify.app.util.StreamifyHapticEngine.playbackPulse()
                                onPlayPause()
                            }) {
                                AnimatedContent(
                                    targetState = playbackButtonState,
                                    transitionSpec = { fadeIn(tween(140)) togetherWith fadeOut(tween(140)) },
                                    label = "PortraitPlayPauseAnimatedContent"
                                ) { state ->
                                    when (state) {
                                        com.streamify.app.viewmodel.PlaybackButtonState.BUFFERING -> {
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(28.dp),
                                                color = TextOnActiveChip,
                                                strokeWidth = 2.8.dp,
                                                strokeCap = androidx.compose.ui.graphics.StrokeCap.Round
                                            )
                                        }
                                        com.streamify.app.viewmodel.PlaybackButtonState.PLAYING -> {
                                            Icon(
                                                imageVector = Icons.Filled.Pause,
                                                contentDescription = "PlayPause",
                                                tint = TextOnActiveChip,
                                                modifier = Modifier.size(34.dp)
                                            )
                                        }
                                        com.streamify.app.viewmodel.PlaybackButtonState.PAUSED -> {
                                            Icon(
                                                imageVector = Icons.Filled.PlayArrow,
                                                contentDescription = "PlayPause",
                                                tint = TextOnActiveChip,
                                                modifier = Modifier.size(34.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        IconButton(onClick = {
                            com.streamify.app.util.StreamifyHapticEngine.magneticDetent()
                            onNext()
                        }) {
                            Icon(
                                imageVector = Icons.Filled.SkipNext,
                                contentDescription = "Next",
                                tint = TextMain,
                                modifier = Modifier.size(38.dp)
                            )
                        }

                        IconButton(onClick = onRepeatToggle) {
                            Icon(
                                imageVector = if (isRepeatActive) Icons.Filled.Repeat else Icons.Outlined.Repeat,
                                contentDescription = "Repeat",
                                tint = if (isRepeatActive) ActiveControl else TextSecondary,
                                modifier = Modifier.size(26.dp)
                            )
                        }
                    }
                }

                // --- BOTTOM TABS (Opens Modal Sheets) ---
                YtPlayerBottomTabs(
                    activeTab = "",
                    onQueueClick = { showUpNextSheet = true },
                    onLyricsClick = { showLyricsSheet = true },
                    onRelatedClick = { showRelatedSheet = true }
                )
            }
        }
    }

    if (showUpNextSheet) {
        ModalBottomSheet(
            onDismissRequest = { showUpNextSheet = false },
            containerColor = BgBase,
            scrimColor = Color.Black.copy(alpha = 0.6f),
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.85f)
                    .padding(horizontal = 16.dp)
            ) {
                LandscapeQueuePane(
                    queue = playerState.queue,
                    currentIndex = playerState.currentIndex,
                    currentTrack = playerState.currentTrack,
                    isPlaying = playerState.isPlaying,
                    onTrackClick = { clicked ->
                        playerViewModel.playTrack(clicked, playerState.queue)
                    }
                )
            }
        }
    }

    if (showLyricsSheet) {
        ModalBottomSheet(
            onDismissRequest = { showLyricsSheet = false },
            containerColor = BgBase,
            scrimColor = Color.Black.copy(alpha = 0.6f),
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.85f)
                    .padding(horizontal = 16.dp)
            ) {
                LandscapeLyricsPane(
                    track = track,
                    positionFlow = positionFlow,
                    isPlaying = isPlaying,
                    onSeek = { posMs ->
                        if (durationMs > 0) onSeek(posMs.toFloat() / durationMs.toFloat())
                    }
                )
            }
        }
    }

    if (showCommentsSheet) {
        // Scoped collection: the sheet receives the ticker flow itself and
        // collects inside its own recomposition scope (see CommentsSheet).
        CommentsSheet(
            track = track,
            positionFlow = positionFlow,
            communityViewModel = communityViewModel,
            onSeekTo = { posMs ->
                if (durationMs > 0) onSeek(posMs.toFloat() / durationMs.toFloat())
            },
            onDismiss = { showCommentsSheet = false }
        )
    }

    if (showRelatedSheet) {
        val onRelatedTrackClick = remember(playerViewModel) {
            { clickedTrack: Track ->
                playerViewModel.playSingleTrack(clickedTrack)
            }
        }
        RelatedDiscoverSheet(
            track = track,
            playerViewModel = playerViewModel,
            onTrackClick = onRelatedTrackClick,
            onDismiss = { showRelatedSheet = false }
        )
    }
}
