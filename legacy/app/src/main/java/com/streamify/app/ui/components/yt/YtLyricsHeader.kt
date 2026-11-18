package com.streamify.app.ui.components.yt
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.streamify.app.ui.theme.*

@Composable
fun YtLyricsHeader(
    source: String = "Musixmatch / LRCLIB",
    isSynced: Boolean = true,
    userOffsetMs: Long = 0L,
    onAdjustOffset: ((Long) -> Unit)? = null,
    onResetOffset: (() -> Unit)? = null,
    onSaveOffset: (() -> Unit)? = null,
    onClose: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (onClose != null) {
                IconButton(onClick = onClose) {
                    Icon(
                        imageVector = Icons.Filled.KeyboardArrowDown,
                        contentDescription = "Close",
                        tint = TextMain,
                        modifier = Modifier.size(32.dp)
                    )
                }
            } else {
                Spacer(modifier = Modifier.width(12.dp))
            }

            Column(modifier = Modifier.padding(start = if (onClose == null) 4.dp else 0.dp)) {
                Text(
                    text = "Lyrics",
                    style = LocalAppTypography.current.headlineMedium.copy(fontSize = 18.sp),
                    color = TextMain
                )
                Text(
                    text = if (isSynced) "Source: $source • Real-Time Sync" else "Source: $source • Unsynchronized",
                    style = LocalAppTypography.current.songArtist.copy(fontSize = 11.sp),
                    color = TextSecondary
                )
            }
        }

        // Micro-Nudge Timing Offset Controls (When lyrics are synced)
        if (isSynced && onAdjustOffset != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(end = 12.dp)
            ) {
                // Nudge Back 0.5s
                Surface(
                    onClick = { onAdjustOffset(-500L) },
                    shape = RoundedCornerShape(8.dp),
                    color = BgSurfaceElevated,
                    modifier = Modifier.height(28.dp)
                ) {
                    Box(
                        modifier = Modifier.padding(horizontal = 7.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "-0.5s",
                            style = LocalAppTypography.current.chipText.copy(fontSize = 10.sp, fontWeight = FontWeight.Bold),
                            color = TextSecondary
                        )
                    }
                }

                Spacer(modifier = Modifier.width(4.dp))

                // Current Offset Indicator / Reset
                if (userOffsetMs != 0L) {
                    Surface(
                        onClick = { onResetOffset?.invoke() },
                        shape = RoundedCornerShape(8.dp),
                        color = Primary.copy(alpha = 0.2f),
                        modifier = Modifier.height(28.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 6.dp)
                        ) {
                            Text(
                                text = "${if (userOffsetMs > 0) "+" else ""}${userOffsetMs / 1000.0}s",
                                style = LocalAppTypography.current.chipText.copy(
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold
                                ),
                                color = Primary
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(4.dp))

                    // Small Tick Button to Save for Local & Community
                    if (onSaveOffset != null) {
                        Surface(
                            onClick = { onSaveOffset.invoke() },
                            shape = RoundedCornerShape(8.dp),
                            color = Primary,
                            modifier = Modifier.height(28.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 6.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Check,
                                    contentDescription = "Save & Share Offset",
                                    tint = BgBase,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(4.dp))
                    }
                }

                // Nudge Forward 0.5s
                Surface(
                    onClick = { onAdjustOffset(500L) },
                    shape = RoundedCornerShape(8.dp),
                    color = BgSurfaceElevated,
                    modifier = Modifier.height(28.dp)
                ) {
                    Box(
                        modifier = Modifier.padding(horizontal = 7.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "+0.5s",
                            style = LocalAppTypography.current.chipText.copy(fontSize = 10.sp, fontWeight = FontWeight.Bold),
                            color = TextSecondary
                        )
                    }
                }
            }
        }
    }
}
