package com.streamify.app.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.streamify.app.data.discovery.BlendTrack
import com.streamify.app.ui.components.yt.YtThumbnail
import com.streamify.app.ui.theme.*
import com.streamify.app.viewmodel.BlendViewModel
import com.streamify.app.viewmodel.PlayerViewModel

/** Friend-taste provenance dot color (member B). */
private val SkyProvenance = Color(0xFF38BDF8)

/**
 * BlendScreen (Gap #25) — the shared Blend playlist:
 *  - animated split-avatar header (the two members slide in and overlap),
 *  - counting match percentage score ("88% Taste Match"),
 *  - "Both of you love this" badges on mutual tracks,
 *  - per-row provenance dot showing which member's taste surfaced the song,
 *  - pull-style regenerate affordance.
 */
@Composable
fun BlendScreen(
    playerViewModel: PlayerViewModel,
    blendViewModel: BlendViewModel = androidx.lifecycle.viewmodel.compose.viewModel(),
    friendName: String = "Friend",
    friendSeeds: List<String> = emptyList(),
    friendAvatarUrl: String = "",
    onBack: () -> Unit,
    onTrackClick: (com.streamify.app.data.models.Track, List<com.streamify.app.data.models.Track>) -> Unit
) {
    LaunchedEffect(friendName, friendSeeds) {
        blendViewModel.generateBlend(friendName, friendSeeds, friendAvatarUrl)
    }

    val state by blendViewModel.state.collectAsState()
    val result = state.result

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(StreamifyColors.BgBase)
            .statusBarsPadding()
    ) {
        // Top bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = StreamifyColors.TextMain
                )
            }
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = "Blend",
                style = StreamifyType.HeadlineMedium,
                color = StreamifyColors.TextMain
            )
            Spacer(modifier = Modifier.weight(1f))
            IconButton(
                onClick = { blendViewModel.generateBlend(friendName, friendSeeds, friendAvatarUrl) }
            ) {
                Icon(
                    Icons.Filled.Refresh,
                    contentDescription = "Regenerate blend",
                    tint = StreamifyColors.TextSub
                )
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 140.dp)
        ) {
            item(key = "blend_hero") {
                BlendHeader(
                    friendName = state.friendName.ifBlank { friendName },
                    friendAvatarUrl = state.friendAvatarUrl.ifBlank { friendAvatarUrl },
                    matchPercent = result?.matchPercent ?: 0,
                    mutualArtists = result?.mutualArtists ?: emptyList(),
                    ready = result != null
                )
            }

            if (state.isLoading && result == null) {
                item(key = "blend_loading") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 48.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = StreamifyColors.Primary)
                    }
                }
            }

            if (result != null) {
                if (result.tracks.isEmpty()) {
                    item(key = "blend_empty") {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(32.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "Couldn't cross-pollinate yet — check your connection and try again.",
                                style = StreamifyType.BodyMedium,
                                color = StreamifyColors.TextSub
                            )
                        }
                    }
                } else {
                    item(key = "blend_play_all") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Button(
                                onClick = {
                                    val queue = blendViewModel.playableQueue()
                                    queue.firstOrNull()?.let { onTrackClick(it, queue) }
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = StreamifyColors.Primary
                                ),
                                shape = RoundedCornerShape(24.dp)
                            ) {
                                Icon(
                                    Icons.Filled.PlayArrow,
                                    contentDescription = null,
                                    tint = Color.Black,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    "Play Blend",
                                    color = Color.Black,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                "${result.tracks.size} tracks • refreshed daily",
                                style = StreamifyType.Caption,
                                color = StreamifyColors.TextSub
                            )
                        }
                    }

                    items(
                        items = result.tracks,
                        key = { it.videoId.ifBlank { it.title + it.artist } }
                    ) { blendTrack ->
                        BlendTrackRow(
                            blendTrack = blendTrack,
                            friendName = state.friendName.ifBlank { friendName },
                            onClick = {
                                val queue = blendViewModel.playableQueue()
                                onTrackClick(blendTrack.toTrack(), queue)
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BlendHeader(
    friendName: String,
    friendAvatarUrl: String,
    matchPercent: Int,
    mutualArtists: List<String>,
    ready: Boolean
) {
    // Counting match score — 0 → N animation once the blend lands.
    val animatedMatch by animateIntAsState(
        targetValue = if (ready) matchPercent else 0,
        animationSpec = tween(durationMillis = 900),
        label = "blendMatchPercent"
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(
                Brush.linearGradient(
                    listOf(
                        StreamifyColors.Primary.copy(alpha = 0.65f),
                        StreamifyColors.PrimaryDark.copy(alpha = 0.85f)
                    )
                )
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Split-avatar pair — slide in from both sides and overlap.
            Row(verticalAlignment = Alignment.CenterVertically) {
                AvatarSlideIn(fromStart = true) {
                    BlendAvatar(
                        label = "You",
                        avatarUrl = "",
                        ringColor = Color.White
                    )
                }
                AvatarSlideIn(fromStart = false, overlap = (-14).dp) {
                    BlendAvatar(
                        label = friendName.take(1).uppercase().ifBlank { "F" },
                        avatarUrl = friendAvatarUrl,
                        ringColor = StreamifyColors.Primary
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text(
                        text = "You & $friendName",
                        style = StreamifyType.TitleMedium.copy(fontWeight = FontWeight.Bold),
                        color = Color.White
                    )
                    Text(
                        text = "Blend",
                        style = StreamifyType.Caption,
                        color = Color.White.copy(alpha = 0.8f)
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Match percentage score.
            Text(
                text = "$animatedMatch%",
                style = StreamifyType.HeadlineLarge.copy(
                    fontSize = 44.sp,
                    fontWeight = FontWeight.Black
                ),
                color = Color.White
            )
            Text(
                text = "TASTE MATCH",
                style = StreamifyType.Caption.copy(
                    letterSpacing = 2.sp,
                    fontWeight = FontWeight.Bold
                ),
                color = Color.White.copy(alpha = 0.85f)
            )

            if (mutualArtists.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "You both love ${mutualArtists.take(3).joinToString(" · ") { it.replaceFirstChar { c -> c.uppercase() } }}",
                    style = StreamifyType.BodySmall,
                    color = Color.White.copy(alpha = 0.9f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun AvatarSlideIn(
    fromStart: Boolean,
    overlap: androidx.compose.ui.unit.Dp = 0.dp,
    content: @Composable () -> Unit
) {
    AnimatedVisibility(
        visible = true,
        enter = slideInHorizontally(
            animationSpec = tween(500, delayMillis = if (fromStart) 0 else 120)
        ) { full -> if (fromStart) -full else full } + fadeIn(tween(500))
    ) {
        Box(modifier = Modifier.padding(end = overlap)) { content() }
    }
}

@Composable
private fun BlendAvatar(label: String, avatarUrl: String, ringColor: Color) {
    if (avatarUrl.isNotBlank()) {
        AsyncImage(
            model = avatarUrl,
            contentDescription = null,
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(ringColor)
                .padding(2.dp)
                .clip(CircleShape)
        )
    } else {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.35f)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = label.take(1).uppercase().ifBlank { "?" },
                style = StreamifyType.HeadlineMedium.copy(fontWeight = FontWeight.Bold),
                color = Color.White
            )
        }
    }
}

@Composable
private fun BlendTrackRow(
    blendTrack: BlendTrack,
    friendName: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Provenance chip: whose taste surfaced this row.
        val isMine = blendTrack.memberIndex == 0
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(if (isMine) StreamifyColors.Primary else SkyProvenance)
        )
        Spacer(modifier = Modifier.width(10.dp))

        YtThumbnail(
            url = blendTrack.thumbnailUrl.ifBlank {
                "https://i.ytimg.com/vi/${blendTrack.videoId}/hqdefault.jpg"
            },
            size = 46.dp,
            cornerRadius = 6.dp
        )

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = blendTrack.title,
                style = LocalAppTypography.current.songTitle.copy(fontSize = 14.sp),
                color = StreamifyColors.TextMain,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = blendTrack.artist,
                style = LocalAppTypography.current.songArtist.copy(fontSize = 12.sp),
                color = StreamifyColors.TextSub,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (blendTrack.mutual) {
                Spacer(modifier = Modifier.height(4.dp))
                MutualBadge(friendName = friendName)
            }
        }

        Text(
            text = if (isMine) "You" else friendName.take(10),
            style = StreamifyType.Caption,
            color = StreamifyColors.TextTertiary,
            maxLines = 1
        )
    }
}

@Composable
internal fun MutualBadge(friendName: String) {
    Surface(
        color = StreamifyColors.Primary.copy(alpha = 0.16f),
        shape = RoundedCornerShape(6.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
        ) {
            Icon(
                Icons.Filled.Favorite,
                contentDescription = null,
                tint = StreamifyColors.Primary,
                modifier = Modifier.size(10.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = "Both of you love this",
                style = StreamifyType.Caption.copy(fontSize = 10.sp, fontWeight = FontWeight.Bold),
                color = StreamifyColors.Primary
            )
        }
    }
}
