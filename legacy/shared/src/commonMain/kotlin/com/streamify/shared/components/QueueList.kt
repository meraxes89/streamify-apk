package com.streamify.shared.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.streamify.shared.model.PresentationTrack
import com.streamify.shared.theme.StreamifyPalette
import com.streamify.shared.theme.StreamifySpacing
import com.streamify.shared.theme.StreamifyTextStyles

/**
 * Phase 5 (MP-UI03 / Beta): the platform-agnostic queue list — the shared
 * "Queue list" media-control surface of the directive contract.
 *
 * DATA-SOURCE DISCIPLINE: the sovereign Rust core pushes playback SESSION
 * snapshots (status, volume, DAC clock, buffer, jam mirror) — it does not
 * own the play QUEUE (that surface arrives with the storage/catalog
 * contract). Each platform therefore feeds this component its own queue
 * truth as plain [PresentationTrack] data: the Android host lowers its
 * Media3 session queue onto it, the desktop/iOS shells render whatever
 * their lane can supply. The component itself stays pure presentation —
 * it renders exactly what it is given and an honest empty state when the
 * platform has no queue source, never a fabricated list.
 */
data class QueueListState(
    /** Platform-supplied queue, play order. */
    val tracks: List<PresentationTrack> = emptyList(),
    /** Index of the playing track; -1 when no track is active. */
    val currentIndex: Int = -1,
    /** Whether the current track is actively playing (drives the glyph). */
    val isPlaying: Boolean = false,
) {
    /** The active track, when the index points inside the list. */
    val current: PresentationTrack?
        get() = tracks.getOrNull(currentIndex)

    /** Queue entries after the current track, in play order. */
    val upNext: List<Pair<Int, PresentationTrack>>
        get() = tracks.withIndex()
            .filter { (index, _) -> index > currentIndex }
            .map { (index, track) -> index to track }

    /** Queue entries before the current track, in play order. */
    val played: List<Pair<Int, PresentationTrack>>
        get() = tracks.withIndex()
            .filter { (index, _) -> currentIndex >= 0 && index < currentIndex }
            .map { (index, track) -> index to track }

    companion object {
        val EMPTY = QueueListState()
    }
}

/**
 * Renders one platform queue as the shared "Now playing / Up next" surface.
 * Rows are [TrackListItem]s (the shared row chrome — one implementation,
 * every platform); tapping a row reports the QUEUE INDEX, which the host
 * lowers onto its own play-at-index surface.
 */
@Composable
fun QueueList(
    state: QueueListState,
    modifier: Modifier = Modifier,
    onTrackClick: ((queueIndex: Int) -> Unit)? = null,
    emptyStateText: String = "The queue lights up once a platform queue source is connected.",
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(StreamifySpacing.Xs),
    ) {
        if (state.tracks.isEmpty()) {
            QueueEmptyState(text = emptyStateText)
            return@Column
        }

        state.current?.let { current ->
            QueueSectionLabel(text = "Now playing")
            TrackListItem(
                track = current,
                isCurrent = true,
                isPlaying = state.isPlaying,
                onClick = onTrackClick?.let { callback -> { callback(state.currentIndex) } },
            )
        }

        if (state.upNext.isNotEmpty()) {
            QueueSectionLabel(text = "Up next")
            state.upNext.forEach { (index, track) ->
                TrackListItem(
                    track = track,
                    isCurrent = false,
                    isPlaying = false,
                    index = index,
                    onClick = onTrackClick?.let { callback -> { callback(index) } },
                )
            }
        }
    }
}

@Composable
private fun QueueSectionLabel(text: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(
            start = StreamifySpacing.Lg,
            top = StreamifySpacing.Sm,
        ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(StreamifySpacing.Xs),
    ) {
        Icon(
            imageVector = Icons.Filled.QueueMusic,
            contentDescription = null,
            tint = StreamifyPalette.TextTertiary,
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = text,
            style = StreamifyTextStyles.caption,
            color = StreamifyPalette.TextTertiary,
        )
    }
}

@Composable
private fun QueueEmptyState(text: String) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(StreamifySpacing.Lg),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(StreamifySpacing.Sm),
    ) {
        Icon(
            imageVector = Icons.Filled.QueueMusic,
            contentDescription = null,
            tint = StreamifyPalette.TextTertiary,
            modifier = Modifier.size(28.dp),
        )
        Text(
            text = text,
            style = StreamifyTextStyles.caption,
            color = StreamifyPalette.TextTertiary,
        )
    }
}
