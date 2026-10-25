package com.streamify.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.streamify.app.data.models.Track
import com.streamify.app.ui.motion.LiquidMorphController
import com.streamify.app.ui.motion.LiquidMorphGeometry
import com.streamify.app.ui.motion.reportBoundsTo
import com.streamify.app.ui.theme.*

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.graphics.StrokeCap
import com.streamify.app.viewmodel.PlaybackButtonState
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

@Composable
fun MiniPlayerBar(
    track: Track?,
    isPlaying: Boolean,
    progressFlow: StateFlow<Float>,
    isBuffering: Boolean = false,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onExpand: () -> Unit,
    onToggleLike: (() -> Unit)? = null,
    onSwipeDown: (() -> Unit)? = null,
    alpha: Float = 1f,
    tokenController: QuantumSonicTokenController? = null,
    morphController: LiquidMorphController? = null,
    /** Title of the next queue entry for the swipe-to-skip peek preview. */
    nextTrackTitle: String? = null,
    /** Title of the previous queue entry for the swipe-to-skip peek preview. */
    previousTrackTitle: String? = null,
    modifier: Modifier = Modifier
) {
    if (track == null) return
    // Snapshot-backed subscription: the State object is created here but its
    // value is ONLY read inside the draw lambda of the 2dp strip below —
    // 5Hz playback ticks trigger DRAW-ONLY invalidation of that strip,
    // never a recomposition of this bar or the dock above it.
    val progressState = progressFlow.collectAsState()
    // Lambda state provider (120Hz draw-phase contract): deferred read.
    val progressProvider: () -> Float = { progressState.value.coerceIn(0f, 1f) }
    // Always-current callback reference for long-lived pointer detectors.
    val currentOnSwipeDown by androidx.compose.runtime.rememberUpdatedState(onSwipeDown)

    val buttonState = when {
        isBuffering -> PlaybackButtonState.BUFFERING
        isPlaying -> PlaybackButtonState.PLAYING
        else -> PlaybackButtonState.PAUSED
    }

    var isAbsorbing by remember { mutableStateOf(false) }
    if (tokenController != null) {
        LaunchedEffect(tokenController.stage) {
            if (tokenController.stage == TokenStage.IMPACT) {
                isAbsorbing = true
                kotlinx.coroutines.delay(220)
                isAbsorbing = false
            }
        }
    }

    val recoilScaleX by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (isAbsorbing) 1.045f else 1.0f,
        animationSpec = androidx.compose.animation.core.spring(
            dampingRatio = androidx.compose.animation.core.Spring.DampingRatioMediumBouncy,
            stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow
        ),
        label = "MiniPlayerRecoilX"
    )

    val recoilScaleY by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (isAbsorbing) 0.935f else 1.0f,
        animationSpec = androidx.compose.animation.core.spring(
            dampingRatio = androidx.compose.animation.core.Spring.DampingRatioMediumBouncy,
            stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow
        ),
        label = "MiniPlayerRecoilY"
    )

    val density = androidx.compose.ui.platform.LocalDensity.current
    // 80dp trigger threshold (design spec): arming past it fires the haptic
    // once and commits the skip on release.
    val swipeThresholdPx = with(density) { 80.dp.toPx() }
    val dragOffsetX = remember { androidx.compose.animation.core.Animatable(0f) }
    // Raw (unresisted) finger displacement — drives arming + peek reveal.
    val rawDragX = remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    val coroutineScope = rememberCoroutineScope()

    Surface(
        color = BgSurfaceElevated,
        modifier = modifier
            .fillMaxWidth()
            .height(64.dp)
            .graphicsLayer {
                // Liquid-morph response: the dock fades, lifts and shrinks as
                // the shared cover layer expands toward the FullPlayerSheet.
                // Read inside the lambda -> draw-phase-only invalidation.
                val morphP = morphController?.progress?.value ?: 0f
                this.alpha = alpha * LiquidMorphGeometry.miniBarAlpha(morphP)
                val shrink = 1f - 0.04f * morphP
                this.scaleX = recoilScaleX * shrink
                this.scaleY = recoilScaleY * shrink
                this.translationX = dragOffsetX.value
                this.translationY =
                    -LiquidMorphGeometry.miniBarLiftFraction(morphP) * 64.dp.toPx()
            }
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(onTap = { onExpand() })
                }
                .pointerInput(morphController) {
                    // Vertical gestures, sign-resolved:
                    //  - UPWARD drag -> liquid morph expansion of the
                    //    FullPlayerSheet, 1:1 with the finger (drag-to-open).
                    //  - DOWNWARD flick past the dismiss threshold -> dock
                    //    dismissal for the current track (auto-restores when
                    //    the next track starts).
                    var totalDragY = 0f
                    var expandGesture = false
                    val tracker = VelocityTracker()
                    detectVerticalDragGestures(
                        onDragStart = { tracker.resetTracking() },
                        onVerticalDrag = { change, dragAmount ->
                            change.consume()
                            tracker.addPosition(change.uptimeMillis, change.position)
                            totalDragY += dragAmount
                            if (!expandGesture && totalDragY < -12f && morphController != null) {
                                expandGesture = true
                                morphController.beginGesture(fromExpanded = false)
                            }
                            if (expandGesture) {
                                // dy < 0 while expanding: negate -> progress rises.
                                morphController?.dragBy(-dragAmount)
                            }
                        },
                        onDragEnd = {
                            val velocityY = tracker.calculateVelocity().y
                            if (expandGesture) {
                                morphController?.endGesture(velocityPxPerSec = -velocityY)
                            } else if (totalDragY > 140f) {
                                com.streamify.app.util.StreamifyHapticEngine.tokenImpactDetent()
                                currentOnSwipeDown?.invoke()
                            }
                            totalDragY = 0f
                            expandGesture = false
                        },
                        onDragCancel = {
                            if (expandGesture) {
                                morphController?.cancelGesture()
                            }
                            totalDragY = 0f
                            expandGesture = false
                        }
                    )
                }
                .pointerInput(Unit) {
                    // Horizontal swipe-to-skip: 1:1 finger tracking with a
                    // saturating spring-resistance tail past the 80dp trigger,
                    // an arming haptic exactly at the threshold, and a
                    // peek-preview of the destination track title.
                    var rawDrag = 0f
                    var armed = false
                    // Hysteresis band: retreating inside 75% of the trigger
                    // distance disarms the skip — release after a retreat is
                    // a no-op, not a surprise track change.
                    val disarmThresholdPx = swipeThresholdPx * 0.75f
                    detectHorizontalDragGestures(
                        onHorizontalDrag = { change, dragAmount ->
                            change.consume()
                            rawDrag += dragAmount
                            rawDragX.floatValue = rawDrag
                            coroutineScope.launch {
                                dragOffsetX.snapTo(
                                    LiquidMorphGeometry.swipeResistanceOffset(rawDrag, swipeThresholdPx)
                                )
                            }
                                if (!armed && kotlin.math.abs(rawDrag) >= swipeThresholdPx) {
                                armed = true
                                // Trigger haptic at the 80dp drag threshold.
                                com.streamify.app.ui.util.HapticFeedbackManager.get()?.skipTrigger()
                                    ?: com.streamify.app.util.StreamifyHapticEngine.tokenImpactDetent()
                            } else if (armed && kotlin.math.abs(rawDrag) < disarmThresholdPx) {
                                // Finger came most of the way back: cancel the
                                // armed skip. Without this the trigger stuck
                                // once armed, and release after a retreat
                                // still skipped — reading direction from the
                                // SATURATED resisted offset, which can even
                                // flip sign on a retreat past zero.
                                armed = false
                            }
                        },
                        onDragEnd = {
                            // Capture BEFORE the launch: rawDrag/armed reset
                            // synchronously right after, and the coroutine is
                            // queued behind them on the same main dispatcher.
                            val totalRawDrag = rawDrag
                            val skipArmed = armed
                            coroutineScope.launch {
                                if (skipArmed) {
                                    // Direction from the raw finger displacement,
                                    // not the resisted dragOffsetX (saturating
                                    // resistance tail can't express intent).
                                    if (totalRawDrag < 0f) onNext() else onPrevious()
                                }
                                dragOffsetX.animateTo(
                                    targetValue = 0f,
                                    animationSpec = androidx.compose.animation.core.spring(
                                        dampingRatio = androidx.compose.animation.core.Spring.DampingRatioMediumBouncy,
                                        stiffness = androidx.compose.animation.core.Spring.StiffnessLow
                                    )
                                )
                            }
                            rawDrag = 0f
                            armed = false
                            rawDragX.floatValue = 0f
                        },
                        onDragCancel = {
                            coroutineScope.launch {
                                dragOffsetX.animateTo(
                                    targetValue = 0f,
                                    animationSpec = androidx.compose.animation.core.spring(
                                        dampingRatio = androidx.compose.animation.core.Spring.DampingRatioMediumBouncy,
                                        stiffness = androidx.compose.animation.core.Spring.StiffnessLow
                                    )
                                )
                            }
                            rawDrag = 0f
                            armed = false
                            rawDragX.floatValue = 0f
                        }
                    )
                }
        ) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(start = 8.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 48x48 Album Art — 8dp corner radius (morph endpoint spec).
                // Root-coordinate bounds are reported to the liquid morph
                // controller so the shared cover layer can interpolate from
                // this exact rect up to the FullPlayer hero art rect.
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(BgCard)
                        .then(
                            if (morphController != null) {
                                Modifier.reportBoundsTo(morphController.miniCoverRect)
                            } else {
                                Modifier
                            }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    if (!track.coverArtPath.isNullOrBlank()) {
                        AsyncImage(
                            model = track.coverArtPath,
                            contentDescription = track.title,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = androidx.compose.ui.layout.ContentScale.Crop
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Filled.PlayArrow,
                            contentDescription = null,
                            tint = TextSecondary,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.width(10.dp))

                // Song Title & Artist Column
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = track.title,
                        style = LocalAppTypography.current.songTitle,
                        color = TextMain,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = track.artist,
                        style = LocalAppTypography.current.songArtist,
                        color = TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // Like / Heart Action — dual-pulse heartbeat, synchronized
                // with the expanding heart-burst animation.
                if (onToggleLike != null) {
                    IconButton(onClick = {
                        com.streamify.app.ui.util.HapticFeedbackManager.get()?.likeHeartbeat()
                            ?: com.streamify.app.util.StreamifyHapticEngine.heartbeatFlutter()
                        onToggleLike()
                    }) {
                        Icon(
                            imageVector = if (track.isLiked) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                            contentDescription = if (track.isLiked) "Unlike" else "Like",
                            tint = if (track.isLiked) StreamifyColors.Primary else TextMain,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }

                // Play / Pause Action — crisp confirmation click.
                IconButton(onClick = {
                    com.streamify.app.ui.util.HapticFeedbackManager.get()?.playPauseClick()
                        ?: com.streamify.app.util.StreamifyHapticEngine.playbackPulse()
                    onPlayPause()
                }) {
                    Box(contentAlignment = Alignment.Center) {
                        AnimatedContent(
                            targetState = buttonState,
                            transitionSpec = { fadeIn(tween(120)) togetherWith fadeOut(tween(120)) },
                            label = "MiniPlayerPlayPauseAnimatedContent"
                        ) { state ->
                            when (state) {
                                PlaybackButtonState.BUFFERING -> {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(20.dp),
                                        color = TextMain,
                                        strokeWidth = 2.dp,
                                        strokeCap = StrokeCap.Round
                                    )
                                }
                                PlaybackButtonState.PLAYING -> {
                                    Icon(
                                        imageVector = Icons.Filled.Pause,
                                        contentDescription = "Pause",
                                        tint = TextMain,
                                        modifier = Modifier.size(28.dp)
                                    )
                                }
                                PlaybackButtonState.PAUSED -> {
                                    Icon(
                                        imageVector = Icons.Filled.PlayArrow,
                                        contentDescription = "Play",
                                        tint = TextMain,
                                        modifier = Modifier.size(28.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                // Skip Next Action
                IconButton(onClick = onNext) {
                    Icon(
                        imageVector = Icons.Filled.SkipNext,
                        contentDescription = "Next",
                        tint = TextMain,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            // Swipe-to-skip peek preview — leaf-scoped: reads the raw drag
            // State HERE so the reveal recomposes only this overlay, never
            // the bar or the dock above it.
            SwipePeekOverlay(
                rawDragX = rawDragX,
                thresholdPx = swipeThresholdPx,
                dragOffsetX = dragOffsetX,
                nextTitle = nextTrackTitle,
                prevTitle = previousTrackTitle
            )

            // 2dp Micro-Progress Bar — drawn flush at the bottom edge in the
            // DRAW PHASE via [Modifier.drawWithCache] + lambda state provider:
            // zero recomposition, zero remeasure, zero allocation per tick.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp)
                    .align(Alignment.BottomCenter)
                    .drawWithCache {
                        val inactive = Divider
                        val active = ActiveControl
                        onDrawBehind {
                            drawRect(color = inactive, size = size)
                            val fraction = progressProvider()
                            drawRect(
                                color = active,
                                size = Size(width = size.width * fraction, height = size.height)
                            )
                        }
                    }
            )
        }
    }
}

/**
 * Swipe-to-skip peek preview: as the horizontal drag approaches the 80dp
 * trigger, the destination track's title (with a direction chevron) reveals
 * itself from behind the sliding bar content via a counter-translated
 * parallax layer. Recomposition is scoped to THIS leaf only.
 */
@Composable
private fun SwipePeekOverlay(
    rawDragX: androidx.compose.runtime.State<Float>,
    thresholdPx: Float,
    dragOffsetX: androidx.compose.animation.core.Animatable<Float, androidx.compose.animation.core.AnimationVector1D>,
    nextTitle: String?,
    prevTitle: String?
) {
    val raw = rawDragX.value
    if (raw == 0f) return
    val towardNext = raw < 0f
    val title = (if (towardNext) nextTitle else prevTitle) ?: return
    val peek = LiquidMorphGeometry.swipePeekFraction(raw, thresholdPx)
    if (peek <= 0.01f) return
    Row(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                // Counter-translate: the preview emerges from behind the
                // bar content that slides away with the resisted drag.
                translationX = -dragOffsetX.value * 0.85f
                alpha = peek
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        if (!towardNext) {
            Icon(
                imageVector = Icons.Filled.SkipPrevious,
                contentDescription = null,
                tint = TextSecondary,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
        }
        Text(
            text = title,
            style = LocalAppTypography.current.songArtist,
            color = TextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 180.dp)
        )
        if (towardNext) {
            Spacer(modifier = Modifier.width(6.dp))
            Icon(
                imageVector = Icons.Filled.SkipNext,
                contentDescription = null,
                tint = TextSecondary,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}
