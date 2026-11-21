package com.streamify.app.ui.components

import android.view.ViewGroup
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.MusicOff
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.streamify.app.data.network.CanvasScraperApi
import com.streamify.app.player.CanvasLoopPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * CanvasVideoSurface — looping Canvas layer + toggle (Phase 3, deliverable 2)
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * Compose surface for the Spotify-style Canvas experience:
 *
 *  • [CanvasBackdrop] — full-bleed looping video layer that sits BEHIND the
 *    player furniture (artwork, metadata, controls) with ambient glow
 *    blending: the dominant album color is screened over the video with a
 *    radial gradient so the loop always feels color-anchored to the track,
 *    and the sheet's BgBase is faded in at the bottom so controls stay
 *    legible over bright loops.
 *  • [CanvasToggleChip] — the audio-only ↔ Canvas toggle switch, shown only
 *    when a Canvas actually exists for the track (never a dead control).
 *  • [rememberCanvasLoop] — the state holder that resolves the loop for a
 *    track via CanvasScraperApi (SWR-cached, off the main thread, never
 *    throwing).
 *
 * Zero-jank discipline: the ExoPlayer is only constructed when the user
 * enables Canvas; recomposition cannot allocate players. The AndroidView
 * attaches exactly once per enabled session.
 */

/** Resolved Canvas state for the current track. */
sealed class CanvasLoopState {
    object None : CanvasLoopState()                 // no canvas exists
    object Loading : CanvasLoopState()              // resolving
    data class Ready(val loop: CanvasScraperApi.CanvasLoop) : CanvasLoopState()
}

/**
 * Resolves the Canvas loop for a track. Resolution order:
 * ytmVideoId → direct canvas; else title/artist query → search → canvas.
 * Runs on Dispatchers.IO; result is null-safe and never throws.
 */
@Composable
fun rememberCanvasLoop(
    videoId: String?,
    trackTitle: String,
    trackArtist: String
): CanvasLoopState {
    var state by remember(videoId, trackTitle, trackArtist) {
        mutableStateOf<CanvasLoopState>(CanvasLoopState.Loading)
    }
    LaunchedEffect(videoId, trackTitle, trackArtist) {
        state = CanvasLoopState.Loading
        val loop = withContext(Dispatchers.IO) {
            runCatching {
                val direct = videoId?.takeIf { it.isNotBlank() }?.let {
                    CanvasScraperApi.canvasFor(it)
                }
                direct ?: run {
                    val query = "$trackTitle $trackArtist".trim().takeIf { it.isNotBlank() }
                        ?: return@runCatching null
                    CanvasScraperApi.canvasForQuery(query)
                }
            }.getOrNull()
        }
        state = loop?.let { CanvasLoopState.Ready(it) } ?: CanvasLoopState.None
    }
    return state
}

/**
 * Owns the [CanvasLoopPlayer] lifecycle: built only when [enabled], released
 * on dispose or when Canvas is toggled off. Synchronous remember — no
 * recomposition race between player creation and the first setLoop.
 */
@Composable
fun rememberCanvasLoopPlayer(enabled: Boolean): CanvasLoopPlayer? {
    val context = LocalContext.current
    val player = remember(enabled) {
        if (enabled) CanvasLoopPlayer(context) else null
    }
    DisposableEffect(player) {
        onDispose { player?.release() }
    }
    return player
}

/**
 * Full-bleed looping Canvas layer with ambient glow blending. Place directly
 * behind player furniture (after the base radial glow, before content).
 *
 * @param loop the resolved Canvas loop (Ready state)
 * @param enabled user toggle — false renders nothing
 * @param dominantColor the track's dominant art color for glow blending
 */
@Composable
fun CanvasBackdrop(
    loop: CanvasScraperApi.CanvasLoop,
    enabled: Boolean,
    dominantColor: Color,
    modifier: Modifier = Modifier
) {
    if (!enabled) return
    val context = LocalContext.current
    val canvasPlayer = rememberCanvasLoopPlayer(enabled)

    // Feed the loop URL in idempotently — recomposition storms are free.
    LaunchedEffect(loop.loopUrl) {
        canvasPlayer?.setLoop(loop)
    }
    // Pause when the sheet is not visible… caller controls visibility by
    // simply not composing this layer; nothing extra to do here.

    val videoAlpha by animateFloatAsState(
        targetValue = if (enabled) 0.85f else 0f,
        animationSpec = tween(700),
        label = "canvasVideoAlpha"
    )

    Box(modifier = modifier) {
        canvasPlayer?.exoPlayer?.let { exo ->
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        useController = false
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                        setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                    }
                },
                update = { view ->
                    if (view.player !== exo) view.player = exo
                },
                modifier = Modifier
                    .matchParentSize()
                    .alpha(videoAlpha)
            )
        }

        // ── Ambient glow blending ────────────────────────────────────────────
        // Screen the dominant art color over the loop so the video feels
        // color-anchored to the track, then fade to BgBase at the bottom so
        // the transport controls keep contrast over bright loops.
        val glowColor = loop.paletteColor?.let { argb ->
            runCatching { Color(argb.toInt()) }.getOrNull()
        } ?: dominantColor
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            glowColor.copy(alpha = 0.18f),
                            Color.Transparent,
                            Color.Transparent,
                            Color(0xFF0A0A0F).copy(alpha = 0.72f)
                        )
                    )
                )
        )
    }
}

/**
 * Audio-only ↔ Canvas toggle chip. Only rendered when [visible] (a Canvas
 * exists for the track) — never a dead control.
 */
@Composable
fun CanvasToggleChip(
    enabled: Boolean,
    visible: Boolean,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(animationSpec = tween(250)),
        exit = fadeOut(animationSpec = tween(250))
    ) {
        Surface(
            onClick = { onToggle(!enabled) },
            shape = RoundedCornerShape(18.dp),
            color = if (enabled) androidx.compose.ui.graphics.Color(0xFF1ED760).copy(alpha = 0.16f)
            else Color(0xFF16161E),
            border = androidx.compose.foundation.BorderStroke(
                width = 1.dp,
                color = if (enabled) androidx.compose.ui.graphics.Color(0xFF1ED760).copy(alpha = 0.55f)
                else Color(0xFF2C2C38)
            ),
            modifier = modifier
        ) {
            androidx.compose.foundation.layout.Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
            ) {
                Icon(
                    imageVector = if (enabled) Icons.Filled.MusicNote else Icons.Filled.MusicOff,
                    contentDescription = if (enabled) "Canvas on" else "Canvas off",
                    tint = if (enabled) androidx.compose.ui.graphics.Color(0xFF1ED760) else Color(0xFF9A9AAE),
                    modifier = Modifier.size(13.dp)
                )
                androidx.compose.foundation.layout.Spacer(modifier = Modifier.size(5.dp))
                Text(
                    text = "Canvas",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (enabled) androidx.compose.ui.graphics.Color(0xFF1ED760) else Color(0xFF9A9AAE)
                )
            }
        }
    }
}
