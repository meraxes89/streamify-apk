package com.streamify.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.streamify.app.data.supabase.CommunityPlaylist
import com.streamify.app.ui.components.yt.YtThumbnail
import com.streamify.app.ui.theme.*
import com.streamify.app.viewmodel.CommunityViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun CommunityHubScreen(
    communityViewModel: CommunityViewModel,
    onBack: () -> Unit,
    onPlaylistClick: (CommunityPlaylist) -> Unit,
    /** Gap #32 friend-feed actions. */
    onListenAlong: (String, String) -> Unit = { _, _ -> },
    onJoinJam: () -> Unit = {},
    onViewProfile: (String, String, String) -> Unit = { _, _, _ -> },
    onBlend: (String, String) -> Unit = { _, _ -> }
) {
    val state by communityViewModel.uiState.collectAsState()
    var selectedGenre by remember { mutableStateOf("All") }
    val genres = listOf("All", "Top Hits", "Hip-Hop", "Chill Lo-Fi", "Electronic", "Focus", "Rock", "Workout")

    // Live feed (Gap #32): refresh presence while the hub is composed.
    LaunchedEffect(Unit) {
        communityViewModel.loadCommunityFeed()
        while (true) {
            kotlinx.coroutines.delay(30_000)
            communityViewModel.loadCommunityFeed()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BgBase)
            .statusBarsPadding()
    ) {
        // Top App Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = TextMain,
                    modifier = Modifier.size(24.dp)
                )
            }
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = "Community Hub",
                style = LocalAppTypography.current.headlineMedium.copy(fontSize = 18.sp),
                color = TextMain
            )
        }

        // Genre Filter Chips
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(genres, key = { it }) { genre ->
                val isSelected = selectedGenre == genre
                Surface(
                    color = if (isSelected) BgChipActive else BgChipInactive,
                    shape = RoundedCornerShape(8.dp),
                    border = if (isSelected) null else androidx.compose.foundation.BorderStroke(1.dp, BorderChip),
                    modifier = Modifier
                        .height(32.dp)
                        .clickable { selectedGenre = genre }
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.padding(horizontal = 12.dp)
                    ) {
                        Text(
                            text = genre,
                            style = LocalAppTypography.current.chipText.copy(fontSize = 13.sp),
                            color = if (isSelected) TextOnActiveChip else TextMain
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        var isRefreshingCommunity by remember { mutableStateOf(false) }
        val coroutineScope = rememberCoroutineScope()

        com.streamify.app.ui.components.StreamifyPullToRefreshContainer(
            isRefreshing = isRefreshingCommunity,
            onRefresh = {
                coroutineScope.launch {
                    isRefreshingCommunity = true
                    communityViewModel.loadCommunityFeed()
                    kotlinx.coroutines.delay(400)
                    isRefreshingCommunity = false
                }
            }
        ) {
            if (state.isLoading && !isRefreshingCommunity) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(bottom = 120.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = Primary, strokeWidth = 3.dp)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 120.dp)
                ) {
                    // ── FRIEND ACTIVITY LIVE FEED (Gap #32) ─────────────────
                    if (state.friendsActivity.isNotEmpty()) {
                        item(key = "header_friend_activity") {
                            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                                Text(
                                    text = "Friend Activity",
                                    style = LocalAppTypography.current.headlineMedium.copy(fontSize = 16.sp),
                                    color = TextMain
                                )
                                Text(
                                    text = "Live • what friends are playing right now",
                                    style = LocalAppTypography.current.songArtist.copy(fontSize = 12.sp),
                                    color = TextSecondary
                                )
                            }
                        }
                        items(
                            state.friendsActivity,
                            key = { "friend_${it.userId}" }
                        ) { friend ->
                            com.streamify.app.ui.components.FriendActivityCard(
                                friend = friend,
                                compact = true,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 5.dp),
                                onListenAlong = { onListenAlong(friend.trackTitle, friend.trackArtist) },
                                onJoinJam = if (friend.sessionCode != null) {
                                    { onJoinJam() }
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
                        item(key = "friend_activity_spacer") {
                            Spacer(modifier = Modifier.height(12.dp))
                            androidx.compose.material3.HorizontalDivider(
                                color = Divider,
                                thickness = 0.5.dp,
                                modifier = Modifier.padding(horizontal = 16.dp)
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                        }
                    }

                    // ── COMMUNITY PLAYLISTS ──────────────────────────────────
                    if (state.communityPlaylists.isEmpty() && state.friendsActivity.isEmpty()) {
                        item(key = "empty_state") {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 80.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("🎶", fontSize = 42.sp)
                                    Spacer(modifier = Modifier.height(10.dp))
                                    Text(
                                        text = "No community playlists yet",
                                        style = LocalAppTypography.current.headlineMedium.copy(fontSize = 16.sp),
                                        color = TextMain
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = "Publish your custom playlist from Library to see it here!",
                                        style = LocalAppTypography.current.songArtist,
                                        color = TextSecondary
                                    )
                                }
                            }
                        }
                    } else {
                        item(key = "header_community") {
                            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                                Text(
                                    text = "Trending Playlists",
                                    style = LocalAppTypography.current.headlineMedium.copy(fontSize = 16.sp),
                                    color = TextMain
                                )
                            }
                        }
                    }

                    items(state.communityPlaylists, key = { it.id }) { playlist ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(64.dp)
                                .clickable { onPlaylistClick(playlist) }
                                .padding(horizontal = 16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (playlist.coverUrl.isNotBlank()) {
                                YtThumbnail(
                                    url = playlist.coverUrl,
                                    size = 48.dp,
                                    cornerRadius = 4.dp
                                )
                            } else {
                                Box(
                                    modifier = Modifier
                                        .size(48.dp)
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(BgSurfaceElevated),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.QueueMusic,
                                        contentDescription = null,
                                        tint = Primary,
                                        modifier = Modifier.size(24.dp)
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.width(12.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = playlist.name,
                                    style = LocalAppTypography.current.songTitle.copy(fontSize = 14.sp),
                                    color = TextMain,
                                    maxLines = 1
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "by ${playlist.creatorName} • ${playlist.trackCount} songs",
                                    style = LocalAppTypography.current.songArtist.copy(fontSize = 12.sp),
                                    color = TextSecondary,
                                    maxLines = 1
                                )
                            }

                            Surface(
                                color = BgSurfaceElevated,
                                shape = CircleShape
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Favorite,
                                        contentDescription = null,
                                        tint = Primary,
                                        modifier = Modifier.size(13.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = "${playlist.likesCount}",
                                        style = LocalAppTypography.current.songArtist.copy(fontSize = 11.sp),
                                        color = TextMain
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
