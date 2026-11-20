package com.streamify.app.ui.components.yt
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.ThumbDown
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.outlined.ThumbDown
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.streamify.app.ui.theme.*

@Composable
fun YtPlayerActionPills(
    isLiked: Boolean,
    onToggleLike: () -> Unit,
    onCommentsClick: (() -> Unit)? = null,
    onSaveClick: (() -> Unit)? = null,
    onJamClick: (() -> Unit)? = null,
    onRadioClick: (() -> Unit)? = null,
    onDownloadClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier.fillMaxWidth()
    ) {
        // 1. Thumbs Up / Thumbs Down Combo Pill
        item {
            Surface(
                color = BgSurfaceElevated,
                border = BorderStroke(1.dp, BorderChip),
                shape = CircleShape,
                modifier = Modifier.height(36.dp)
            ) {
                Row(
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                        .fillMaxHeight(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable { onToggleLike() }
                    ) {
                        Icon(
                            imageVector = if (isLiked) Icons.Filled.ThumbUp else Icons.Outlined.ThumbUp,
                            contentDescription = "Like",
                            tint = if (isLiked) Primary else TextMain,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (isLiked) "Liked" else "Like",
                            style = LocalAppTypography.current.chipText.copy(fontSize = 12.sp),
                            color = TextMain
                        )
                    }

                    Box(
                        modifier = Modifier
                            .size(width = 1.dp, height = 16.dp)
                            .background(BorderChip)
                    )

                    Icon(
                        imageVector = Icons.Outlined.ThumbDown,
                        contentDescription = "Dislike",
                        tint = TextSecondary,
                        modifier = Modifier
                            .size(18.dp)
                            .clickable { /* Dislike handler */ }
                    )
                }
            }
        }

        // 2. Comments Pill
        if (onCommentsClick != null) {
            item {
                ActionPill(
                    icon = Icons.Filled.ChatBubbleOutline,
                    label = "Comments",
                    onClick = onCommentsClick
                )
            }
        }

        // 3. Radio Pill
        if (onRadioClick != null) {
            item {
                ActionPill(
                    icon = Icons.Filled.AutoAwesome,
                    label = "Radio",
                    onClick = onRadioClick
                )
            }
        }

        // 4. Save / Add to Playlist Pill
        if (onSaveClick != null) {
            item {
                ActionPill(
                    icon = Icons.Filled.BookmarkBorder,
                    label = "Save",
                    onClick = onSaveClick
                )
            }
        }

        // 5. Share / Jam Pill
        if (onJamClick != null) {
            item {
                ActionPill(
                    icon = Icons.Filled.Share,
                    label = "Jam",
                    onClick = onJamClick
                )
            }
        }

        // 6. Download Pill
        if (onDownloadClick != null) {
            item {
                ActionPill(
                    icon = Icons.Filled.Download,
                    label = "Download",
                    onClick = onDownloadClick
                )
            }
        }
    }
}

@Composable
private fun ActionPill(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit
) {
    Surface(
        color = BgSurfaceElevated,
        border = BorderStroke(1.dp, BorderChip),
        shape = CircleShape,
        modifier = Modifier
            .height(36.dp)
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 14.dp)
                .fillMaxHeight(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = TextMain,
                modifier = Modifier.size(18.dp)
            )
            Text(
                text = label,
                style = LocalAppTypography.current.chipText.copy(fontSize = 12.sp),
                color = TextMain
            )
        }
    }
}
