package com.streamify.app.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.streamify.app.data.models.Track
import com.streamify.app.ui.components.LocalContextMenuController
import com.streamify.app.ui.components.MenuOrigin
import com.streamify.app.ui.components.SwipeableTrackListItem
import com.streamify.app.ui.components.TrackCoverArt
import com.streamify.app.ui.theme.StreamifyColors
import com.streamify.app.ui.theme.StreamifyDimens
import com.streamify.app.ui.theme.StreamifyType
import com.streamify.app.viewmodel.PlayerViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

@Composable
fun ArtistScreen(
    artistName: String,
    allTracks: List<Track>,
    playerViewModel: PlayerViewModel,
    onBack: () -> Unit,
    onTrackClick: (Track, List<Track>) -> Unit
) {
    val artistTracks = remember(artistName, allTracks) {
        allTracks.filter { it.artist.contains(artistName, ignoreCase = true) }
    }
    val currentTrack by playerViewModel.currentTrack.collectAsState()
    val playerState by playerViewModel.playerState.collectAsState()
    val playerIsPlaying = playerState.isPlaying
    val contextMenuController = LocalContextMenuController.current

    val firstTrackWithCover = artistTracks.find { !it.coverArtPath.isNullOrBlank() }

    // ── Phase 3: vertical 30s Clips rail + immersive feed (audio sync) ────
    val clipsState = com.streamify.app.ui.components.rememberArtistClips(artistName)
    var showClipsFeed by remember { mutableStateOf(false) }
    var resumeAudioOnClipsClose by remember { mutableStateOf(false) }

    // ── Phase 3: upcoming-release countdown banner + Pre-Save ─────────────
    var releaseWatch by remember { mutableStateOf<com.streamify.app.data.network.ReleaseWatcherApi.ReleaseWatch?>(null) }
    var preSaveIds by remember { mutableStateOf(setOf<String>()) }
    var nowTickMs by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(artistName) {
        releaseWatch = com.streamify.app.data.network.ReleaseWatcherApi.watchArtist(artistName)
        preSaveIds = com.streamify.app.data.network.PreSaveStore.loadAll().map { it.releaseId }.toSet()
    }
    LaunchedEffect(Unit) {
        while (isActive) {
            delay(1000)
            nowTickMs = System.currentTimeMillis()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(StreamifyColors.BgBase)
    ) {
        Spacer(modifier = Modifier.height(StreamifyDimens.SpaceGiant))

        // Top App Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = StreamifyDimens.SpaceMD),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = StreamifyColors.TextMain
                )
            }
            Spacer(modifier = Modifier.width(StreamifyDimens.SpaceSM))
            Text(
                text = artistName,
                style = StreamifyType.HeadlineMedium,
                color = StreamifyColors.TextMain,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Spacer(modifier = Modifier.height(StreamifyDimens.SpaceMD))

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = StreamifyDimens.SpaceGiant * 2)
        ) {
            // Artist Header Hero
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(StreamifyDimens.SpaceMD),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    AsyncImage(
                        model = firstTrackWithCover?.coverArtPath,
                        contentDescription = artistName,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(180.dp)
                            .clip(CircleShape)
                            .background(StreamifyColors.BgSurfaceElevated)
                    )

                    Spacer(modifier = Modifier.height(StreamifyDimens.SpaceMD))

                    Text(
                        text = artistName,
                        style = StreamifyType.HeadlineLarge,
                        color = StreamifyColors.TextMain,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(StreamifyDimens.SpaceXS))

                    Text(
                        text = "${artistTracks.size} Songs",
                        style = StreamifyType.BodyMedium,
                        color = StreamifyColors.TextSub
                    )

                    Spacer(modifier = Modifier.height(StreamifyDimens.SpaceMD))

                    // ── Gap #31: one-tap Follow + live follower count ──────
                    com.streamify.app.ui.components.FollowButton(
                        type = com.streamify.app.data.social.FollowGraphStore.FollowType.ARTIST,
                        id = artistName
                    )

                    Spacer(modifier = Modifier.height(StreamifyDimens.SpaceMD))

                    // Play All & Shuffle Buttons
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(
                            onClick = {
                                if (artistTracks.isNotEmpty()) {
                                    onTrackClick(artistTracks.first(), artistTracks)
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = StreamifyColors.Primary),
                            shape = CircleShape,
                            modifier = Modifier.padding(end = StreamifyDimens.SpaceSM)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.PlayArrow,
                                contentDescription = "Play All",
                                tint = Color.Black
                            )
                            Spacer(modifier = Modifier.width(StreamifyDimens.SpaceXS))
                            Text("Play", color = Color.Black, fontWeight = FontWeight.Bold)
                        }

                        OutlinedButton(
                            onClick = {
                                if (artistTracks.isNotEmpty()) {
                                    val shuffled = artistTracks.shuffled()
                                    onTrackClick(shuffled.first(), shuffled)
                                }
                            },
                            shape = CircleShape,
                            border = BorderStroke(1.dp, StreamifyColors.Primary)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Shuffle,
                                contentDescription = "Shuffle",
                                tint = StreamifyColors.Primary
                            )
                            Spacer(modifier = Modifier.width(StreamifyDimens.SpaceXS))
                            Text("Shuffle", color = StreamifyColors.Primary)
                        }
                    }
                }
            }

            // ── Phase 3: upcoming-release countdown banner + Pre-Save ─────
            val upcoming = releaseWatch?.nextUpcoming
            if (upcoming != null) {
                item(key = "artist_release_countdown") {
                    com.streamify.app.ui.components.ArtistCountdownBanner(
                        artistName = artistName,
                        release = upcoming,
                        nowMs = nowTickMs,
                        isPreSaved = upcoming.releaseId in preSaveIds,
                        onTogglePreSave = {
                            if (upcoming.releaseId in preSaveIds) {
                                com.streamify.app.data.network.PreSaveStore.removePreSave(upcoming.releaseId)
                            } else {
                                com.streamify.app.data.network.PreSaveStore.addPreSave(
                                    com.streamify.app.data.network.PreSaveStore.PreSave(
                                        releaseId = upcoming.releaseId,
                                        artistName = artistName,
                                        title = upcoming.title,
                                        expectedAtMs = upcoming.expectedAtMs
                                    )
                                )
                            }
                            preSaveIds = com.streamify.app.data.network.PreSaveStore.loadAll()
                                .map { it.releaseId }.toSet()
                        }
                    )
                    Spacer(modifier = Modifier.height(StreamifyDimens.SpaceMD))
                }
            }

            item {
                Text(
                    text = "Popular Songs",
                    style = StreamifyType.TitleLarge,
                    color = StreamifyColors.TextMain,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(
                        horizontal = StreamifyDimens.SpaceMD,
                        vertical = StreamifyDimens.SpaceSM
                    )
                )
            }

            // ── Phase 3: vertical 30s Clips discovery rail ────────────────────
            item(key = "artist_clips_rail") {
                com.streamify.app.ui.components.VerticalClipsRail(
                    clipsState = clipsState,
                    onOpenFeed = { showClipsFeed = true }
                )
                Spacer(modifier = Modifier.height(StreamifyDimens.SpaceMD))
            }

            items(artistTracks, key = { it.id }) { track ->
                SwipeableTrackListItem(
                    track = track,
                    isCurrentTrack = currentTrack?.id == track.id,
                    isActuallyPlaying = playerIsPlaying,
                    onClick = { onTrackClick(track, artistTracks) },
                    onOptionsClick = { contextMenuController.show(track, origin = MenuOrigin.HOME) },
                    onSwipeQueue = { playerViewModel.addToQueue(track) },
                    onSwipeLike = { playerViewModel.toggleLike(track) }
                )
            }
        }
    }

    // ── Phase 3: immersive vertical clips feed (audio-synced) ─────────────
    if (showClipsFeed) {
        val feedClips = (clipsState as? com.streamify.app.ui.components.ClipsState.Ready)
            ?.page?.clips ?: emptyList()
        if (feedClips.isNotEmpty()) {
            com.streamify.app.ui.components.VerticalClipsFeedSheet(
                clips = feedClips,
                onDismiss = { showClipsFeed = false },
                onFeedVisibilityChanged = { visible ->
                    if (visible) {
                        // Audio sync: the clip feed owns the audio stage while
                        // open — pause the music session, remember to resume.
                        resumeAudioOnClipsClose = playerState.isPlaying
                        if (playerState.isPlaying) playerViewModel.pause()
                    } else if (resumeAudioOnClipsClose) {
                        resumeAudioOnClipsClose = false
                        playerViewModel.play()
                    }
                }
            )
        }
    }
}
