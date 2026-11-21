package com.streamify.app.ui.components.yt
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
fun YtListenAgainGrid(
    columns: List<List<Track>>, // Pre-chunked pairs of 2
    onTrackClick: (Track, List<Track>) -> Unit,
    modifier: Modifier = Modifier,
    currentPlayingTrack: Track? = null,
    isBuffering: Boolean = false
) {
    val allTracks = columns.flatten()

    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier.fillMaxWidth()
    ) {
        items(
            items = columns,
            key = { column -> column.firstOrNull()?.id ?: column.hashCode() },
            contentType = { "listenAgainColumn" }
        ) { columnTracks ->
            Column(
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                columnTracks.forEach { track ->
                    val isCurrentlyBuffering = isBuffering && currentPlayingTrack != null && (
                        (currentPlayingTrack.id > 0 && currentPlayingTrack.id == track.id) ||
                        (currentPlayingTrack.title.equals(track.title, ignoreCase = true) && currentPlayingTrack.artist.equals(track.artist, ignoreCase = true))
                    )
                    YtMediumCard(
                        track = track,
                        isLoading = isCurrentlyBuffering,
                        onClick = { onTrackClick(track, allTracks) }
                    )
                }
            }
        }
    }
}

@Composable
private fun YtMediumCard(
    track: Track,
    isLoading: Boolean = false,
    onClick: () -> Unit
) {
    val contextMenuController = LocalContextMenuController.current

    Column(
        modifier = Modifier
            .width(120.dp)
            .trackItemGestures(
                track = track,
                origin = MenuOrigin.HOME,
                controller = contextMenuController,
                onShortClick = onClick
            )
    ) {
        YtThumbnail(
            url = track.coverArtPath,
            size = 120.dp,
            cornerRadius = 8.dp,
            title = track.title,
            artist = track.artist,
            isLoading = isLoading
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = track.title,
            style = LocalAppTypography.current.songTitle.copy(fontSize = 13.sp),
            color = TextMain,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = track.artist,
            style = LocalAppTypography.current.songArtist.copy(fontSize = 11.sp),
            color = TextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
