package com.streamify.app.ui.components.yt
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.streamify.app.data.models.Track
import com.streamify.app.ui.theme.*

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun YtQueueTrackItem(
    track: Track,
    isPlaying: Boolean,
    isActuallyPlaying: Boolean = true,
    isBuffering: Boolean = false,
    dragOffset: Float = 0f, // Handled for 120fps GPU movement
    onDragStart: (() -> Unit)? = null,
    onDragMove: ((Float) -> Unit)? = null,
    onDragEnd: (() -> Unit)? = null,
    onClick: () -> Unit,
    onMoreClick: () -> Unit,
    showDragHandle: Boolean = true,
    leadingBadge: String? = null,
    dimmed: Boolean = false,
    onReplay: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp)
            // Extreme Performance: GPU translation bypasses Compose layout/measure passes
            .graphicsLayer {
                translationY = dragOffset
                if (dimmed) alpha = 0.62f
            }
            .combinedClickable(
                onClick = onClick,
                onLongClick = {
                    com.streamify.app.util.StreamifyHapticEngine.magneticDetent()
                    onMoreClick()
                }
            )
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (leadingBadge != null) {
            Text(
                text = leadingBadge,
                style = androidx.compose.ui.text.TextStyle(
                    fontSize = 10.sp,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                ),
                color = com.streamify.app.ui.theme.TextTertiary,
                modifier = Modifier.width(30.dp)
            )
        }
        // Thumbnail / Equalizer Box
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(LocalAppShapes.current.thumbnailSmall)
        ) {
            YtThumbnail(
                url = track.coverArtPath,
                size = 48.dp,
                cornerRadius = 4.dp,
                title = track.title,
                artist = track.artist,
                isLoading = isBuffering && isPlaying
            )

            if (isPlaying && !isBuffering) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.55f)),
                    contentAlignment = Alignment.Center
                ) {
                    YtActiveEqualizer(isActive = isActuallyPlaying)
                }
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        // Title and Artist Column
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = track.title,
                style = LocalAppTypography.current.songTitle.copy(fontSize = 14.sp),
                color = if (isPlaying) ActiveControl else TextMain,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "${track.artist}${if (track.album.isNotBlank() && track.album != "Streamify") " • " + track.album else ""}",
                style = LocalAppTypography.current.songArtist.copy(fontSize = 12.sp),
                color = TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            // WS-D D4: the taste engine's explainable reason — queue rows
            // recommended by the local engine carry a one-line "why".
            if (!track.reason.isNullOrBlank()) {
                Text(
                    text = track.reason,
                    style = LocalAppTypography.current.songArtist.copy(fontSize = 10.sp),
                    color = TextTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        // 3-Dot Overflow Menu
        IconButton(
            onClick = onMoreClick,
            modifier = Modifier.size(32.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.MoreVert,
                contentDescription = "Options",
                tint = TextSecondary,
                modifier = Modifier.size(18.dp)
            )
        }

        // Drag Handle (Long-press to reorder)
        if (showDragHandle && onDragStart != null && onDragMove != null && onDragEnd != null) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .pointerInput(track.id) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = { onDragStart() },
                            onDragEnd = { onDragEnd() },
                            onDragCancel = { onDragEnd() },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                onDragMove(dragAmount.y)
                            }
                        )
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.DragHandle,
                    contentDescription = "Drag to reorder",
                    tint = TextSecondary,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}
