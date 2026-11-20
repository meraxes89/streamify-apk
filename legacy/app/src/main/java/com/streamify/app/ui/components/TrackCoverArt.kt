package com.streamify.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.streamify.app.ui.theme.StreamifyShapes

@Composable
fun TrackCoverArt(
    coverArtPath: String?,
    title: String,
    artist: String,
    modifier: Modifier = Modifier,
    shape: Shape = StreamifyShapes.CardShape,
    sizeDp: Int = 0
) {
    val descriptor = remember(coverArtPath, title, artist) {
        val vid = com.streamify.app.data.network.YouTubeStreamResolver.extractVideoId(coverArtPath ?: "")
        com.streamify.app.data.network.YouTubeStreamResolver.buildThumbnailPipeline(
            rawUrl = coverArtPath,
            videoId = vid,
            title = title,
            artist = artist
        )
    }

    val context = androidx.compose.ui.platform.LocalContext.current

    val fallbackColors = remember(descriptor.fallbackColorSeed) {
        val seed = descriptor.fallbackColorSeed
        val hue1 = kotlin.math.abs(seed % 360).toFloat()
        val hue2 = kotlin.math.abs((seed * 31) % 360).toFloat()
        listOf(
            Color.hsl(hue1, 0.65f, 0.35f),
            Color.hsl(hue2, 0.70f, 0.15f)
        )
    }

    // PERF: no subcomposition. The generative cover is a PERMANENT layer
    // beneath the image; AsyncImage simply paints over it when pixels land.
    // Failure walks primary -> secondary -> generative with zero recompose
    // churn beyond one state step per downgrade.
    var stage by remember(descriptor.primary, descriptor.secondary) { mutableStateOf(0) }
    val candidateUrl = when (stage) {
        0 -> descriptor.primary?.takeIf { it.isNotBlank() }
        1 -> descriptor.secondary?.takeIf { it.isNotBlank() && it != descriptor.primary }
        else -> null
    }

    Box(
        modifier = modifier
            .clip(shape)
            .background(Brush.linearGradient(fallbackColors))
    ) {
        GenerativeCoverContent(title = title, artist = artist)

        if (candidateUrl != null) {
            val density = androidx.compose.ui.platform.LocalDensity.current
            val targetPx = remember(sizeDp, density) { if (sizeDp > 0) with(density) { sizeDp.dp.roundToPx() } else 0 }
            val request = remember(candidateUrl, targetPx) {
                coil.request.ImageRequest.Builder(context)
                    .data(candidateUrl)
                    .apply {
                        if (targetPx > 0) size(targetPx, targetPx)
                    }
                    .crossfade(false)
                    .diskCachePolicy(coil.request.CachePolicy.ENABLED)
                    .memoryCachePolicy(coil.request.CachePolicy.ENABLED)
                    .build()
            }
            AsyncImage(
                model = request,
                contentDescription = "$title cover",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
                onError = { if (stage < 2) stage++ }
            )
        }
    }
}

@Composable
private fun GenerativeCoverContent(
    title: String,
    artist: String
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(4.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.MusicNote,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.85f),
                modifier = Modifier.weight(1f, fill = false)
            )
            Text(
                text = title,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
            if (artist.isNotBlank() && artist != "Unknown") {
                Text(
                    text = artist,
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 9.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}
