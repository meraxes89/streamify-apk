package com.streamify.app.ui.components.yt
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.streamify.app.data.network.YouTubeStreamResolver
import com.streamify.app.ui.theme.*

/**
 * List-optimized thumbnail.
 *
 * PERF CONTRACT: uses plain AsyncImage (NO SubcomposeAsyncImage). A
 * subcomposition per cell was the classic library/search fling-jank source —
 * every thumbnail paid an extra composer + slot table + measure pass. The
 * MusicNote placeholder is a permanent layer UNDER the image, so nothing is
 * swapped in composition while loading. Crossfade is disabled per-cell: alpha
 * layers on dozens of tiny cells measurably cost raster time on low-end GPUs
 * during fast scroll.
 */
@Composable
fun YtThumbnail(
    url: String?,
    size: Dp,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 4.dp,
    title: String = "",
    artist: String = "",
    videoId: String? = null,
    isLoading: Boolean = false
) {
    val shape = RoundedCornerShape(cornerRadius)

    val resolvedVideoId = remember(url, videoId) {
        videoId ?: YouTubeStreamResolver.extractVideoId(url ?: "")
    }

    val displayUrl = remember(url, resolvedVideoId) {
        if (!url.isNullOrBlank()) {
            YouTubeStreamResolver.sanitizeCoverUrl(url, resolvedVideoId)
        } else if (!resolvedVideoId.isNullOrBlank()) {
            "https://i.ytimg.com/vi/$resolvedVideoId/sddefault.jpg"
        } else {
            null
        }
    }

    // One-shot downgrade path on load failure: primary -> hqdefault -> icon.
    var downgraded by remember(displayUrl) { mutableStateOf(false) }
    val effectiveUrl = remember(displayUrl, resolvedVideoId, downgraded) {
        when {
            !downgraded && !displayUrl.isNullOrBlank() -> displayUrl
            resolvedVideoId != null -> "https://i.ytimg.com/vi/$resolvedVideoId/hqdefault.jpg"
            else -> null
        }
    }

    Surface(
        color = BgCard,
        shape = shape,
        modifier = modifier
            .size(size)
            .clip(shape)
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            // Permanent placeholder layer (visible until pixels arrive, and
            // again if every candidate URL fails).
            Icon(
                imageVector = Icons.Filled.MusicNote,
                contentDescription = null,
                tint = TextTertiary,
                modifier = Modifier.size(size * 0.45f)
            )

            if (effectiveUrl != null) {
                val context = androidx.compose.ui.platform.LocalContext.current
                val density = androidx.compose.ui.platform.LocalDensity.current
                val targetPx = remember(size, density) { with(density) { size.roundToPx() } }
                val request = remember(effectiveUrl, targetPx) {
                    coil.request.ImageRequest.Builder(context)
                        .data(effectiveUrl)
                        .size(targetPx, targetPx)
                        .crossfade(false)
                        .diskCachePolicy(coil.request.CachePolicy.ENABLED)
                        .memoryCachePolicy(coil.request.CachePolicy.ENABLED)
                        .build()
                }
                AsyncImage(
                    model = request,
                    contentDescription = title.ifBlank { null },
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(shape),
                    onError = {
                        // Escalate once per URL; the second failure leaves the
                        // placeholder icon visible. No infinite retry loop.
                        if (!downgraded) downgraded = true
                    }
                )
            }

            if (isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.52f)),
                    contentAlignment = Alignment.Center
                ) {
                    androidx.compose.material3.CircularProgressIndicator(
                        modifier = Modifier.size((size * 0.38f).coerceIn(18.dp, 32.dp)),
                        strokeWidth = 2.5.dp,
                        color = Primary,
                        trackColor = Primary.copy(alpha = 0.2f)
                    )
                }
            }
        }
    }
}
