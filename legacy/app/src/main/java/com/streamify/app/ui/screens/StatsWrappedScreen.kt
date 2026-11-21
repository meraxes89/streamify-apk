package com.streamify.app.ui.screens

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.streamify.app.data.telemetry.WrappedStats
import com.streamify.app.data.telemetry.YtStatsTelemetryEngine
import com.streamify.app.data.models.Track
import com.streamify.app.ui.components.*
import com.streamify.app.ui.components.yt.*
import com.streamify.app.ui.theme.*
import com.streamify.app.viewmodel.PlayerViewModel

@Composable
fun StatsWrappedScreen(
    playerViewModel: PlayerViewModel? = null,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        YtStatsTelemetryEngine.initFromContext(context)
    }
    val cachedStats by YtStatsTelemetryEngine.cachedWrappedStats.collectAsState()
    val computedStats by produceState<WrappedStats?>(initialValue = cachedStats) {
        YtStatsTelemetryEngine.computeWrappedStats(forceRefresh = true).collect {
            value = it
        }
    }
    val stats = computedStats ?: cachedStats
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BgBase)
            .statusBarsPadding()
            .verticalScroll(scrollState)
            .padding(bottom = 120.dp) // Protective padding for docked player
    ) {
        // Top App Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
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
                    text = "Streamify Wrapped 2026",
                    style = LocalAppTypography.current.headlineMedium.copy(fontSize = 18.sp),
                    color = TextMain
                )
            }

            stats?.let { currentStats ->
                val topSongsText = currentStats.top5Tracks.mapIndexed { idx, t -> "${idx + 1}. ${t.title} - ${t.artist}" }.joinToString("\n")
                IconButton(onClick = {
                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(
                            Intent.EXTRA_TEXT,
                            "🔥 My 2026 Streamify Wrapped:\n\n" +
                                    "🎧 ${currentStats.totalMinutes} Minutes Listened\n" +
                                    "✨ Persona: ${currentStats.personaName} ${currentStats.personaEmoji}\n" +
                                    "🎶 Top Genre: ${currentStats.topGenres.firstOrNull()?.first ?: "Music"}\n\n" +
                                    "🎵 Top 5 Songs:\n$topSongsText\n\n" +
                                    "Listen on Streamify!"
                        )
                    }
                    context.startActivity(Intent.createChooser(shareIntent, "Share Streamify Wrapped"))
                }) {
                    Icon(
                        imageVector = Icons.Filled.Share,
                        contentDescription = "Share",
                        tint = ActiveControl,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }

        val currentStats = stats
        if (currentStats == null) {
            // Loading Telemetry DNA state
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(300.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Primary, strokeWidth = 3.dp)
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Computing your audio DNA...",
                        style = LocalAppTypography.current.songArtist,
                        color = TextSecondary
                    )
                }
            }
        } else {
            Spacer(modifier = Modifier.height(8.dp))

            // 1. Hero Telemetry Card (Total Minutes & Rotations)
            YtWrappedHeroCard(
                totalMinutes = currentStats.totalMinutes,
                totalTracks = currentStats.totalTracks,
                likedSongs = currentStats.likedSongs,
                topPlayedCount = currentStats.topPlayedCount
            )

            // 2. 🔥 TOP 5 SONGS I LISTENED (With Large Thumbnails & Minutes)
            if (currentStats.top5Tracks.isNotEmpty()) {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "YOUR TOP 5 SONGS",
                    style = LocalAppTypography.current.songArtist.copy(
                        fontSize = 11.sp,
                        letterSpacing = 1.sp,
                        fontWeight = FontWeight.Bold
                    ),
                    color = TextSecondary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                )

                Surface(
                    color = BgSurfaceElevated,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                ) {
                    Column(modifier = Modifier.padding(vertical = 8.dp)) {
                        currentStats.top5Tracks.forEachIndexed { index, track ->
                            val rank = index + 1
                            val rankColor = when (rank) {
                                1 -> Color(0xFFFFD700) // Gold
                                2 -> Color(0xFFC0C0C0) // Silver
                                3 -> Color(0xFFCD7F32) // Bronze
                                else -> TextSecondary
                            }

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        playerViewModel?.playTrack(track, currentStats.top5Tracks)
                                    }
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // Rank Badge
                                Box(
                                    modifier = Modifier.width(28.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "#$rank",
                                        style = LocalAppTypography.current.headlineSmall.copy(
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Black
                                        ),
                                        color = rankColor
                                    )
                                }

                                Spacer(modifier = Modifier.width(8.dp))

                                // Large Thumbnail
                                YtThumbnail(
                                    url = track.coverArtPath,
                                    size = 52.dp,
                                    cornerRadius = 8.dp
                                )

                                Spacer(modifier = Modifier.width(12.dp))

                                // Title & Artist
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = track.title,
                                        style = LocalAppTypography.current.songTitle.copy(
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Bold
                                        ),
                                        color = TextMain,
                                        maxLines = 1
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = "${track.artist} • ${if (track.playCount > 0) "${track.playCount} plays" else if (track.durationSec > 0) "${track.durationSec / 60}m ${track.durationSec % 60}s" else "Heavy Rotation"}",
                                        style = LocalAppTypography.current.songArtist.copy(fontSize = 12.sp),
                                        color = TextSecondary,
                                        maxLines = 1
                                    )

                                }

                                // Play Button Icon
                                IconButton(
                                    onClick = {
                                        playerViewModel?.playTrack(track, currentStats.top5Tracks)
                                    },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.PlayArrow,
                                        contentDescription = "Play",
                                        tint = ActiveControl,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }

                            if (index < currentStats.top5Tracks.size - 1) {
                                Divider(
                                    color = BorderChip.copy(alpha = 0.5f),
                                    thickness = 0.5.dp,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                }
            }

            // 3. AI Audio Persona Card (BPM-Reactive)
            Spacer(modifier = Modifier.height(16.dp))
            YtPersonaCard(
                personaName = currentStats.personaName,
                personaEmoji = currentStats.personaEmoji,
                description = currentStats.personaDescription,
                bpm = currentStats.averageBpm
            )

            Spacer(modifier = Modifier.height(16.dp))

            // 4. Top Genres Breakdown
            Text(
                text = "TOP GENRES",
                style = LocalAppTypography.current.songArtist.copy(
                    fontSize = 11.sp,
                    letterSpacing = 0.5.sp,
                    fontWeight = FontWeight.Bold
                ),
                color = TextSecondary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
            )

            Surface(
                color = BgSurfaceElevated,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
            ) {
                Column(modifier = Modifier.padding(vertical = 12.dp)) {
                    currentStats.topGenres.forEachIndexed { index, (genre, percentage) ->
                        YtGenreDistributionBar(
                            rank = index + 1,
                            genre = genre,
                            percentage = percentage
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Share CTA Button
            val topSongsShareText = currentStats.top5Tracks.mapIndexed { idx, t -> "${idx + 1}. ${t.title} - ${t.artist}" }.joinToString("\n")
            Button(
                onClick = {
                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(
                            Intent.EXTRA_TEXT,
                            "🔥 My 2026 Streamify Wrapped:\n\n" +
                                    "🎧 ${currentStats.totalMinutes} Minutes Listened\n" +
                                    "✨ Persona: ${currentStats.personaName} ${currentStats.personaEmoji}\n" +
                                    "🎶 Top Genre: ${currentStats.topGenres.firstOrNull()?.first ?: "Music"}\n\n" +
                                    "🎵 Top 5 Songs:\n$topSongsShareText\n\n" +
                                    "Listen on Streamify!"
                        )
                    }
                    context.startActivity(Intent.createChooser(shareIntent, "Share Streamify Wrapped"))
                },
                colors = ButtonDefaults.buttonColors(containerColor = ActiveControl),
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .height(48.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.Share,
                    contentDescription = null,
                    tint = TextOnActiveChip,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Share Your Wrapped",
                    style = LocalAppTypography.current.chipText.copy(fontSize = 14.sp),
                    color = TextOnActiveChip
                )
            }
        }
    }
}
