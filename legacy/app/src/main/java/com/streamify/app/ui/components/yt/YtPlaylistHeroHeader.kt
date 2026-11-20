package com.streamify.app.ui.components.yt
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.streamify.app.ui.theme.*

@Composable
fun YtPlaylistHeroHeader(
    title: String,
    subtitle: String,
    artworkUrl: String?,
    scrollOffset: Float = 0f, // Passed from LazyColumn for 120fps GPU parallax
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    onExportM3u: (() -> Unit)? = null,
    isRadioDiscoveryMode: Boolean = false,
    onToggleRadioDiscoveryMode: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    // Extreme GPU Parallax: Scale and translate based on scroll without layout passes
    val scale = (1f - (scrollOffset.coerceIn(0f, 300f) / 1000f)).coerceIn(0.7f, 1f)
    val translationY = scrollOffset.coerceIn(0f, 300f) * 0.4f

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // 180dp Hero Artwork
        Box(
            modifier = Modifier
                .size(180.dp)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    this.translationY = -translationY
                }
                .clip(RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center
        ) {
            YtThumbnail(
                url = artworkUrl,
                size = 180.dp,
                cornerRadius = 8.dp
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Title and Subtitle
        Text(
            text = title,
            style = LocalAppTypography.current.headlineLarge.copy(
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold
            ),
            color = TextMain,
            maxLines = 2,
            textAlign = TextAlign.Center,
            overflow = TextOverflow.Ellipsis
        )

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = subtitle,
            style = LocalAppTypography.current.songArtist.copy(fontSize = 13.sp),
            color = TextSecondary,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Controls Row
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Primary Play Button (Stark White Pill)
            Surface(
                shape = CircleShape,
                color = ActiveControl,
                modifier = Modifier
                    .size(48.dp)
                    .clickable(onClick = onPlay)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Filled.PlayArrow,
                        contentDescription = "Play",
                        tint = TextOnActiveChip,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }

            // Shuffle Button (Graphite Circle)
            Surface(
                shape = CircleShape,
                color = BgSurfaceElevated,
                modifier = Modifier
                    .size(44.dp)
                    .clickable(onClick = onShuffle)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Filled.Shuffle,
                        contentDescription = "Shuffle",
                        tint = TextMain,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }

            // Export M3U8 Button
            if (onExportM3u != null) {
                Surface(
                    shape = CircleShape,
                    color = BgSurfaceElevated,
                    modifier = Modifier
                        .size(44.dp)
                        .clickable(onClick = onExportM3u)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Filled.Share,
                            contentDescription = "Export M3U8",
                            tint = TextSecondary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }

        // 2-Mode Toggle: Online Radio Discovery vs Play from Playlist
        if (onToggleRadioDiscoveryMode != null) {
            Spacer(modifier = Modifier.height(14.dp))
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = if (isRadioDiscoveryMode) Primary.copy(alpha = 0.2f) else BgSurfaceElevated,
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    if (isRadioDiscoveryMode) Primary else BorderChip
                ),
                modifier = Modifier.clickable(onClick = onToggleRadioDiscoveryMode)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (isRadioDiscoveryMode) Icons.Filled.Radio else Icons.Filled.QueueMusic,
                        contentDescription = null,
                        tint = if (isRadioDiscoveryMode) Primary else TextSecondary,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (isRadioDiscoveryMode) "Mode: Online Radio Discovery" else "Mode: Play from Playlist",
                        style = LocalAppTypography.current.chipText.copy(
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        ),
                        color = if (isRadioDiscoveryMode) Primary else TextMain
                    )
                }
            }
        }
    }
}

// Synthetic Liked Music Gradient Card (Zero Image Loading Overhead)
@Composable
fun YtLikedMusicCard(
    trackCount: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp)
            .padding(horizontal = 16.dp)
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Synthetic Gradient Heart Box
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(
                    brush = Brush.linearGradient(
                        colors = listOf(Color(0xFF5F2A8D), Color(0xFF3EA6FF))
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Filled.Favorite,
                contentDescription = "Liked Music",
                tint = Color.White,
                modifier = Modifier.size(22.dp)
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Liked Music",
                style = LocalAppTypography.current.songTitle.copy(fontSize = 14.sp),
                color = TextMain
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "Auto playlist • $trackCount songs",
                style = LocalAppTypography.current.songArtist.copy(fontSize = 12.sp),
                color = TextSecondary
            )
        }
    }
}
