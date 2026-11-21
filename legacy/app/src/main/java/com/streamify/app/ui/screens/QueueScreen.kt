package com.streamify.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.Alignment
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.streamify.app.data.models.Track
import com.streamify.app.ui.components.ContextMenuSheet
import com.streamify.app.ui.components.LocalContextMenuController
import com.streamify.app.ui.components.MenuOrigin
import com.streamify.app.radio.UniversalCandidateBroker
import com.streamify.app.radio.OnlineRadioEngine
import com.streamify.app.ui.components.yt.YtQueueHeader
import com.streamify.app.ui.components.yt.YtQueueTrackItem
import com.streamify.app.ui.theme.*
import com.streamify.app.viewmodel.PlayerViewModel

@Composable
fun QueueScreen(
    playerViewModel: PlayerViewModel,
    onTrackClick: (Int) -> Unit,
    onClose: (() -> Unit)? = null
) {
    val playerState by playerViewModel.playerState.collectAsState()
    val radioBuilding by UniversalCandidateBroker.isFetching.collectAsState()
    val radioSummary by OnlineRadioEngine.lastBuildSummary.collectAsState()
    val smartShuffleInjected by playerViewModel.smartShuffleInjectedIds.collectAsState()
    val smartShuffleActive = smartShuffleInjected.isNotEmpty()
    val contextMenuController = LocalContextMenuController.current
    val nowPlaying = playerState.currentTrack
    val queue = playerState.queue
    val currentIndex = playerState.currentIndex
    val listState = rememberLazyListState()

    // 120fps Mathematical Drag Reorder State
    var draggedItemIndex by remember { mutableStateOf<Int?>(null) }
    var draggedItemOffset by remember { mutableStateOf(0f) }

    // HISTORY section state (overhauled played surface)
    var historyExpanded by remember { mutableStateOf(false) }
    val itemHeightPx = with(LocalDensity.current) { 56.dp.toPx() }

    // Distinct Index-Aware Queue Slices
    val playedTracks = remember(queue, currentIndex) {
        if (queue.isNotEmpty() && currentIndex > 0) {
            queue.subList(0, currentIndex.coerceAtMost(queue.size))
        } else {
            emptyList()
        }
    }

    val upNext = remember(queue, currentIndex) {
        if (queue.isNotEmpty() && currentIndex in queue.indices && currentIndex + 1 < queue.size) {
            queue.subList(currentIndex + 1, queue.size)
        } else {
            emptyList()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BgBase)
            .statusBarsPadding()
    ) {
        // 1. YouTube Music Queue Header
        YtQueueHeader(
            sourceName = if (nowPlaying != null) "${nowPlaying.artist} Radio" else "Library Queue",
            isAutoplayEnabled = playerState.isAutoPlayEnabled,
            onToggleAutoplay = { playerViewModel.toggleAutoPlay() },
            onClearQueue = { playerViewModel.clearQueue() },
            onClose = { onClose?.invoke() ?: run { /* Pop backstack */ } },
            hasQueueItems = upNext.isNotEmpty() || playedTracks.isNotEmpty()
        )

        // Radio build telemetry (OnlineRadioEngine)
        if (radioBuilding || radioSummary != "Idle") {
            androidx.compose.material3.Surface(
                color = if (radioBuilding) StreamifyColors.Primary.copy(alpha = 0.12f)
                        else StreamifyColors.BgSurfaceElevated,
                shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
                modifier = Modifier
                    .padding(horizontal = 16.dp, vertical = 6.dp)
                    .fillMaxWidth()
            ) {
                Text(
                    text = if (radioBuilding) "◌ Building online radio…" else "◉ $radioSummary",
                    style = LocalAppTypography.current.songArtist.copy(
                        fontSize = 10.sp, letterSpacing = 0.8.sp,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                    ),
                    color = if (radioBuilding) StreamifyColors.Primary else StreamifyColors.TextSecondary,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp)
                )
            }
        }

        // ── Phase 3: Smart Shuffle — interleave recommendations w/ badges ──
        androidx.compose.material3.Surface(
            color = if (smartShuffleActive) StreamifyColors.Primary.copy(alpha = 0.12f)
                    else StreamifyColors.BgSurfaceElevated,
            shape = androidx.compose.foundation.shape.RoundedCornerShape(22.dp),
            border = androidx.compose.foundation.BorderStroke(
                width = 1.dp,
                color = if (smartShuffleActive) StreamifyColors.Primary.copy(alpha = 0.5f)
                        else StreamifyColors.BorderChip
            ),
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 4.dp)
                .fillMaxWidth()
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.Shuffle,
                    contentDescription = null,
                    tint = if (smartShuffleActive) StreamifyColors.Primary else StreamifyColors.TextSecondary,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Smart Shuffle",
                        style = LocalAppTypography.current.songTitle.copy(
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        ),
                        color = if (smartShuffleActive) StreamifyColors.Primary else StreamifyColors.TextMain
                    )
                    if (smartShuffleActive) {
                        Text(
                            text = "${smartShuffleInjected.size} recommended picks woven in",
                            style = LocalAppTypography.current.songArtist.copy(fontSize = 10.sp),
                            color = StreamifyColors.TextSecondary
                        )
                    } else {
                        Text(
                            text = "Weave recommendations into this queue",
                            style = LocalAppTypography.current.songArtist.copy(fontSize = 10.sp),
                            color = StreamifyColors.TextSecondary
                        )
                    }
                }
                if (smartShuffleActive) {
                    // Reshuffle trigger — fresh batch, old picks stripped.
                    androidx.compose.material3.TextButton(
                        onClick = { playerViewModel.reshuffleSmartShuffle() },
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp)
                    ) {
                        Text(
                            text = "Reshuffle",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = StreamifyColors.Primary
                        )
                    }
                }
                androidx.compose.material3.Switch(
                    checked = smartShuffleActive,
                    onCheckedChange = { playerViewModel.toggleSmartShuffle() },
                    colors = androidx.compose.material3.SwitchDefaults.colors(
                        checkedTrackColor = StreamifyColors.Primary,
                        checkedThumbColor = androidx.compose.ui.graphics.Color.White
                    ),
                    modifier = Modifier.size(width = 44.dp, height = 26.dp)
                )
            }
        }

        // 2. Queue LazyColumn
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 120.dp) // Protective padding for docked player
        ) {
            // Section 0 · HISTORY — overhauled played surface:
            // most-recent-first, position badges, dimmed artwork, replay
            // affordance, collapse/expand, and one-tap clear.
            if (playedTracks.isNotEmpty()) {
                val history = playedTracks.asReversed()
                val visibleHistory =
                    if (historyExpanded || history.size <= 3) history
                    else history.take(3)

                item(key = "header_history", contentType = "sectionHeader") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "HISTORY (${playedTracks.size})",
                            style = LocalAppTypography.current.songArtist.copy(
                                fontSize = 11.sp,
                                letterSpacing = 0.5.sp,
                                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                            ),
                            color = TextTertiary
                        )
                        Spacer(modifier = Modifier.weight(1f))
                        IconButton(
                            onClick = {
                                com.streamify.app.util.StreamifyHapticEngine.magneticDetent()
                                playerViewModel.clearPlayedHistory()
                            },
                            modifier = Modifier.size(30.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.DeleteSweep,
                                contentDescription = "Clear history",
                                tint = TextTertiary.copy(alpha = 0.8f),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        IconButton(
                            onClick = { historyExpanded = !historyExpanded },
                            modifier = Modifier.size(30.dp)
                        ) {
                            Icon(
                                imageVector = if (historyExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                                contentDescription = if (historyExpanded) "Collapse" else "Expand",
                                tint = TextTertiary.copy(alpha = 0.8f),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }

                itemsIndexed(
                    items = visibleHistory,
                    key = { i, track -> "played_${i}_${track.id}" },
                    contentType = { _, _ -> "trackRow" }
                ) { index, track ->
                    val originalPosition = playedTracks.size - index   // absolute queue slot (#1..)
                    YtQueueTrackItem(
                        track = track,
                        isPlaying = false,
                        dragOffset = 0f,
                        showDragHandle = false,
                        leadingBadge = "#%02d".format(originalPosition),
                        dimmed = true,
                        onReplay = null,   // row tap already replays; keep rows uncluttered
                        onClick = {
                            onTrackClick(track.id)
                            playerViewModel.playTrack(track, queue)
                        },
                        onMoreClick = { contextMenuController.show(track, origin = MenuOrigin.QUEUE) }
                    )
                }

                if (history.size > 3 && !historyExpanded) {
                    item(key = "history_show_all", contentType = "textAction") {
                        Text(
                            text = "SHOW ${history.size - 3} EARLIER",
                            style = LocalAppTypography.current.songArtist.copy(
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.8.sp
                            ),
                            color = Primary,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { historyExpanded = true }
                                .padding(horizontal = 16.dp, vertical = 10.dp)
                        )
                    }
                }
            }

                        // Section A: Now Playing Active Track
            if (nowPlaying != null) {
                item(key = "header_now_playing", contentType = "sectionHeader") {
                    Text(
                        text = "NOW PLAYING",
                        style = LocalAppTypography.current.songArtist.copy(
                            fontSize = 11.sp,
                            letterSpacing = 0.5.sp
                        ),
                        color = TextTertiary,
                        modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 6.dp)
                    )
                }

                item(key = "active_${nowPlaying.id}_${nowPlaying.filepath.hashCode()}", contentType = "trackRow") {
                    YtQueueTrackItem(
                        track = nowPlaying,
                        isPlaying = true,
                        isActuallyPlaying = playerState.isPlaying,
                        dragOffset = 0f,
                        showDragHandle = false,
                        onClick = { /* Already playing */ },
                        onMoreClick = { contextMenuController.show(nowPlaying, origin = MenuOrigin.QUEUE) }
                    )
                }
            }

            // Section B: Up Next Queue Items (Guaranteed zero played song repetition)
            if (upNext.isNotEmpty()) {
                item(key = "header_up_next", contentType = "sectionHeader") {
                    Text(
                        text = "UP NEXT (${upNext.size})",
                        style = LocalAppTypography.current.songArtist.copy(
                            fontSize = 11.sp,
                            letterSpacing = 0.5.sp
                        ),
                        color = TextTertiary,
                        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 6.dp)
                    )
                }

                itemsIndexed(
                    items = upNext,
                    key = { i, track -> "queue_${i}_${track.id}" },
                    contentType = { _, _ -> "trackRow" }
                ) { index, track ->
                    val isBeingDragged = draggedItemIndex == index
                    val dragOffset = if (isBeingDragged) draggedItemOffset else 0f
                    // Phase 3 — distinct badge for Smart Shuffle injections.
                    val isSmartPick = track.id in smartShuffleInjected

                    YtQueueTrackItem(
                        track = track,
                        isPlaying = false,
                        dragOffset = dragOffset,
                        showDragHandle = true,
                        leadingBadge = if (isSmartPick) "✦ REC" else null,
                        onDragStart = {
                            draggedItemIndex = index
                            draggedItemOffset = 0f
                            // Phase 5 — firm grab click on long-press pickup.
                            com.streamify.app.ui.util.HapticFeedbackManager.get()?.queueGrab()
                                ?: com.streamify.app.util.StreamifyHapticEngine.queueGrab()
                        },
                        onDragMove = { deltaY ->
                            draggedItemOffset += deltaY
                            val targetIndex = (index + (draggedItemOffset / itemHeightPx).toInt())
                                .coerceIn(0, upNext.size - 1)
                            if (targetIndex != index) {
                                val absFrom = currentIndex + 1 + index
                                val absTo = currentIndex + 1 + targetIndex
                                playerViewModel.reorderQueue(absFrom, absTo)
                                draggedItemIndex = targetIndex
                                draggedItemOffset = 0f
                                // Phase 5 — subtle tick across index boundaries.
                                com.streamify.app.ui.util.HapticFeedbackManager.get()?.queueBoundaryTick()
                                    ?: com.streamify.app.util.StreamifyHapticEngine.magneticDetent()
                            }
                        },
                        onDragEnd = {
                            draggedItemIndex = null
                            draggedItemOffset = 0f
                            // Phase 5 — soft drop thud on release.
                            com.streamify.app.ui.util.HapticFeedbackManager.get()?.queueDrop()
                        },
                        onClick = {
                            onTrackClick(track.id)
                            playerViewModel.playTrack(track, queue)
                        },
                        onMoreClick = { contextMenuController.show(track, origin = MenuOrigin.QUEUE) }
                    )
                }
            } else if (nowPlaying == null) {
                item(key = "empty_queue", contentType = "emptyState") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 80.dp),
                        contentAlignment = androidx.compose.ui.Alignment.Center
                    ) {
                        Text(
                            text = "No songs in queue",
                            style = LocalAppTypography.current.songArtist,
                            color = TextSecondary
                        )
                    }
                }
            }
        }
    }
}
