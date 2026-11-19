package com.streamify.app.ui.components.yt
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Link
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.streamify.app.data.youtube.ImportProgress
import com.streamify.app.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun YtImportPlaylistSheet(
    importProgress: ImportProgress?,
    isScraping: Boolean,
    onImportClick: (String) -> Unit,
    onPlayPlaylist: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var url by remember { mutableStateOf("") }

    // Dynamic Platform Identification
    val platformColor by animateColorAsState(
        targetValue = when {
            url.contains("spotify", ignoreCase = true) -> Color(0xFF1DB954)
            url.contains("youtube", ignoreCase = true) || url.contains("youtu.be", ignoreCase = true) -> Primary
            url.contains("apple", ignoreCase = true) -> Color(0xFFFA243C)
            else -> BorderChip
        },
        label = "platformColor"
    )

    val platformName = when {
        url.contains("spotify", ignoreCase = true) -> "Spotify"
        url.contains("youtube", ignoreCase = true) || url.contains("youtu.be", ignoreCase = true) -> "YouTube"
        url.contains("apple", ignoreCase = true) -> "Apple Music"
        else -> null
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = BgSurfaceElevated,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 16.dp)
                .navigationBarsPadding()
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Filled.Link,
                        contentDescription = null,
                        tint = Primary,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Import Playlist",
                        style = LocalAppTypography.current.headlineMedium.copy(
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold
                        ),
                        color = TextMain
                    )
                }

                if (platformName != null) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = platformColor.copy(alpha = 0.2f)
                    ) {
                        Text(
                            text = platformName,
                            style = LocalAppTypography.current.chipText.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp
                            ),
                            color = platformColor,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // URL Text Field
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = {
                    Text(
                        text = "Paste Spotify, YouTube, or Apple Music link",
                        color = TextSecondary,
                        style = LocalAppTypography.current.songArtist.copy(fontSize = 13.sp)
                    )
                },
                textStyle = LocalAppTypography.current.titleLarge.copy(fontSize = 14.sp, color = TextMain),
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = platformColor,
                    unfocusedBorderColor = BorderChip,
                    cursorColor = ActiveControl,
                    focusedTextColor = TextMain,
                    unfocusedTextColor = TextMain
                )
            )

            Spacer(modifier = Modifier.height(20.dp))

            if (isScraping) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    CircularProgressIndicator(
                        color = Primary,
                        strokeWidth = 2.5.dp,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "Scraping playlist tracks...",
                        style = LocalAppTypography.current.songArtist,
                        color = TextSecondary
                    )
                }
            } else if (importProgress == null || importProgress.total == 0) {
                // Initial State: Action Button
                Button(
                    onClick = { if (url.isNotBlank()) onImportClick(url) },
                    enabled = url.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = ActiveControl,
                        disabledContainerColor = BgCard
                    ),
                    shape = RoundedCornerShape(50.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                ) {
                    Text(
                        text = "Resolve & Import Tracks",
                        color = if (url.isNotBlank()) TextOnActiveChip else TextTertiary,
                        fontWeight = FontWeight.Bold,
                        style = LocalAppTypography.current.titleLarge.copy(fontSize = 14.sp)
                    )
                }
            } else {
                // Ingestion Progress State
                val progressFraction = if (importProgress.total > 0) {
                    importProgress.completed.toFloat() / importProgress.total.toFloat()
                } else 0f

                val animatedProgress by animateFloatAsState(
                    targetValue = progressFraction,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness = Spring.StiffnessLow
                    ),
                    label = "importProgressBar"
                )

                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = if (importProgress.isComplete) Icons.Filled.CheckCircle else Icons.Filled.Download,
                                contentDescription = null,
                                tint = if (importProgress.isComplete) Color(0xFF10B981) else platformColor,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (importProgress.isComplete) "Import Complete!" else importProgress.currentTrackTitle,
                                style = LocalAppTypography.current.titleLarge.copy(fontSize = 13.sp),
                                color = TextMain,
                                maxLines = 1
                            )
                        }

                        Text(
                            text = "${importProgress.completed}/${importProgress.total}",
                            style = LocalAppTypography.current.seekbarTime.copy(fontSize = 12.sp),
                            color = TextSecondary
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    LinearProgressIndicator(
                        progress = { animatedProgress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp)),
                        color = if (importProgress.isComplete) Color(0xFF10B981) else platformColor,
                        trackColor = BgCard
                    )

                    if (importProgress.isComplete) {
                        Spacer(modifier = Modifier.height(20.dp))
                        Button(
                            onClick = {
                                onPlayPlaylist(importProgress.playlistId)
                                onDismiss()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = ActiveControl),
                            shape = RoundedCornerShape(50.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.CheckCircle,
                                contentDescription = null,
                                tint = TextOnActiveChip,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Done • View in Library",
                                color = TextOnActiveChip,
                                fontWeight = FontWeight.Bold,
                                style = LocalAppTypography.current.titleLarge.copy(fontSize = 14.sp)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}
