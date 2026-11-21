package com.streamify.shared.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.streamify.shared.model.PresentationTrack
import com.streamify.shared.model.formatPlaybackTime
import com.streamify.shared.theme.StreamifyPalette
import com.streamify.shared.theme.StreamifySpacing
import com.streamify.shared.theme.StreamifyTextStyles

/**
 * Phase 5 (MP-UI03 / Beta): the platform-agnostic track list row.
 *
 * Renders one [PresentationTrack] with a playing indicator (driven by the
 * engine push state, not local flags), optional trailing slot and an
 * optional index — the shared baseline both the desktop Library/Search
 * panes and Android hosts build their lists from, so row chrome is never
 * duplicated between platforms.
 */
@Composable
fun TrackListItem(
    track: PresentationTrack,
    isCurrent: Boolean = false,
    isPlaying: Boolean = false,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    index: Int? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp)
            .then(
                if (onClick != null) {
                    Modifier.clickable { onClick() }
                } else {
                    Modifier
                }
            )
            .padding(horizontal = StreamifySpacing.Lg),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(StreamifySpacing.Md),
    ) {
        if (index != null) {
            Text(
                text = "${index + 1}",
                style = StreamifyTextStyles.caption,
                color = StreamifyPalette.TextTertiary,
            )
        }

        // Leading slot: animated now-playing glyph for the current track,
        // brand note glyph otherwise.
        Box(
            modifier = Modifier.size(36.dp).clip(CircleShape)
                .background(StreamifyPalette.BgCard),
            contentAlignment = Alignment.Center,
        ) {
            if (isCurrent && isPlaying) {
                Icon(
                    imageVector = Icons.Filled.GraphicEq,
                    contentDescription = "Now playing",
                    tint = StreamifyPalette.Primary,
                    modifier = Modifier.size(20.dp),
                )
            } else {
                Icon(
                    imageVector = Icons.Filled.MusicNote,
                    contentDescription = null,
                    tint = if (isCurrent) {
                        StreamifyPalette.TextSecondary
                    } else {
                        StreamifyPalette.TextTertiary
                    },
                    modifier = Modifier.size(18.dp),
                )
            }
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = track.title,
                style = StreamifyTextStyles.songTitle,
                color = if (isCurrent) {
                    StreamifyPalette.ActiveControl
                } else {
                    StreamifyPalette.TextMain
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = track.artist,
                style = StreamifyTextStyles.songArtist,
                color = if (isCurrent) {
                    StreamifyPalette.TextSecondary
                } else {
                    StreamifyPalette.TextTertiary
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        if (trailing != null) {
            trailing()
        } else if (track.durationMs != null) {
            val statusColor = StreamifyPalette.TextTertiary
            Text(
                text = formatPlaybackTime(track.durationMs),
                style = StreamifyTextStyles.caption,
                color = statusColor,
            )
        }
    }
}
