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
import com.streamify.app.ui.theme.*
import com.streamify.app.viewmodel.CommunityViewModel
import com.streamify.app.viewmodel.UiEvent
import com.streamify.app.viewmodel.UiEventBus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File
import com.streamify.app.util.SLog

@Composable
internal fun LandscapeQueuePane(
    queue: List<Track>,
    currentIndex: Int,
    currentTrack: Track?,
    isPlaying: Boolean,
    onTrackClick: (Track) -> Unit
) {
    val listState = rememberLazyListState()

    val playedHistory = remember(queue, currentIndex) {
        if (currentIndex > 0 && queue.isNotEmpty()) {
            queue.subList(0, currentIndex.coerceAtMost(queue.size))
        } else {
            emptyList()
        }
    }

    val upNext = remember(queue, currentIndex) {
        if (currentIndex >= 0 && currentIndex + 1 < queue.size) {
            queue.subList(currentIndex + 1, queue.size)
        } else {
            emptyList()
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 4.dp)
    ) {
        // 1. PLAYED (History)
        if (playedHistory.isNotEmpty()) {
            item(key = "hdr_history") {
                Text(
                    text = "HISTORY (${playedHistory.size})",
                    style = LocalAppTypography.current.songArtist.copy(
                        fontSize = 11.sp,
                        letterSpacing = 0.5.sp,
                        fontWeight = FontWeight.Bold
                    ),
                    color = TextTertiary.copy(alpha = 0.6f),
                    modifier = Modifier.padding(start = 8.dp, bottom = 4.dp)
                )
            }

            itemsIndexed(
                items = playedHistory,
                key = { i, t -> "hist_${i}_${t.id}" }
            ) { _, itemTrack ->
                YtQueueTrackItem(
                    track = itemTrack,
                    isPlaying = false,
                    dragOffset = 0f,
                    showDragHandle = false,
                    modifier = Modifier.graphicsLayer { alpha = 0.55f },
                    onClick = { onTrackClick(itemTrack) },
                    onMoreClick = { /* Options */ }
                )
            }

            item(key = "sp_divider_hist") {
                Spacer(modifier = Modifier.height(10.dp))
            }
        }

        // 2. NOW PLAYING
        if (currentTrack != null) {
            item(key = "hdr_playing") {
                Text(
                    text = "NOW PLAYING",
                    style = LocalAppTypography.current.songArtist.copy(
                        fontSize = 11.sp,
                        letterSpacing = 0.5.sp,
                        fontWeight = FontWeight.Bold
                    ),
                    color = ActiveControl,
                    modifier = Modifier.padding(start = 8.dp, bottom = 4.dp)
                )
            }

            item(key = "active_${currentTrack.id}_${currentTrack.filepath.hashCode()}") {
                YtQueueTrackItem(
                    track = currentTrack,
                    isPlaying = true,
                    isActuallyPlaying = isPlaying,
                    dragOffset = 0f,
                    showDragHandle = false,
                    onClick = { /* Already playing */ },
                    onMoreClick = { /* Options */ }
                )
            }

            item(key = "sp_divider") {
                Spacer(modifier = Modifier.height(10.dp))
            }
        }

        // 3. UP NEXT (Strictly upcoming unplayed tracks)
        if (upNext.isNotEmpty()) {
            item(key = "hdr_upnext") {
                Text(
                    text = "UP NEXT (${upNext.size})",
                    style = LocalAppTypography.current.songArtist.copy(
                        fontSize = 11.sp,
                        letterSpacing = 0.5.sp,
                        fontWeight = FontWeight.Bold
                    ),
                    color = TextTertiary,
                    modifier = Modifier.padding(start = 8.dp, top = 6.dp, bottom = 4.dp)
                )
            }

            itemsIndexed(
                items = upNext,
                key = { i, t -> "upnext_${i}_${t.id}" }
            ) { _, itemTrack ->
                YtQueueTrackItem(
                    track = itemTrack,
                    isPlaying = false,
                    dragOffset = 0f,
                    showDragHandle = true,
                    onClick = { onTrackClick(itemTrack) },
                    onMoreClick = { /* Options */ }
                )
            }
        } else {
            item(key = "empty_q") {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 40.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "End of queue. Auto-play will discover new tracks.",
                        style = LocalAppTypography.current.bodyMedium,
                        color = TextSecondary,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

@Composable
internal fun LandscapeLyricsPane(
    track: Track,
    positionFlow: StateFlow<Long>,
    isPlaying: Boolean = true,
    onSeek: (Long) -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    var lyricsLines by remember(track.id) { mutableStateOf<List<LyricsLine>>(emptyList()) }
    var isLoading by remember(track.id) { mutableStateOf(true) }
    val lyricController = remember { LyricPlaybackController() }
    val context = androidx.compose.ui.platform.LocalContext.current

    // L2: shared persisted offset (same key as LyricsScreen route)
    LaunchedEffect(track.id, track.title, track.artist) {
        lyricController.bindTrack(LyricOffsetStore.keyOfTrack(track))
    }

    // Cache-only load keyed on lyricsPath too: when PlayerViewModel (the single fetch
    // owner) lands verified lyrics, this effect re-fires and hydrates them instantly.
    LaunchedEffect(track.id, track.lyricsPath) {
        isLoading = true
        withContext(Dispatchers.IO) {
            val loadedLines = com.streamify.app.data.lyrics.LyricsCacheManager.getOrFetchLyrics(context, track, allowNetwork = false)
            withContext(Dispatchers.Main) {
                lyricsLines = loadedLines
                isLoading = false
            }
        }
    }

    val isSynced = remember(lyricsLines) {
        lyricsLines.isNotEmpty() && lyricsLines.any { it.timeMs > 0L }
    }

    val handleSaveOffset: () -> Unit = {
        if (lyricsLines.isNotEmpty() && lyricController.userOffsetMs != 0L) {
            val offset = lyricController.userOffsetMs
            val shiftedLines = LyricsData.shiftTimestamps(lyricsLines, offset)
            val adjustedLrc = LyricsData.formatLrc(lyricsLines, offset)

            // 1. Instant in-memory shift
            lyricsLines = shiftedLines
            lyricController.resetOffset()

            // 2. Persist to Disk LRU, Companion LRC, SQLite DB & Supabase Community
            coroutineScope.launch(Dispatchers.IO) {
                try {
                    com.streamify.app.data.lyrics.LyricsCacheManager.saveLyricsToDiskAndDb(context, track, adjustedLrc)

                    // Submit to Community Supabase
                    try {
                        val cleanSig = (track.title.trim().lowercase() + "_" + track.artist.trim().lowercase())
                        val cloudId = "trk_${kotlin.math.abs(cleanSig.hashCode())}"
                        com.streamify.app.data.supabase.SupabaseClient.submitSyncedLyrics(cloudId, adjustedLrc)
                    } catch (e: Exception) {
                        // Non-fatal
                    }

                    withContext(Dispatchers.Main) {
                        android.widget.Toast.makeText(
                            context,
                            "❤️ Thank you for syncing! Lyrics timing saved & synced.",
                            android.widget.Toast.LENGTH_LONG
                        ).show()
                    }
                } catch (e: Exception) {
                    SLog.st("FullPlayerSheet", "LandscapeLyricsPane save-synced-lyrics failed", e)
                }
            }
        }
    }


    Column(modifier = Modifier.fillMaxSize()) {
        YtLyricsHeader(
            source = "Musixmatch / LRCLIB",
            isSynced = isSynced,
            userOffsetMs = lyricController.userOffsetMs,
            onAdjustOffset = { delta -> lyricController.adjustOffset(delta) },
            onResetOffset = { lyricController.resetOffset() },
            onSaveOffset = handleSaveOffset,
            onClose = null
        )

        if (isLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = ActiveControl, modifier = Modifier.size(32.dp))
            }
        } else if (lyricsLines.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "No synchronized lyrics found for this track.",
                    style = LocalAppTypography.current.bodyMedium,
                    color = TextSecondary,
                    textAlign = TextAlign.Center
                )
            }
        } else {
            val listState = rememberLazyListState()

            // Seed the lyric clock from the hot flow WITHOUT restarting this
            // effect (or recomposing) on every tick.
            LaunchedEffect(positionFlow, isPlaying) {
                lyricController.isPlaying = isPlaying
                positionFlow.collect { pos ->
                    lyricController.targetPositionMs = pos
                }
            }

            LaunchedEffect(Unit) {
                lyricController.runFrameLoop()
            }

            // derivedStateOf: recomputes the scan every frame tick but only
            // emits (and thus recomposes) when the ACTIVE LINE actually flips.
            val activeIndex by remember(lyricsLines, isSynced) {
                derivedStateOf {
                    if (!isSynced) -1
                    else {
                        val idx = lyricsLines.indexOfLast { it.timeMs <= lyricController.interpolatedPosMs }
                        if (idx >= 0) idx else 0
                    }
                }
            }

            LaunchedEffect(activeIndex, isSynced) {
                if (isSynced && lyricsLines.isNotEmpty() && activeIndex in lyricsLines.indices && !listState.isScrollInProgress) {
                    val viewportHeight = listState.layoutInfo.viewportSize.height
                    if (viewportHeight > 0) {
                        val focalOffset = viewportHeight * 0.35f
                        val itemInfo = listState.layoutInfo.visibleItemsInfo.find { it.index == activeIndex }
                        if (itemInfo != null) {
                            listState.animateScrollBy(
                                value = itemInfo.offset - focalOffset,
                                animationSpec = spring(
                                    dampingRatio = Spring.DampingRatioLowBouncy,
                                    stiffness = Spring.StiffnessMediumLow
                                )
                            )
                        } else {
                            listState.animateScrollToItem(
                                index = activeIndex,
                                scrollOffset = (-focalOffset).toInt()
                            )
                        }
                    }
                }
            }

            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = 16.dp)
            ) {
                if (!isSynced) {
                    items(lyricsLines.size) { index ->
                        val line = lyricsLines[index]
                        Text(
                            text = line.text,
                            style = LocalAppTypography.current.headlineSmall.copy(
                                fontSize = 17.sp,
                                fontWeight = FontWeight.Medium
                            ),
                            color = TextMain.copy(alpha = 0.90f),
                            modifier = Modifier.padding(vertical = 6.dp, horizontal = 8.dp)
                        )
                    }
                } else {
                    items(lyricsLines.size) { index ->
                        val line = lyricsLines[index]
                        val nextLineTime = if (index + 1 < lyricsLines.size) lyricsLines[index + 1].timeMs else line.timeMs + 3500L
                        val isActive = index == activeIndex
                        val isPast = index < activeIndex

                        com.streamify.app.ui.components.FluidSyllableText(
                            text = line.text,
                            lineStartMs = line.timeMs,
                            lineEndMs = nextLineTime,
                            // Playhead supplied as a provider: read only inside
                            // the draw phase, so lyric rows never recompose per frame.
                            playbackMsProvider = { lyricController.interpolatedPosMs },
                            isActive = isActive,
                            isPast = isPast,
                            onClick = { onSeek(line.timeMs) }
                        )
                    }
                }
            }
        }
    }
}


