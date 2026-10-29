package com.streamify.shared.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.streamify.shared.theme.StreamifyPalette
import com.streamify.shared.theme.StreamifySpacing
import com.streamify.shared.theme.StreamifyTextStyles
import kotlin.math.roundToInt

/**
 * Phase 5 (MP-UI03 / Beta): the platform-agnostic output gain control.
 *
 * Binds DIRECTLY to engine-authoritative volume (the value the Rust core
 * clamps to [0.0, 1.0] and echoes back in every pushed snapshot — the
 * slider never owns state, it renders it), with a mute toggle and a
 * percentage readout. Hosts wire [onVolumeChange] to
 * [com.streamify.shared.state.PlaybackController.setVolume] and let the
 * push flow repaint the slider — round-trip discipline instead of local
 * echo, so two surfaces (e.g. mini player + tray) can never disagree.
 */
@Composable
fun VolumeSlider(
    volume: Float,
    onVolumeChange: (Float) -> Unit,
    onToggleMute: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val clamped = volume.coerceIn(0f, 1f)
    val muted = clamped <= 0f

    Row(
        modifier = modifier.padding(horizontal = StreamifySpacing.Lg),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(StreamifySpacing.Sm),
    ) {
        IconButton(
            onClick = { onToggleMute?.invoke() },
            enabled = enabled && onToggleMute != null,
        ) {
            Icon(
                imageVector = if (muted) {
                    Icons.Filled.VolumeOff
                } else {
                    Icons.AutoMirrored.Filled.VolumeUp
                },
                contentDescription = if (muted) "Unmute" else "Mute",
                tint = if (muted) {
                    StreamifyPalette.Primary
                } else {
                    StreamifyPalette.TextSecondary
                },
                modifier = Modifier.size(22.dp),
            )
        }

        Slider(
            value = clamped,
            onValueChange = onVolumeChange,
            enabled = enabled,
            modifier = Modifier.weight(1f),
        )

        Spacer(modifier = Modifier.width(StreamifySpacing.Xs))

        Text(
            text = "${(clamped * 100f).roundToInt()}%",
            style = StreamifyTextStyles.caption,
            color = StreamifyPalette.TextTertiary,
        )
    }
}
