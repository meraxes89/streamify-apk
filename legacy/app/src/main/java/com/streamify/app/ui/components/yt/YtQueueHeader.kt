package com.streamify.app.ui.components.yt
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.streamify.app.ui.theme.*

@Composable
fun YtQueueHeader(
    sourceName: String,
    isAutoplayEnabled: Boolean,
    onToggleAutoplay: () -> Unit,
    onClearQueue: () -> Unit,
    onClose: () -> Unit,
    hasQueueItems: Boolean = false,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "Close",
                        tint = TextMain,
                        modifier = Modifier.size(24.dp)
                    )
                }
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "Up next",
                    style = LocalAppTypography.current.headlineMedium.copy(fontSize = 18.sp),
                    color = TextMain
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                if (hasQueueItems) {
                    TextButton(
                        onClick = onClearQueue,
                        contentPadding = PaddingValues(horizontal = 8.dp)
                    ) {
                        Text(
                            text = "Clear",
                            style = LocalAppTypography.current.chipText.copy(fontSize = 12.sp),
                            color = TextSecondary
                        )
                    }
                }

                Text(
                    text = "Autoplay",
                    style = LocalAppTypography.current.chipText.copy(fontSize = 12.sp),
                    color = if (isAutoplayEnabled) ActiveControl else TextSecondary
                )
                Spacer(modifier = Modifier.width(4.dp))
                Switch(
                    checked = isAutoplayEnabled,
                    onCheckedChange = { onToggleAutoplay() },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = TextOnActiveChip,
                        checkedTrackColor = ActiveControl,
                        uncheckedThumbColor = TextSecondary,
                        uncheckedTrackColor = BgSurfaceElevated
                    ),
                    modifier = Modifier.scale(0.8f)
                )
            }
        }

        if (sourceName.isNotBlank()) {
            Text(
                text = "Playing from $sourceName",
                style = LocalAppTypography.current.songArtist.copy(fontSize = 12.sp),
                color = TextSecondary,
                modifier = Modifier.padding(start = 16.dp, top = 2.dp, bottom = 4.dp)
            )
        }
    }
}
