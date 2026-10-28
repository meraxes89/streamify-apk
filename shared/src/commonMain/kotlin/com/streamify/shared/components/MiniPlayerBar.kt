package com.streamify.shared.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.streamify.shared.model.PlaybackStatus
import com.streamify.shared.model.PresentationTrack
import com.streamify.shared.model.formatPlaybackTime
import com.streamify.shared.model.progressFraction
import com.streamify.shared.theme.StreamifyPalette
import com.streamify.shared.theme.StreamifySpacing
import com.streamify.shared.theme.StreamifyTextStyles

/**
 * Phase 5 (MP-UI03 / Beta): the platform-agnostic docked mini player.
 *
 * The shared baseline of the app's Android MiniPlayerBar: transport
 * controls, track title/artist, DAC-clock-derived position and a
 * micro-progress strip, rendered from [MiniPlayerBarState] + callbacks so
 * the SAME composable serves the desktop shell dock and any Android host
 * without duplicating UI code between platforms. Platform-specific
 * flourish (liquid morph, haptics, swipe-to-skip) stays in the Android
 * app's own component — this is the common contract both can build on.
 *
 * Degradation is total, never fabricated: with no track the bar renders a
 * control-plane placeholder; with no DAC clock it hides the position; with
 * no duration the progress strip stays quiet (indeterminate baseline).
 */
data class MiniPlayerBarState(
    val status: PlaybackStatus = PlaybackStatus.IDLE,
    val track: PresentationTrack? = null,
    /** Hardware-derived position (MP-DEC-03); null until the first DAC sample. */
    val positionMs: Long? = null,
)

@Composable
fun MiniPlayerBar(
    state: MiniPlayerBarState,
    onTogglePlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    modifier: Modifier = Modifier,
    onExpand: (() -> Unit)? = null,
) {
    val track = state.track
    val fraction = progressFraction(state.positionMs, track?.durationMs)

    Surface(
        color = StreamifyPalette.BgSurfaceElevated,
        modifier = modifier.fillMaxWidth().height(StreamifySpacing.MiniPlayerHeight),
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxSize().padding(
                    start = StreamifySpacing.Sm,
                    end = StreamifySpacing.Xs,
                ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TrackBadge(track = track)

                Spacer(modifier = Modifier.width(StreamifySpacing.Md))

                Column(
                    modifier = Modifier.weight(1f)
                        .then(
                            if (onExpand != null) {
                                Modifier.clickable { onExpand() }
                            } else {
                                Modifier
                            }
                        ),
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        text = track?.title ?: "Control plane live — no track loaded",
                        style = StreamifyTextStyles.songTitle,
                        color = StreamifyPalette.TextMain,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    val positionText = state.positionMs?.let { formatPlaybackTime(it) }
                    val durationText = track?.durationMs?.let { formatPlaybackTime(it) }
                    val subtitle = when {
                        track != null && positionText != null && durationText != null ->
                            "${track.artist} · $positionText / $durationText"
                        track != null && positionText != null ->
                            "${track.artist} · $positionText"
                        track != null -> track.artist
                        else -> "Rust core engine · snapshots pushed on change"
                    }
                    Text(
                        text = subtitle,
                        style = StreamifyTextStyles.songArtist,
                        color = StreamifyPalette.TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                IconButton(onClick = onPrevious) {
                    Icon(
                        imageVector = Icons.Filled.SkipPrevious,
                        contentDescription = "Previous track",
                        tint = StreamifyPalette.TextMain,
                        modifier = Modifier.size(24.dp),
                    )
                }

                TransportButton(
                    status = state.status,
                    onTogglePlayPause = onTogglePlayPause,
                )

                IconButton(onClick = onNext) {
                    Icon(
                        imageVector = Icons.Filled.SkipNext,
                        contentDescription = "Next track",
                        tint = StreamifyPalette.TextMain,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }

            // Micro-progress strip along the bottom edge: determinate when
            // both DAC position and track duration are known, quiet
            // baseline otherwise.
            Box(
                modifier = Modifier.fillMaxWidth()
                    .height(2.dp)
                    .align(Alignment.BottomCenter),
            ) {
                if (fraction != null) {
                    LinearProgressIndicator(
                        progress = { fraction },
                        modifier = Modifier.fillMaxWidth().height(2.dp),
                        color = StreamifyPalette.ActiveControl,
                        trackColor = StreamifyPalette.Divider,
                        strokeCap = StrokeCap.Round,
                    )
                } else {
                    Box(
                        modifier = Modifier.fillMaxWidth().height(2.dp)
                            .background(StreamifyPalette.Divider),
                    )
                }
            }
        }
    }
}

/** 48dp artwork tile with a graceful note-glyph placeholder. */
@Composable
private fun TrackBadge(track: PresentationTrack?) {
    Box(
        modifier = Modifier.size(48.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(StreamifyPalette.BgCard),
        contentAlignment = Alignment.Center,
    ) {
        // Artwork loading is a platform concern (Coil on Android, async
        // fetchers on desktop): hosts overlay it on this placeholder slot.
        // The shared baseline renders the brand glyph.
        Icon(
            imageVector = Icons.Filled.MusicNote,
            contentDescription = null,
            tint = StreamifyPalette.TextSecondary,
            modifier = Modifier.size(22.dp),
        )
    }
}

/** Play/Pause button with buffering spinner state (app-parity tri-state). */
@Composable
private fun TransportButton(
    status: PlaybackStatus,
    onTogglePlayPause: () -> Unit,
) {
    IconButton(onClick = onTogglePlayPause) {
        AnimatedContent(
            targetState = status,
            transitionSpec = { fadeIn(tween(120)) togetherWith fadeOut(tween(120)) },
            label = "MiniPlayerTransport",
        ) { target ->
            when (target) {
                PlaybackStatus.BUFFERING -> CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    color = StreamifyPalette.TextMain,
                    strokeWidth = 2.dp,
                    strokeCap = StrokeCap.Round,
                )
                PlaybackStatus.PLAYING -> Icon(
                    imageVector = Icons.Filled.Pause,
                    contentDescription = "Pause",
                    tint = StreamifyPalette.TextMain,
                    modifier = Modifier.size(28.dp),
                )
                else -> Icon(
                    imageVector = Icons.Filled.PlayArrow,
                    contentDescription = "Play",
                    tint = StreamifyPalette.TextMain,
                    modifier = Modifier.size(28.dp),
                )
            }
        }
    }
}
