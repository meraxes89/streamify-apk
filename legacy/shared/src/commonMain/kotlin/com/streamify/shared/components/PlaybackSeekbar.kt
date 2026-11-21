package com.streamify.shared.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.streamify.shared.model.DacPositionSmoother
import com.streamify.shared.model.formatPlaybackTime
import com.streamify.shared.theme.StreamifyPalette
import com.streamify.shared.theme.StreamifyTextStyles

/**
 * Phase 5 (MP-UI03 / Beta): the platform-agnostic seek bar — the shared
 * media-control surface for hardware-derived positions.
 *
 * Renders the DAC-clock position (MP-DEC-03 — hardware samples are the sole
 * clock master, filtered only by [DacPositionSmoother]'s
 * jitter/monotonicity guards) with drag-to-seek:
 *
 *  * position + duration known — an interactive [Slider]; while the user
 *    drags, the bar renders the SCRUB position (the engine keeps playing
 *    untouched — [PlaybackSeekbar.onSeek] fires exactly once, on release);
 *  * position known, duration unknown — a quiet track baseline plus the
 *    elapsed label (an honest position-only rendering; a seek TARGET cannot
 *    be computed without a duration, so the bar stays read-only instead of
 *    fabricating one);
 *  * neither known — the quiet baseline ("--:--" labels), the shared
 *    degraded-mode discipline: absence is never dressed up as zero.
 *
 * Round-trip discipline (the VolumeSlider contract, applied to position):
 * the bar never owns authoritative state — it renders what the engine last
 * pushed and lowers [onSeek] onto the host, which dispatches a
 * [com.streamify.shared.model.PlayerCommand.Seek]; the engine's next push
 * repaints the bar. Two surfaces rendering the same session can therefore
 * never disagree.
 */
@Composable
fun PlaybackSeekbar(
    positionMs: Long?,
    durationMs: Long?,
    onSeek: (positionMs: Long) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val knownDuration = durationMs?.takeIf { it > 0L }
    var scrubbing by remember { mutableStateOf(false) }
    var scrubPosition by remember { mutableStateOf(0L) }

    // Track-identity reset: a stale scrub latch must never leak across
    // tracks. Keying the latch on the duration makes a track change (new
    // duration) drop any in-flight scrub state deterministically.
    LaunchedEffect(knownDuration) {
        scrubbing = false
    }

    val displayPosition = if (scrubbing) scrubPosition else positionMs

    Column(modifier = modifier.fillMaxWidth()) {
        if (knownDuration != null && displayPosition != null) {
            Slider(
                value = displayPosition.coerceIn(0L, knownDuration).toFloat(),
                onValueChange = { raw ->
                    scrubbing = true
                    scrubPosition = raw.toLong().coerceIn(0L, knownDuration)
                },
                onValueChangeFinished = {
                    if (scrubbing) {
                        onSeek(scrubPosition)
                        scrubbing = false
                    }
                },
                valueRange = 0f..knownDuration.toFloat(),
                enabled = enabled,
                modifier = Modifier.fillMaxWidth().height(28.dp),
            )
        } else {
            // Quiet baseline: a non-interactive track strip in the same
            // slot, so layout never jumps between degraded and determinate
            // modes.
            Slider(
                value = 0f,
                onValueChange = { },
                onValueChangeFinished = { },
                valueRange = 0f..1f,
                enabled = false,
                modifier = Modifier.fillMaxWidth().height(28.dp),
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = displayPosition?.let { formatPlaybackTime(it) } ?: "--:--",
                style = StreamifyTextStyles.caption,
                color = if (scrubbing) StreamifyPalette.ActiveControl else StreamifyPalette.TextTertiary,
            )
            Text(
                text = knownDuration?.let { formatPlaybackTime(it) } ?: "--:--",
                style = StreamifyTextStyles.caption,
                color = StreamifyPalette.TextTertiary,
            )
        }
    }
}

/**
 * Host-side smoothing hook: feeds every pushed DAC position through a
 * remembered [DacPositionSmoother] and returns the value the UI should
 * render. Both shells (desktop Home pane, any Android host) share this one
 * wiring so the playhead discipline is implemented exactly once.
 */
@Composable
fun rememberDacSmoothedPosition(
    positionMs: Long?,
    isPlaying: Boolean,
): Long? {
    val smoother = remember { DacPositionSmoother() }
    var rendered by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(positionMs, isPlaying) {
        rendered = smoother.offer(positionMs, isPlaying)
    }
    return rendered
}
