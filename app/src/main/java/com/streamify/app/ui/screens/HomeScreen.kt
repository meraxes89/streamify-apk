package com.streamify.app.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import com.streamify.app.data.models.Track
import com.streamify.app.data.update.StreamifyUpdateManager
import com.streamify.app.data.supabase.SupabaseClient
import com.streamify.app.data.update.UpdateState
import com.streamify.app.jam.JoinFabric
import com.streamify.app.ui.components.*
import com.streamify.app.ui.components.yt.*
import com.streamify.app.ui.theme.*
import com.streamify.app.util.ApkInstaller
import com.streamify.app.viewmodel.CommunityViewModel
import com.streamify.app.viewmodel.HomeUiState
import com.streamify.app.viewmodel.HomeViewModel
import com.streamify.app.viewmodel.JamViewModel
import com.streamify.app.viewmodel.PlayerViewModel

@Composable
fun HomeScreen(
    playerViewModel: PlayerViewModel,
    jamViewModel: JamViewModel,
    viewModel: HomeViewModel = viewModel(),
    communityViewModel: CommunityViewModel = viewModel(),
    dominantColor: Color = BgBase,
    onTrackClick: (Track, List<Track>) -> Unit,
    onSettingsClick: () -> Unit,
    onSearchClick: () -> Unit = {},
    onNavigateToJam: () -> Unit = {},
    onNavigateToCommunity: () -> Unit = {},
    onNavigateToProfile: () -> Unit = {},
    /** Gap #32 friends-rail one-tap actions. */
    onListenAlong: (String, String) -> Unit = { _, _ -> },
    onJoinJam: () -> Unit = {},
    onBlend: (String, String) -> Unit = { _, _ -> },
    onViewProfile: (String, String, String) -> Unit = { _, _, _ -> },
    /** Phase 3 — "Your Updates" hub entry (new releases + pre-save alerts). */
    onOpenUpdatesHub: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()
    val communityState by communityViewModel.uiState.collectAsState()
    val isRefreshing by viewModel.isRefreshing.collectAsState()
    val user by SupabaseClient.currentUser.collectAsState()

    // Gap #20 / #26: dedicated flows so the Daylist hero and Daily Mixes
    // update without recomposing the whole shelf tree.
    val daylistUi by viewModel.daylist.collectAsState()
    val dailyMixes by viewModel.dailyMixes.collectAsState()
    
    // Decoupled Player State Observation: Isolates 200ms position ticks from root HomeScreen recomposition
    val currentTrack by remember {
        derivedStateOf { playerViewModel.playerState.value.currentTrack }
    }
    val isBuffering by remember {
        derivedStateOf { playerViewModel.playerState.value.isBuffering }
    }
    val listState = rememberLazyListState()
    val contextMenuController = LocalContextMenuController.current

    var selectedMood by remember { mutableStateOf("All") }

    // Gap #12 — Join Fabric rails (BLE proximity + LAN beacon 0x09 +
    // speaker connect): discovery runs exactly while Home is composed and
    // every scanner / listener job is torn down on dispose — zero leak.
    DisposableEffect(Unit) {
        JoinFabric.startDiscovery()
        onDispose { JoinFabric.stopDiscovery() }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BgBase)
    ) {
        // 1. YouTube Music Sticky Top App Bar
        YtTopAppBar(
            onSearchClick = onSearchClick,
            onAvatarClick = onNavigateToProfile,
            onCastClick = onNavigateToJam,
            onNotificationsClick = onOpenUpdatesHub,
            avatarUrl = user?.avatarUrl,
            avatarInitial = user?.displayName?.take(1) ?: "S"
        )


        // 2. YouTube Music Sticky Mood Filter Rail
        YtMoodFilterRail(
            selectedMood = selectedMood,
            onMoodSelected = { mood ->
                selectedMood = if (selectedMood == mood) "All" else mood
            },
            modifier = Modifier.fillMaxWidth()
        )

        // 3. YouTube Music Shelves Content
        Box(modifier = Modifier.weight(1f).fillMaxSize()) {
            when (val state = uiState) {
                is HomeUiState.Loading -> {
                    YtHomeSkeleton()
                }
                is HomeUiState.Error -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = "Error: ${state.message}",
                            color = Primary,
                            style = LocalAppTypography.current.songArtist
                        )
                    }
                }
                is HomeUiState.Success -> {
                    val allTracks = state.allTracks

                    // Derived State Isolation: Only re-filters when allTracks or selectedMood changes
                    val displayTracks = remember(allTracks, selectedMood) {
                        when (selectedMood.lowercase()) {
                            "workout" -> allTracks.filter { it.bpm >= 120f || it.bpm == 0f }.ifEmpty { allTracks }
                            "relax", "chill" -> allTracks.filter { (it.bpm in 60f..110f) || it.bpm == 0f }.ifEmpty { allTracks }
                            "focus" -> allTracks.filter { it.bpm in 70f..115f }.ifEmpty { allTracks }
                            "energize" -> allTracks.filter { it.bpm >= 125f }.ifEmpty { allTracks }
                            else -> allTracks
                        }
                    }

                    val screenConfig = LocalScreenConfiguration.current
                    val gridColumns = remember(screenConfig.widthDp) {
                        ((screenConfig.widthDp.value / 158f).toInt()).coerceAtLeast(2)
                    }

                    val quickPickCandidates = remember(displayTracks, state.sessionRecommendations, allTracks) {
                        if (displayTracks.isNotEmpty()) displayTracks else state.sessionRecommendations.ifEmpty { allTracks }
                    }
                    val quickPickColumns = remember(quickPickCandidates) {
                        val pool = if (quickPickCandidates.isNotEmpty()) quickPickCandidates else allTracks
                        pool.take(16).chunked(4)
                    }

                    val listenAgainCandidates = remember(state.topPlayed, state.recent) {
                        val combined = (state.topPlayed + state.recent).distinctBy { it.id }
                        if (combined.isNotEmpty()) combined else allTracks
                    }
                    val listenAgainColumns = remember(listenAgainCandidates, gridColumns) {
                        listenAgainCandidates.take(gridColumns * 6).chunked(if (screenConfig.isTablet) 3 else 2)
                    }

                    val supermixPool = remember(state.madeForYou, state.sessionRecommendations) {
                        (state.madeForYou + state.sessionRecommendations).distinctBy { it.id }.ifEmpty { allTracks }
                    }

                    val circadianColumns = remember(state.circadianRecommendations, gridColumns) {
                        state.circadianRecommendations.take(gridColumns * 6).chunked(if (screenConfig.isTablet) 3 else 2)
                    }

                    val hybridColumns = remember(state.hybridRecommendations, gridColumns) {
                        state.hybridRecommendations.take(gridColumns * 4).chunked(if (screenConfig.isTablet) 3 else 2)
                    }

                    val context = LocalContext.current
                    val updateState by StreamifyUpdateManager.updateState.collectAsState()

                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 120.dp)
                    ) {
                        // DAYLIST HERO (Gap #20): morphing circadian banner.
                        val daylistHero = daylistUi.hero
                        if (daylistHero != null) {
                            item(key = "daylist_hero", contentType = "daylistHero") {
                                YtDaylistHeroBanner(
                                    model = daylistHero,
                                    onPlayDaylist = {
                                        val tracks = daylistUi.tracks
                                        tracks.firstOrNull()?.let { onTrackClick(it, tracks) }
                                    }
                                )
                            }
                        }

                        // DAILY MIXES 1–6 (Gap #26): artist-cluster carousels.
                        if (dailyMixes.isNotEmpty()) {
                            item(key = "header_daily_mixes", contentType = "sectionHeader") {
                                YtSectionHeader(
                                    title = "Daily Mixes",
                                    kicker = "Your artist clusters • 1–6"
                                )
                            }
                            item(key = "rail_daily_mixes", contentType = "rail") {
                                LazyRow(
                                    contentPadding = PaddingValues(horizontal = 16.dp),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    items(
                                        items = dailyMixes,
                                        key = { "daily_mix_${it.number}_${it.seedArtist}" },
                                        contentType = { "dailyMixCard" }
                                    ) { mix ->
                                        YtDailyMixCard(
                                            mix = mix,
                                            onPlayMix = {
                                                mix.tracks.firstOrNull()?.let {
                                                    onTrackClick(it, mix.tracks)
                                                }
                                            },
                                            onLongPress = {
                                                com.streamify.app.util.StreamifyHapticEngine.magneticDetent()
                                                mix.tracks.firstOrNull()?.let { track ->
                                                    contextMenuController.show(track, origin = MenuOrigin.HOME)
                                                }
                                            }
                                        )
                                    }
                                }
                            }
                        }

                        // In-App OTA Update Banner
                        if (updateState is UpdateState.UpdateAvailable) {
                            val available = updateState as UpdateState.UpdateAvailable
                            item(key = "card_update_available", contentType = "updateBanner") {
                                UpdateAvailableCard(
                                    updateState = available,
                                    onUpdateClick = {
                                        StreamifyUpdateManager.dispatchUpdate(context, available.buildInfo)
                                    },
                                    onDismissClick = {
                                        StreamifyUpdateManager.dismissUpdate(context, available.buildInfo.buildNumber)
                                    }
                                )
                            }
                        }

                        // Optional Broadcast Banner
                        if (communityState.activeBroadcasts.isNotEmpty()) {
                            item(key = "broadcast_banner", contentType = "broadcastBanner") {
                                Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                                    BroadcastBanner(broadcasts = communityState.activeBroadcasts)
                                }
                            }
                        }

                        // SHELF 0: FRIENDS ARE LISTENING (Gap #32) — live presence
                        // rail with one-tap Listen Along / Join Jam / Blend and a
                        // tap-through to the friend's public profile.
                        if (communityState.friendsActivity.isNotEmpty()) {
                            item(key = "header_friends", contentType = "sectionHeader") {
                                YtSectionHeader(
                                    title = "Friends",
                                    kicker = "Listening Now"
                                )
                            }
                            item(key = "row_friends", contentType = "rail") {
                                LazyRow(
                                    contentPadding = PaddingValues(horizontal = 16.dp),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    items(
                                        communityState.friendsActivity,
                                        key = { "home_friend_${it.userId}" },
                                        contentType = { "friendActivityCard" }
                                    ) { friend ->
                                        FriendActivityCard(
                                            friend = friend,
                                            onListenAlong = {
                                                onListenAlong(friend.trackTitle, friend.trackArtist)
                                            },
                                            onJoinJam = if (friend.sessionCode != null) {
                                                onJoinJam
                                            } else null,
                                            onBlend = {
                                                val seeds = friend.trackArtist
                                                    .split(",", "•")
                                                    .map { it.trim() }
                                                    .filter { it.isNotBlank() }
                                                onBlend(friend.displayName, seeds.joinToString(","))
                                            },
                                            onViewProfile = {
                                                onViewProfile(
                                                    friend.userId,
                                                    friend.displayName,
                                                    friend.avatarUrl
                                                )
                                            }
                                        )
                                    }
                                }
                            }
                        }

                        // SHELF 1: LISTEN AGAIN
                        if (listenAgainColumns.isNotEmpty() && listenAgainColumns.first().isNotEmpty()) {
                            item(key = "header_listen_again", contentType = "sectionHeader") {
                                YtSectionHeader(
                                    title = "Listen Again",
                                    kicker = "Past Heavy Rotations"
                                )
                            }
                            item(key = "grid_listen_again", contentType = "grid") {
                                YtListenAgainGrid(
                                    columns = listenAgainColumns,
                                    currentPlayingTrack = currentTrack,
                                    isBuffering = isBuffering,
                                    onTrackClick = onTrackClick
                                )
                            }
                        }

                        // SHELF 2: QUICK PICKS
                        if (quickPickColumns.isNotEmpty() && quickPickColumns.first().isNotEmpty()) {
                            item(key = "header_quick_picks", contentType = "sectionHeader") {
                                YtSectionHeader(
                                    title = "Quick Picks",
                                    kicker = if (selectedMood != "All") "$selectedMood Mix" else "Start radio for"
                                )
                            }
                            item(key = "carousel_quick_picks", contentType = "carousel") {
                                YtQuickPicksCarousel(
                                    columns = quickPickColumns,
                                    currentPlayingTrack = currentTrack,
                                    isBuffering = isBuffering,
                                    onTrackClick = onTrackClick
                                )
                            }
                        }

                        // SHELF 3: MY SUPERMIX & DYNAMIC STATIONS
                        if (supermixPool.isNotEmpty()) {
                            item(key = "header_supermix", contentType = "sectionHeader") {
                                YtSectionHeader(
                                    title = "Mixed For You",
                                    kicker = "Personalized Stations"
                                )
                            }
                            item(key = "row_supermix", contentType = "rail") {
                                LazyRow(
                                    contentPadding = PaddingValues(horizontal = 16.dp),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    items(
                                        items = supermixPool.take(8),
                                        key = { "supermix_${it.id}" },
                                        contentType = { "supermixCard" }
                                    ) { track ->
                                        val isCardBuffering = isBuffering && currentTrack != null && (
                                            (currentTrack?.id ?: 0) > 0 && currentTrack?.id == track.id ||
                                            (currentTrack?.title.equals(track.title, ignoreCase = true) && currentTrack?.artist.equals(track.artist, ignoreCase = true))
                                        )
                                        YtSupermixCard(
                                            title = "${track.artist} Mix",
                                            subtitle = "Continuous station with ${track.title}",
                                            artworkUrl = track.coverArtPath,
                                            isLoading = isCardBuffering,
                                            onClick = { onTrackClick(track, supermixPool) },
                                            onLongClick = {
                                                com.streamify.app.util.StreamifyHapticEngine.magneticDetent()
                                                contextMenuController.show(track, origin = MenuOrigin.HOME)
                                            }
                                        )
                                    }
                                }
                            }
                        }

                        // SHELF 4: CIRCADIAN DAYPARTING RHYTHM
                        if (state.circadianRecommendations.isNotEmpty()) {
                            item(key = "header_circadian", contentType = "sectionHeader") {
                                YtSectionHeader(
                                    title = state.circadianSlotTitle,
                                    kicker = "Circadian Acoustic Tuning"
                                )
                            }
                            item(key = "grid_circadian", contentType = "grid") {
                                YtListenAgainGrid(
                                    columns = circadianColumns,
                                    currentPlayingTrack = currentTrack,
                                    isBuffering = isBuffering,
                                    onTrackClick = onTrackClick
                                )
                            }
                        }

                        // SHELF 5: HYBRID ASYMMETRIC RADAR
                        if (state.hybridRecommendations.isNotEmpty()) {
                            item(key = "header_hybrid", contentType = "sectionHeader") {
                                YtSectionHeader(
                                    title = "Hybrid Radar",
                                    kicker = "Last.fm Graph × On-Device SIMD"
                                )
                            }
                            item(key = "grid_hybrid", contentType = "grid") {
                                YtListenAgainGrid(
                                    columns = hybridColumns,
                                    currentPlayingTrack = currentTrack,
                                    isBuffering = isBuffering,
                                    onTrackClick = onTrackClick
                                )
                            }
                        }

                        // SHELF 6: COMMUNITY TRENDING PLAYLISTS
                        if (communityState.communityPlaylists.isNotEmpty()) {
                            item(key = "header_community", contentType = "sectionHeader") {
                                YtSectionHeader(
                                    title = "Community Trending",
                                    kicker = "Curated by Streamify Listeners",
                                    actionText = "See All",
                                    onActionClick = onNavigateToCommunity
                                )
                            }
                            item(key = "row_community", contentType = "rail") {
                                LazyRow(
                                    contentPadding = PaddingValues(horizontal = 16.dp),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    items(communityState.communityPlaylists, key = { it.id }, contentType = { "communityPlaylistCard" }) { playlist ->
                                        YtSupermixCard(
                                            title = playlist.name,
                                            subtitle = "by ${playlist.creatorName}",
                                            artworkUrl = playlist.coverUrl,
                                            onClick = onNavigateToCommunity
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // 4. JOIN FABRIC RAIL (Gap #12) — zero-friction proximity join:
        // BLE tap-to-join (≤ 1 m), LAN beacon 0x09 room notice, and the
        // speaker-connect hosting prompt. Anchored at the bottom of Home;
        // renders only while a live sighting exists.
        JoinPromptRail(
            jamViewModel = jamViewModel,
            playerViewModel = playerViewModel,
            onOpenJamScreen = onNavigateToJam
        )
    }
}