@Composable
internal fun LandscapeRelatedPane(
    track: Track,
    playerViewModel: com.streamify.app.viewmodel.PlayerViewModel,
    onTrackClick: (Track) -> Unit
) {
    var relatedList by remember(track.id) { mutableStateOf<List<Track>>(emptyList()) }
    var isLoading by remember(track.id) { mutableStateOf(true) }

    LaunchedEffect(track.id) {
        isLoading = true
        withContext(Dispatchers.IO) {
            val radio = try {
                com.streamify.app.radio.UniversalCandidateBroker.fetchCandidates(track, targetCount = 20)
            } catch (e: Exception) {
                emptyList()
            }
            withContext(Dispatchers.Main) {
                relatedList = radio.filter { it.id != track.id }
                isLoading = false
            }
        }
    }

    if (isLoading) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = ActiveControl, modifier = Modifier.size(32.dp))
        }
    } else if (relatedList.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = "No related tracks discovered yet.",
                style = LocalAppTypography.current.bodyMedium,
                color = TextSecondary,
                textAlign = TextAlign.Center
            )
        }
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = 4.dp)
        ) {
            items(relatedList, key = { it.id }) { itemTrack ->
                YtQueueTrackItem(
                    track = itemTrack,
                    isPlaying = false,
                    dragOffset = 0f,
                    showDragHandle = false,
                    onClick = { onTrackClick(itemTrack) },
                    onMoreClick = { /* Options */ }
                )
            }
        }
    }
}

