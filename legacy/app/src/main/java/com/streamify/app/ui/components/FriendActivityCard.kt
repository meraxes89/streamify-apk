package com.streamify.app.ui.components

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.streamify.app.data.supabase.FriendActivity
import com.streamify.app.ui.theme.StreamifyColors
import com.streamify.app.ui.theme.StreamifyType
import com.streamify.app.util.StreamifyHapticEngine

/**
 * FriendActivityCard (Gap #32) — one row of the live friend activity rail:
 * who is playing what right now, with one-tap "Listen Along" and "Join Jam"
 * actions plus a "Blend" taste-merge shortcut. Tapping the avatar/name
 * header opens the friend's public profile. Card state (joinable jam)
 * animates in place.
 */
@Composable
fun FriendActivityCard(
    friend: FriendActivity,
    modifier: Modifier = Modifier,
    onListenAlong: (() -> Unit)? = null,
    onJoinJam: (() -> Unit)? = null,
    onBlend: (() -> Unit)? = null,
    onViewProfile: (() -> Unit)? = null,
    compact: Boolean = false
) {
    Surface(
        color = StreamifyColors.BgCard,
        shape = RoundedCornerShape(16.dp),
        modifier = modifier
            .width(if (compact) 280.dp else 200.dp)
            .animateContentSize()
    ) {
        Column(
            modifier = Modifier.padding(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = if (onViewProfile != null) {
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable {
                            StreamifyHapticEngine.magneticDetent()
                            onViewProfile()
                        }
                } else {
                    Modifier.fillMaxWidth()
                }
            ) {
                if (friend.avatarUrl.isNotBlank()) {
                    AsyncImage(
                        model = friend.avatarUrl,
                        contentDescription = null,
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(StreamifyColors.PrimaryDark),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            friend.displayName.take(1).uppercase(),
                            style = StreamifyType.BodyMediumBold,
                            color = StreamifyColors.TextMain
                        )
                    }
                }

                Spacer(modifier = Modifier.width(8.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        friend.displayName,
                        style = StreamifyType.BodySmallBold,
                        color = StreamifyColors.TextMain,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        friend.lastActiveAt,
                        style = StreamifyType.Caption,
                        color = StreamifyColors.Primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                if (friend.sessionCode != null) {
                    // In a live jam right now — the Join Jam affordance glows.
                    Icon(
                        Icons.Filled.Groups,
                        contentDescription = "In a Jam",
                        tint = StreamifyColors.Primary,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.GraphicEq,
                    contentDescription = null,
                    tint = StreamifyColors.Primary,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Column {
                    Text(
                        friend.trackTitle,
                        style = StreamifyType.CaptionBold,
                        color = StreamifyColors.TextMain,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        friend.trackArtist,
                        style = StreamifyType.Caption,
                        color = StreamifyColors.TextSub,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // ── Gap #32 one-tap actions ────────────────────────────────────
            if (onListenAlong != null || onJoinJam != null || onBlend != null) {
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (onListenAlong != null) {
                        ActionChip(
                            icon = Icons.Filled.Hearing,
                            label = "Listen Along",
                            emphasized = true,
                            onClick = {
                                StreamifyHapticEngine.magneticDetent()
                                onListenAlong()
                            }
                        )
                    }
                    if (onJoinJam != null && friend.sessionCode != null) {
                        ActionChip(
                            icon = Icons.Filled.Groups,
                            label = "Join Jam",
                            emphasized = true,
                            onClick = {
                                StreamifyHapticEngine.magneticDetent()
                                onJoinJam()
                            }
                        )
                    }
                    if (onBlend != null) {
                        ActionChip(
                            icon = Icons.Filled.QueueMusic,
                            label = "Blend",
                            emphasized = false,
                            onClick = onBlend
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ActionChip(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    emphasized: Boolean,
    onClick: () -> Unit
) {
    Surface(
        color = if (emphasized) StreamifyColors.Primary.copy(alpha = 0.16f) else StreamifyColors.BgElevated,
        shape = RoundedCornerShape(10.dp),
        onClick = onClick
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (emphasized) StreamifyColors.Primary else StreamifyColors.TextSub,
                modifier = Modifier.size(12.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = label,
                style = StreamifyType.Caption.copy(fontSize = 10.sp, fontWeight = FontWeight.Bold),
                color = if (emphasized) StreamifyColors.Primary else StreamifyColors.TextSub,
                maxLines = 1
            )
        }
    }
}
