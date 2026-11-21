package com.streamify.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.streamify.app.data.models.Track
import com.streamify.app.ui.theme.StreamifyColors
import com.streamify.app.ui.theme.StreamifyDimens
import com.streamify.app.ui.theme.StreamifyShapes
import com.streamify.app.ui.theme.StreamifyType

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun TrackListItem(
    track: Track,
    onClick: () -> Unit,
    onOptionsClick: () -> Unit,
    modifier: Modifier = Modifier,
    isCurrentTrack: Boolean = false,
    isActuallyPlaying: Boolean = true,
    isBuffering: Boolean = false
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(StreamifyDimens.TrackRowHeight)
            .combinedClickable(
                onClick = onClick,
                onLongClick = {
                    com.streamify.app.util.StreamifyHapticEngine.magneticDetent()
                    onOptionsClick()
                }
            )
            .padding(horizontal = StreamifyDimens.SpaceLG),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier.size(StreamifyDimens.TrackRowArt),
            contentAlignment = Alignment.Center
        ) {
            TrackCoverArt(
                coverArtPath = track.coverArtPath,
                title = track.title,
                artist = track.artist,
                modifier = Modifier.fillMaxSize(),
                sizeDp = StreamifyDimens.TrackRowArt.value.toInt(),
                shape = StreamifyShapes.MiniPlayerShape
            )
            if (isCurrentTrack) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(StreamifyColors.BgBase.copy(alpha = 0.6f), StreamifyShapes.MiniPlayerShape),
                    contentAlignment = Alignment.Center
                ) {
                    if (isBuffering) {
                        androidx.compose.material3.CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            color = StreamifyColors.Primary,
                            strokeWidth = 2.dp,
                            strokeCap = androidx.compose.ui.graphics.StrokeCap.Round
                        )
                    } else {
                        NowPlayingIndicator(isActive = isActuallyPlaying)
                    }
                }
            }
        }
        Spacer(modifier = Modifier.width(StreamifyDimens.SpaceMD))
        
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = track.title,
                style = StreamifyType.TitleMedium,
                color = if (isCurrentTrack) StreamifyColors.Primary else StreamifyColors.TextMain,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = track.artist,
                    style = StreamifyType.BodyMedium,
                    color = StreamifyColors.TextSub,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (track.album.equals("Streamify", ignoreCase = true) || track.source.contains("streamify", ignoreCase = true) || track.filepath.lowercase().contains("streamify")) {
                    Spacer(modifier = Modifier.width(StreamifyDimens.SpaceXS))
                    androidx.compose.material3.Surface(
                        color = StreamifyColors.Primary.copy(alpha = 0.15f),
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            text = "Streamify",
                            style = StreamifyType.Caption,
                            color = StreamifyColors.Primary,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                        )
                    }
                } else if (track.isProcessed && track.bpm > 0f) {
                    Spacer(modifier = Modifier.width(StreamifyDimens.SpaceXS))
                    androidx.compose.material3.Surface(
                        color = StreamifyColors.Primary.copy(alpha = 0.15f),
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            text = "${track.bpm.toInt()} BPM",
                            style = StreamifyType.Caption,
                            color = StreamifyColors.Primary,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                        )
                    }
                } else if (track.id > 0 && !track.isProcessed) {
                    Spacer(modifier = Modifier.width(StreamifyDimens.SpaceXS))
                    androidx.compose.material3.Icon(
                        imageVector = androidx.compose.material.icons.Icons.Filled.Schedule,
                        contentDescription = "Pending Analysis",
                        tint = StreamifyColors.TextDimmed,
                        modifier = Modifier.size(13.dp)
                    )
                }
            }
        }
        
        IconButton(onClick = onOptionsClick) {
            Icon(
                imageVector = Icons.Filled.MoreVert,
                contentDescription = "More options",
                tint = StreamifyColors.TextSub
            )
        }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun SwipeableTrackListItem(
    track: Track,
    onClick: () -> Unit,
    onOptionsClick: () -> Unit,
    onSwipeQueue: (() -> Unit)? = null,
    onSwipeLike: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    isCurrentTrack: Boolean = false,
    isActuallyPlaying: Boolean = true,
    isBuffering: Boolean = false
) {
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    val dismissState = androidx.compose.material3.rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == androidx.compose.material3.SwipeToDismissBoxValue.StartToEnd) {
                haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                onSwipeQueue?.invoke()
                false
            } else if (value == androidx.compose.material3.SwipeToDismissBoxValue.EndToStart) {
                haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                onSwipeLike?.invoke()
                false
            } else false
        }
    )

    androidx.compose.material3.SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = onSwipeQueue != null,
        enableDismissFromEndToStart = onSwipeLike != null,
        backgroundContent = {
            val direction = dismissState.dismissDirection
            val color = when (direction) {
                androidx.compose.material3.SwipeToDismissBoxValue.StartToEnd -> StreamifyColors.Primary
                androidx.compose.material3.SwipeToDismissBoxValue.EndToStart -> androidx.compose.ui.graphics.Color(0xFFE91E63)
                else -> androidx.compose.ui.graphics.Color.Transparent
            }
            val alignment = when (direction) {
                androidx.compose.material3.SwipeToDismissBoxValue.StartToEnd -> Alignment.CenterStart
                androidx.compose.material3.SwipeToDismissBoxValue.EndToStart -> Alignment.CenterEnd
                else -> Alignment.Center
            }
            val icon = when (direction) {
                androidx.compose.material3.SwipeToDismissBoxValue.StartToEnd -> Icons.Filled.QueueMusic
                androidx.compose.material3.SwipeToDismissBoxValue.EndToStart -> Icons.Filled.Favorite
                else -> Icons.Filled.QueueMusic
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(color.copy(alpha = 0.25f))
                    .padding(horizontal = 24.dp),
                contentAlignment = alignment
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = color,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    ) {
        TrackListItem(
            track = track,
            onClick = onClick,
            onOptionsClick = onOptionsClick,
            modifier = modifier.background(StreamifyColors.BgBase),
            isCurrentTrack = isCurrentTrack,
            isActuallyPlaying = isActuallyPlaying,
            isBuffering = isBuffering
        )
    }
}

