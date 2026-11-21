package com.streamify.app.ui.components

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.streamify.app.data.update.UpdateState
import com.streamify.app.ui.theme.*

@Composable
fun UpdateAvailableCard(
    updateState: UpdateState.UpdateAvailable,
    onUpdateClick: () -> Unit,
    onDismissClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isChangelogExpanded by remember { mutableStateOf(false) }

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = BgSurfaceElevated,
        // Glowing crimson accent border
        border = BorderStroke(
            width = 1.dp,
            brush = Brush.horizontalGradient(
                listOf(Primary, Primary.copy(alpha = 0.4f))
            )
        ),
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .animateContentSize()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Header Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Filled.SystemUpdate,
                        contentDescription = null,
                        tint = Primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "New Update Available",
                        style = LocalAppTypography.current.headlineMedium.copy(
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        ),
                        color = TextMain
                    )
                }

                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = Primary.copy(alpha = 0.15f)
                ) {
                    Text(
                        text = "Build ${updateState.buildInfo.buildNumber}",
                        style = LocalAppTypography.current.chipText.copy(
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        ),
                        color = Primary,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Changelog preview
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { isChangelogExpanded = !isChangelogExpanded }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val changelogText = updateState.buildInfo.changelog.ifBlank { "New features, performance upgrades, and bug fixes." }
                Text(
                    text = if (isChangelogExpanded) changelogText else changelogText.take(90) + if (changelogText.length > 90) "..." else "",
                    style = LocalAppTypography.current.songArtist.copy(fontSize = 13.sp),
                    color = TextSecondary,
                    modifier = Modifier.weight(1f)
                )

                Icon(
                    imageVector = if (isChangelogExpanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                    contentDescription = "Expand",
                    tint = TextSecondary,
                    modifier = Modifier.size(20.dp)
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Action Buttons Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Dismiss",
                    style = LocalAppTypography.current.titleLarge.copy(fontSize = 13.sp),
                    color = TextSecondary,
                    modifier = Modifier
                        .clickable { onDismissClick() }
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                )

                // Update Now Button (Stark White Pill)
                Surface(
                    shape = RoundedCornerShape(50.dp),
                    color = ActiveControl,
                    modifier = Modifier.clickable { onUpdateClick() }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Download,
                            contentDescription = "Download",
                            tint = TextOnActiveChip,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Update Now",
                            style = LocalAppTypography.current.titleLarge.copy(
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            ),
                            color = TextOnActiveChip
                        )
                    }
                }
            }
        }
    }
}
