package com.streamify.app.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import com.streamify.app.data.network.ArtistClipsApi
import com.streamify.app.data.network.NetworkEngine
import com.streamify.app.data.network.YouTubeStreamResolver
import com.streamify.app.util.SLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * VerticalClipsRail — 30s vertical clip discovery feed (Phase 3, deliverable 2)
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * Two surfaces:
 *
 *  • [VerticalClipsRail] — a horizontal rail of 9:16 clip cards on
 *    Artist/Album screens. Cards show the clip thumbnail, 30s duration
 *    badge and view count; tapping opens the immersive feed.
 *
 *  • [VerticalClipsFeedSheet] — a full-bleed vertical pager (TikTok-style
 *    swipe) where each page is a hardware-accelerated Media3 surface
 *    playing that clip's video WITH its own audio, looped. Audio sync:
 *    the main music session must pause while the feed is open — pass
 *    [onFeedVisibilityChanged] and wire it to PlayerViewModel pause/resume.
 *
 * Zero-jank discipline: ONE ExoPlayer instance serves the whole feed; the
 * current page's clip is loaded into it on page settle, so swiping never
 * stacks decoders. The rail itself renders pure AsyncImages — no video
 * decoders until the feed opens.
 */

/** Resolved clips state for a rail host (artist/album screen). */
sealed class ClipsState {
    object Loading : ClipsState()
    data class Ready(val page: ArtistClipsApi.ClipsPage) : ClipsState()
    object None : ClipsState()
}

/** Resolves the clips rail for an artist (IO dispatcher, never throws). */
@Composable
fun rememberArtistClips(artistName: String): ClipsState {
    var state by remember(artistName) { mutableStateOf<ClipsState>(ClipsState.Loading) }
    LaunchedEffect(artistName) {
        state = ClipsState.Loading
        val page = withContext(Dispatchers.IO) {
            runCatching { ArtistClipsApi.clipsForArtist(artistName) }.getOrNull()
        }
        state = when {
            page != null && page.clips.isNotEmpty() -> ClipsState.Ready(page)
            else -> ClipsState.None
        }
    }
    return state
}

/**
 * Horizontal rail of vertical clip cards. Hidden entirely when the artist
 * has no clips (never an empty header).
 */
@Composable
fun VerticalClipsRail(
    clipsState: ClipsState,
    onOpenFeed: (ArtistClipsApi.ClipsPage) -> Unit,
    modifier: Modifier = Modifier
) {
    val page = (clipsState as? ClipsState.Ready)?.page ?: return
    if (page.clips.isEmpty()) return

    Column(modifier = modifier.fillMaxWidth()) {
        // Section header
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.PlayArrow,
                contentDescription = null,
                tint = Color(0xFF1ED760),
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "Clips",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFFFFFFFF)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "${page.clips.size} verticals",
                fontSize = 12.sp,
                color = Color(0xFF9A9AAE)
            )
        }

        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(page.clips, key = { it.clipId }) { clip ->
                VerticalClipCard(
                    clip = clip,
                    onClick = { onOpenFeed(page) }
                )
            }
        }
    }
}

