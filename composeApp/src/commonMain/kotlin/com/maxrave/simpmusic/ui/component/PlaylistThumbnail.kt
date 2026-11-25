package com.maxrave.simpmusic.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.maxrave.domain.data.entities.LocalPlaylistEntity
import com.maxrave.logger.Logger
import com.maxrave.simpmusic.expect.ui.toImageBitmap
import com.maxrave.simpmusic.ui.icon.QueueMusic
import com.maxrave.simpmusic.ui.icon.SimpIcons
import com.maxrave.simpmusic.ui.theme.ArtworkShape
import org.jetbrains.compose.resources.painterResource
import simpmusic.composeapp.generated.resources.Res
import simpmusic.composeapp.generated.resources.monochrome
import kotlin.math.abs

sealed interface PlaylistArtLayout {
    data class Single(val url: String) : PlaylistArtLayout
    data class Quad(val urls: List<String>) : PlaylistArtLayout
    data object Empty : PlaylistArtLayout
}

fun resolvePlaylistArtLayout(
    customThumbnail: String?,
    trackIds: List<String>?,
    resolvedThumbnails: List<String>? = null,
): PlaylistArtLayout {
    if (!customThumbnail.isNullOrBlank()) {
        return PlaylistArtLayout.Single(customThumbnail)
    }
    val candidateUrls = resolvedThumbnails?.filter { it.isNotBlank() }
        ?.ifEmpty { null }
        ?: trackIds.orEmpty().filter { it.isNotBlank() }.map { "https://i.ytimg.com/vi/$it/hqdefault.jpg" }

    val distinctUrls = candidateUrls.distinct()
    return when {
        distinctUrls.size >= 4 -> PlaylistArtLayout.Quad(distinctUrls.take(4))
        distinctUrls.isNotEmpty() -> PlaylistArtLayout.Single(distinctUrls.first())
        else -> PlaylistArtLayout.Empty
    }
}

/**
 * Universal dynamic playlist artwork following Spotify and Apple Music standards:
 * - Explicit custom thumbnail: 100% full-bleed.
 * - 4+ tracks: 2x2 quad-grid collage with micro-seam separator.
 * - 1..3 tracks: 100% full-bleed first track artwork.
 * - 0 tracks: Generative ambient monogram with title-derived gradient and music badge.
 */
