@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.streamify.app.ui.components.yt

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.streamify.app.data.models.Track
import com.streamify.app.ui.theme.*

/**
 * YtDailyMixRail — Gap #26's Daily Mixes 1–6: automated artist-cluster
 * carousels on Home. Each card is a numbered mix seeded by one cluster;
 * tapping plays the mix, long-press opens the track context menu.
 */
data class DailyMixModel(
    val number: Int,
    val seedArtist: String,
    val tracks: List<Track>
) {
    val coverUrl: String? get() = tracks.firstOrNull { !it.coverArtPath.isNullOrBlank() }?.coverArtPath
    val trackCount: Int get() = tracks.size
}

private val MIX_GRADIENTS = listOf(
    0xFF1E3A8A to 0xFF7C3AED,
    0xFF0F766E to 0xFF22D3EE,
    0xFF9D174D to 0xFFF472B6,
    0xFF7C2D12 to 0xFFFBBF24,
    0xFF365314 to 0xFF84CC16,
    0xFF1E293B to 0xFF38BDF8
)

@Composable
fun YtDailyMixCard(
    mix: DailyMixModel,
    onPlayMix: () -> Unit,
    onLongPress: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val gradient = MIX_GRADIENTS[(mix.number - 1).coerceIn(0, MIX_GRADIENTS.size - 1)]
    val cover = mix.coverUrl

    Box(
        modifier = modifier
            .width(150.dp)
            .combinedClickable(onClick = onPlayMix, onLongClick = onLongPress)
    ) {
        Column {
            Box(
                modifier = Modifier
                    .size(150.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(
                        Brush.linearGradient(
                            listOf(Color(gradient.first), Color(gradient.second))
                        )
                    )
            ) {
                if (cover != null) {
                    AsyncImage(
                        model = cover,
                        contentDescription = mix.seedArtist,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(14.dp))
                    )
                } else {
                    // Textural fallback when the cluster has no artwork.
                    Icon(
                        imageVector = Icons.Filled.MusicNote,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.55f),
                        modifier = Modifier
                            .align(Alignment.Center)
                            .size(44.dp)
                    )
                }

                // Numbered badge — the Daily Mix identity.
                Surface(
                    color = Color.Black.copy(alpha = 0.55f),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp)
                ) {
                    Text(
                        text = "${mix.number}",
                        style = LocalAppTypography.current.headlineMedium.copy(
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Black
                        ),
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Daily Mix ${mix.number}",
                style = LocalAppTypography.current.songTitle.copy(
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                ),
                color = TextMain,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "${mix.seedArtist} • ${mix.trackCount} tracks",
                style = LocalAppTypography.current.songArtist.copy(fontSize = 11.sp),
                color = TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
