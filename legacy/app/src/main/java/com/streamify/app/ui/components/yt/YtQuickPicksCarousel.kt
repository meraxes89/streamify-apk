package com.streamify.app.ui.components.yt
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.streamify.app.data.models.Track
import com.streamify.app.ui.theme.*
import com.streamify.app.ui.components.LocalContextMenuController
import com.streamify.app.ui.components.MenuOrigin
import com.streamify.app.ui.components.trackItemGestures

@Composable
fun YtQuickPicksCarousel(
    columns: List<List<Track>>, // Pre-chunked List<List<Track>> for zero GC allocations
    onTrackClick: (Track, List<Track>) -> Unit,
    onTrackMoreClick: ((Track) -> Unit)? = null,
    modifier: Modifier = Modifier,
    currentPlayingTrack: Track? = null,
    isBuffering: Boolean = false
) {
    val allTracksInCarousel = columns.flatten()

    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier.fillMaxWidth()
    ) {
        items(
            items = columns,
            key = { column -> column.firstOrNull()?.id ?: column.hashCode() },
            contentType = { "quickPickColumn" }
        ) { columnTracks ->
            Column(
                modifier = Modifier.width(320.dp)
            ) {
                columnTracks.forEach { track ->
                    val isCurrentlyBuffering = isBuffering && currentPlayingTrack != null && (
                        (currentPlayingTrack.id > 0 && currentPlayingTrack.id == track.id) ||
                        (currentPlayingTrack.title.equals(track.title, ignoreCase = true) && currentPlayingTrack.artist.equals(track.artist, ignoreCase = true))
                    )
                    YtCompactTrackRow(
                        track = track,
                        isLoading = isCurrentlyBuffering,
                        onClick = { onTrackClick(track, allTracksInCarousel) },
                        onMoreClick = { onTrackMoreClick?.invoke(track) }
                    )
                }
            }
        }
    }
}

@Composable
private fun YtCompactTrackRow(
    track: Track,
    isLoading: Boolean = false,
    onClick: () -> Unit,
    onMoreClick: () -> Unit
) {
    val contextMenuController = LocalContextMenuController.current

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .trackItemGestures(
                track = track,
                origin = MenuOrigin.HOME,
                controller = contextMenuController,
                onShortClick = onClick
            )
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        YtThumbnail(
            url = track.coverArtPath,
            size = 48.dp,
            cornerRadius = 4.dp,
            title = track.title,
            artist = track.artist,
            isLoading = isLoading
        )

        Spacer(modifier = Modifier.width(12.dp))

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = track.title,
                style = LocalAppTypography.current.songTitle.copy(fontSize = 14.sp),
                color = TextMain,
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
        }

        IconButton(
            onClick = {
                contextMenuController.show(track = track, origin = MenuOrigin.HOME)
                onMoreClick()
            },
            modifier = Modifier.size(32.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.MoreVert,
                contentDescription = "More",
                tint = TextSecondary,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}