@Composable
fun PlaylistArtwork(
    title: String,
    thumbnailUrl: String?,
    trackIds: List<String>?,
    modifier: Modifier = Modifier,
    shape: Shape = ArtworkShape,
    resolvedThumbnails: List<String>? = null,
    onSuccessBitmap: ((ImageBitmap) -> Unit)? = null,
) {
    val layout = remember(thumbnailUrl, trackIds, resolvedThumbnails) {
        resolvePlaylistArtLayout(
            customThumbnail = thumbnailUrl,
            trackIds = trackIds,
            resolvedThumbnails = resolvedThumbnails,
        )
    }

    Box(
        modifier = modifier.clip(shape),
    ) {
        when (layout) {
            is PlaylistArtLayout.Single -> {
                SingleArtworkCover(
                    url = layout.url,
                    title = title,
                    onSuccessBitmap = onSuccessBitmap,
                )
            }

            is PlaylistArtLayout.Quad -> {
                QuadCollageCover(
                    urls = layout.urls,
                    onPrimaryBitmap = onSuccessBitmap,
                )
            }

            is PlaylistArtLayout.Empty -> {
                EmptyPlaylistCover(
                    title = title,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

@Composable
fun PlaylistArtwork(
    playlist: LocalPlaylistEntity,
    modifier: Modifier = Modifier,
    shape: Shape = ArtworkShape,
    resolvedThumbnails: List<String>? = null,
    onSuccessBitmap: ((ImageBitmap) -> Unit)? = null,
) {
    PlaylistArtwork(
        title = playlist.title,
        thumbnailUrl = playlist.thumbnail,
        trackIds = playlist.tracks,
        modifier = modifier,
        shape = shape,
        resolvedThumbnails = resolvedThumbnails,
        onSuccessBitmap = onSuccessBitmap,
    )
}

@Composable
private fun SingleArtworkCover(
    url: String,
    title: String,
    modifier: Modifier = Modifier,
    onSuccessBitmap: ((ImageBitmap) -> Unit)? = null,
) {
    val context = LocalPlatformContext.current
    val fallback = rememberHolderPainter(square = true)
    val imageRequest = remember(url, context) {
        ImageRequest.Builder(context)
            .data(url)
            .size(512, 512)
            .diskCachePolicy(CachePolicy.ENABLED)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .crossfade(200)
            .build()
    }
    AsyncImage(
        model = imageRequest,
        contentDescription = title,
        contentScale = ContentScale.Crop,
        modifier = modifier.fillMaxSize(),
        onSuccess = { onSuccessBitmap?.invoke(it.result.image.toImageBitmap()) },
        placeholder = fallback,
        error = fallback,
        fallback = fallback,
    )
}

@Composable
private fun QuadCollageCover(
    urls: List<String>,
    modifier: Modifier = Modifier,
    onPrimaryBitmap: ((ImageBitmap) -> Unit)? = null,
) {
    val context = LocalPlatformContext.current
    val seamColor = Color.Black.copy(alpha = 0.30f)
    val seamWidth = 0.75.dp
    val fallback = rememberHolderPainter(square = true)

    val url0 = urls.getOrNull(0)
    val url1 = urls.getOrNull(1)
    val url2 = urls.getOrNull(2)
    val url3 = urls.getOrNull(3)

    val req0 = remember(url0, context) {
        ImageRequest.Builder(context)
            .data(url0)
            .size(256, 256)
            .diskCachePolicy(CachePolicy.ENABLED)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .crossfade(150)
            .build()
    }
    val req1 = remember(url1, context) {
        ImageRequest.Builder(context)
            .data(url1)
            .size(256, 256)
            .diskCachePolicy(CachePolicy.ENABLED)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .crossfade(150)
            .build()
    }
    val req2 = remember(url2, context) {
        ImageRequest.Builder(context)
            .data(url2)
            .size(256, 256)
            .diskCachePolicy(CachePolicy.ENABLED)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .crossfade(150)
            .build()
    }
    val req3 = remember(url3, context) {
        ImageRequest.Builder(context)
            .data(url3)
            .size(256, 256)
            .diskCachePolicy(CachePolicy.ENABLED)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .crossfade(150)
            .build()
    }

    Column(modifier = modifier.fillMaxSize()) {
        Row(modifier = Modifier.fillMaxWidth().weight(1f)) {
            Box(modifier = Modifier.fillMaxHeight().weight(1f)) {
                AsyncImage(
                    model = req0,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                    onSuccess = { onPrimaryBitmap?.invoke(it.result.image.toImageBitmap()) },
                    placeholder = fallback,
                    error = fallback,
                    fallback = fallback,
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(seamWidth)
                    .background(seamColor),
            )
            Box(modifier = Modifier.fillMaxHeight().weight(1f)) {
                AsyncImage(
                    model = req1,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                    placeholder = fallback,
                    error = fallback,
                    fallback = fallback,
                )
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(seamWidth)
                .background(seamColor),
        )
        Row(modifier = Modifier.fillMaxWidth().weight(1f)) {
            Box(modifier = Modifier.fillMaxHeight().weight(1f)) {
                AsyncImage(
                    model = req2,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                    placeholder = fallback,
                    error = fallback,
                    fallback = fallback,
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(seamWidth)
                    .background(seamColor),
            )
            Box(modifier = Modifier.fillMaxHeight().weight(1f)) {
                AsyncImage(
                    model = req3,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                    placeholder = fallback,
                    error = fallback,
                    fallback = fallback,
                )
            }
        }
    }
}

@Composable
private fun EmptyPlaylistCover(
    title: String,
    modifier: Modifier = Modifier,
) {
    val linearBrush = remember(title) {
        Brush.linearGradient(
            colors = playlistTitleGradient(title),
            start = Offset.Zero,
            end = Offset.Infinite,
        )
    }
    val radialBrush = remember {
        Brush.radialGradient(
            colors = listOf(
                Color.Transparent,
                Color.Black.copy(alpha = 0.35f),
            ),
        )
    }
    BoxWithConstraints(
        modifier = modifier
            .background(brush = linearBrush),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(brush = radialBrush),
        )

        val isTiny = maxWidth < 56.dp
        val isSmall = maxWidth < 100.dp
        val badgeSize = when {
            isTiny -> 24.dp
            isSmall -> 36.dp
            maxWidth < 200.dp -> 48.dp
            else -> 72.dp
        }
        val iconSize = when {
            isTiny -> 14.dp
            isSmall -> 20.dp
            maxWidth < 200.dp -> 26.dp
            else -> 38.dp
        }

        Box(
            modifier = Modifier
                .size(badgeSize)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.18f))
                .border(
                    width = 1.dp,
                    color = Color.White.copy(alpha = 0.28f),
                    shape = CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = SimpIcons.QueueMusic,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(iconSize),
            )
        }
    }
}

@Composable
fun PlaylistThumbnail(
    title: String,
    modifier: Modifier = Modifier,
) {
    val gradientBrush = remember(title) {
        Brush.linearGradient(
            colors = generateGradientFromTitle(title),
        )
    }
    Box(
        modifier = modifier
            .background(brush = gradientBrush)
    ) {

        // Title in bottom right
        Text(
            text = title,
            color = Color.White,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(16.dp)
        )
    }
}

// Generate deterministic gradient colors from title
internal fun generateGradientFromTitle(title: String): List<Color> {
    val hash = title.hashCode()

    // Extract RGB components from hash
    val hue1 = ((hash and 0xFF) / 255f) * 360f
    val hue2 = (((hash shr 8) and 0xFF) / 255f) * 360f

    // Convert HSV to RGB for vibrant colors
    val color1 = hsvToColor(hue1, 0.7f, 0.9f)
    val color2 = hsvToColor(hue2, 0.7f, 0.85f)

    return listOf(color1, color2)
}

/**
 * Deterministic placeholder gradient colors for a playlist title — matches the
 * [PlaylistThumbnail] / [painterPlaylistThumbnail] placeholder, so a screen can tint
 * its background to match a local playlist that has no real artwork.
 */
fun playlistTitleGradient(title: String): List<Color> = generateGradientFromTitle(title)

// HSV to RGB conversion
private fun hsvToColor(hue: Float, saturation: Float, value: Float): Color {
    val h = hue / 60f
    val c = value * saturation
    val x = c * (1 - abs((h % 2) - 1))
    val m = value - c

    val (r, g, b) = when (h.toInt()) {
        0 -> Triple(c, x, 0f)
        1 -> Triple(x, c, 0f)
        2 -> Triple(0f, c, x)
        3 -> Triple(0f, x, c)
        4 -> Triple(x, 0f, c)
        else -> Triple(c, 0f, x)
    }

    return Color(
        red = (r + m),
        green = (g + m),
        blue = (b + m),
        alpha = 1f
    )
}

private class PlaylistThumbnailPainter(
    private val size: Size,
    private val title: String,
    private val textLayoutResult: TextLayoutResult,
    private val iconPainter: Painter
): Painter() {
    override val intrinsicSize: Size
        get() = size

    override fun DrawScope.onDraw() {
        val colors = generateGradientFromTitle(title)
        drawRoundRect(
            brush = Brush.linearGradient(
                colors = colors
            ),
            size = size,
            cornerRadius = CornerRadius(16f, 16f),
        )
        drawText(textLayoutResult, topLeft = calculateTopLeft(
            alignment = Alignment.BottomStart,
            textSize = IntSize(
                width = textLayoutResult.size.width,
                height = textLayoutResult.size.height,
            ),
            containerSize = IntSize(
                size.width.toInt(),
                size.height.toInt(),
            ),
        ))
        val centerX = size.width * 0.9f
        val centerY = size.height * 0.1f
        val circleRadius = size.width * 0.1f / 2

        drawCircle(
            center = Offset(centerX, centerY),
            color = Color.White,
            radius = circleRadius
        )

        translate(
            left = centerX - size.width * 0.15f / 2,
            top = centerY - size.height * 0.15f / 2,
        ) {
            with(iconPainter) {
                draw(size * 0.15f, alpha = 0.2f)
            }
        }
    }
}

private fun calculateTopLeft(
    alignment: Alignment,
    textSize: IntSize,
    containerSize: IntSize,
): Offset {
    val alignmentOffset =
        alignment.align(
            size = textSize.let { (width, height) ->
                IntSize(
                    width = width,
                    height = height,
                )
            },
            space = containerSize,
            layoutDirection = LayoutDirection.Ltr,
        )

    return Offset(
        x = alignmentOffset.x.toFloat() + 16f,
        y = alignmentOffset.y.toFloat() - 16f,
    )
}

@Composable
fun painterPlaylistThumbnail(
    title: String,
    style: TextStyle,
    sizeDp: Pair<Dp, Dp>,
): Painter {
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()
    val textLayoutResult =
        remember(title, style, sizeDp) {
            textMeasurer.measure(
                title,
                style.copy(
                    color = Color.White,
                    textAlign = TextAlign.Start
                ),
                maxLines = 2,
                softWrap = true,
                overflow = TextOverflow.Ellipsis,
                layoutDirection = LayoutDirection.Ltr,
                constraints =
                    Constraints(
                        maxWidth = with(density) { (sizeDp.first - 32.dp).toPx().toInt() },
                        maxHeight = with(density) { (sizeDp.second - 32.dp).toPx().toInt() },
                    ),
            )
        }
    val painterRes = painterResource(Res.drawable.monochrome)
    return remember(density, sizeDp, title, textLayoutResult, painterRes) {
        PlaylistThumbnailPainter(
            size = Size(
                width = with(density) { sizeDp.first.toPx() },
                height = with(density) { sizeDp.second.toPx() },
            ),
            title = title,
            textLayoutResult = textLayoutResult,
            iconPainter = painterRes
        )
    }
}