/** One 9:16 clip card in the rail. */
@Composable
private fun VerticalClipCard(
    clip: ArtistClipsApi.ArtistClip,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .width(118.dp)
            .aspectRatio(9f / 16f)
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF16161E))
            .clickable(onClick = onClick)
    ) {
        AsyncImage(
            model = clip.thumbnailUrl,
            contentDescription = clip.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )

        // Bottom scrim + metadata
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color.Transparent, Color(0xCC0A0A0F))
                    )
                )
                .padding(horizontal = 8.dp, vertical = 8.dp)
        ) {
            Column {
                Text(
                    text = clip.title,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = clip.viewCountText,
                    fontSize = 9.sp,
                    color = Color(0xB3FFFFFF),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        // 30s duration badge
        Surface(
            color = Color(0x99000000),
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(6.dp)
        ) {
            Text(
                text = "${clip.durationSec}s",
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
            )
        }

        // Center play affordance
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .size(30.dp)
                .background(Color(0x66000000), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Filled.PlayArrow,
                contentDescription = "Play clip",
                tint = Color.White,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

/**
 * Full-bleed vertical clips feed. One shared ExoPlayer loads the settled
 * page's clip; the main music session pauses/resumes via
 * [onFeedVisibilityChanged].
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun VerticalClipsFeedSheet(
    clips: List<ArtistClipsApi.ArtistClip>,
    onDismiss: () -> Unit,
    onFeedVisibilityChanged: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier
) {
    if (clips.isEmpty()) return // hosts guard this, but never open an empty feed

    val context = LocalContext.current
    val pagerState = rememberPagerState(initialPage = 0) { clips.size }

    // ONE decoder for the whole feed — swapped per settled page.
    val clipPlayer = remember {
        runCatching {
            val httpFactory = OkHttpDataSource.Factory(NetworkEngine.exoPlayerClient)
            val dataSourceFactory = DefaultDataSource.Factory(context.applicationContext, httpFactory)
            val mediaSourceFactory = androidx.media3.exoplayer.source.DefaultMediaSourceFactory(
                context.applicationContext
            ).setDataSourceFactory(dataSourceFactory)
            ExoPlayer.Builder(context.applicationContext)
                .setMediaSourceFactory(mediaSourceFactory)
                .build()
        }.getOrNull()
    }
    DisposableEffect(Unit) {
        onDispose {
            runCatching {
                clipPlayer?.stop()
                clipPlayer?.release()
            }
        }
    }

    // Audio sync: main session pauses while the feed is open, resumes on exit.
    DisposableEffect(Unit) {
        onFeedVisibilityChanged(true)
        onDispose { onFeedVisibilityChanged(false) }
    }

    // Load the settled page's clip (settled → no mid-swap decoder churn).
    // Resolution: watch URLs are not directly playable — resolve the actual
    // CDN stream first via the existing YouTubeStreamResolver (cached there).
    val settledPage by remember {
        derivedStateOf {
            if (pagerState.isScrollInProgress) null else pagerState.currentPage
        }
    }
    LaunchedEffect(settledPage) {
        val pageIndex = settledPage ?: return@LaunchedEffect
        val clip = clips.getOrNull(pageIndex) ?: return@LaunchedEffect
        val player = clipPlayer ?: return@LaunchedEffect
        val resolved = withContext(Dispatchers.IO) {
            runCatching { YouTubeStreamResolver.resolveStreamUrl(clip.videoId) }.getOrNull()
        } ?: return@LaunchedEffect
        // User already swiped on — drop the stale load instead of yanking the
        // decoder out from under the newly-settled page.
        if (pagerState.currentPage != pageIndex || pagerState.isScrollInProgress) {
            return@LaunchedEffect
        }
        runCatching {
            player.setMediaItem(MediaItem.fromUri(resolved.streamUrl))
            player.repeatMode = Player.REPEAT_MODE_ONE
            player.playWhenReady = true
            player.prepare()
        }.onFailure {
            SLog.d("ClipsFeed", "clip load failed (${it.message})")
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            VerticalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize()
            ) { pageIndex ->
                val clip = clips[pageIndex]
                val isActive = pageIndex == pagerState.currentPage
                Box(modifier = Modifier.fillMaxSize()) {
                    if (isActive) {
                        clipPlayer?.let { exo ->
                            AndroidView(
                                factory = { ctx ->
                                    PlayerView(ctx).apply {
                                        useController = false
                                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                                        setShutterBackgroundColor(android.graphics.Color.BLACK)
                                    }
                                },
                                update = { view ->
                                    if (view.player !== exo) view.player = exo
                                },
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    } else {
                        // Off-page placeholder keeps scroll geometry stable.
                        AsyncImage(
                            model = clip.thumbnailUrl,
                            contentDescription = clip.title,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    }

                    // Bottom metadata scrim
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .align(Alignment.BottomCenter)
                            .background(
                                Brush.verticalGradient(
                                    colors = listOf(Color.Transparent, Color(0xE60A0A0F))
                                )
                            )
                            .padding(bottom = 120.dp, start = 20.dp, end = 20.dp)
                    ) {
                        Column {
                            Text(
                                text = clip.title,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                                                       )
                            if (clip.viewCountText.isNotBlank()) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = clip.viewCountText,
                                    fontSize = 12.sp,
                                    color = Color(0xB3FFFFFF)
                                )
                            }
                        }
                    }

                    // Audio-sync affordance chip
                    Surface(
                        color = Color(0x66000000),
                        shape = RoundedCornerShape(20.dp),
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(bottom = 108.dp, end = 16.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.MusicNote,
                                contentDescription = null,
                                tint = Color(0xFF1ED760),
                                modifier = Modifier.size(12.dp)
                            )
                            Spacer(modifier = Modifier.width(5.dp))
                            Text(
                                text = "Clip audio",
                                fontSize = 10.sp,
                                color = Color(0xB3FFFFFF)
                            )
                        }
                    }
                }
            }

            // Close button
            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 42.dp, end = 16.dp)
                    .size(38.dp)
                    .background(Color(0x66000000), CircleShape)
            ) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "Close clips",
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
            }

            // Page dots
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 72.dp)
            ) {
                clips.take(12).forEachIndexed { index, _ ->
                    val active by animateFloatAsState(
                        targetValue = if (index == pagerState.currentPage) 1f else 0.3f,
                        animationSpec = tween(200),
                        label = "clipDot"
                    )
                    Box(
                        modifier = Modifier
                            .size(width = 16.dp, height = 4.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = active))
                    )
                }
            }
        }
    }
}
