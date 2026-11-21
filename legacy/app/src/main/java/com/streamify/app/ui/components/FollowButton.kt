package com.streamify.app.ui.components

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.streamify.app.data.social.FollowGraphStore
import com.streamify.app.ui.theme.StreamifyColors
import com.streamify.app.ui.theme.StreamifyType
import com.streamify.app.util.StreamifyHapticEngine

/**
 * FollowButton (Gap #31) — one-tap follow with a live follower count chip.
 * Reads the shared [FollowGraphStore] flows so every instance on screen
 * (Artist hero, UserProfile header, feed rows) updates in lock-step the
 * moment any one of them toggles.
 */
@Composable
fun FollowButton(
    type: FollowGraphStore.FollowType,
    id: String,
    modifier: Modifier = Modifier,
    label: String = "Follow",
    followingLabel: String = "Following",
    showCount: Boolean = true
) {
    val following by FollowGraphStore.following.collectAsState()
    val countOverrides by FollowGraphStore.countOverrides.collectAsState()
    val key = FollowGraphStore.key(type, id)
    val isFollowing = key in following
    val count = remember(key, following, countOverrides) {
        FollowGraphStore.followerCount(type, id)
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier.animateContentSize()
    ) {
        OutlinedButton(
            onClick = {
                StreamifyHapticEngine.magneticDetent()
                FollowGraphStore.toggleFollow(type, id)
            },
            shape = RoundedCornerShape(20.dp),
            border = if (isFollowing) null else BorderStroke(1.dp, StreamifyColors.Primary),
            colors = ButtonDefaults.outlinedButtonColors(
                containerColor = if (isFollowing) StreamifyColors.Primary else Color.Transparent,
                contentColor = if (isFollowing) Color.Black else StreamifyColors.Primary
            ),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)
        ) {
            Icon(
                imageVector = if (isFollowing) Icons.Filled.Check else Icons.Filled.PersonAdd,
                contentDescription = null,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = if (isFollowing) followingLabel else label,
                style = StreamifyType.TitleSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        if (showCount) {
            Surface(
                color = StreamifyColors.BgCard,
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(
                    text = formatFollowerCount(count),
                    style = StreamifyType.Caption.copy(fontSize = 11.sp),
                    color = StreamifyColors.TextSub,
                    maxLines = 1,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }
    }
}

/** 12,438 → "12.4K"; 312 → "312". */
fun formatFollowerCount(count: Int): String = when {
    count >= 1_000_000 -> String.format(java.util.Locale.US, "%.1fM", count / 1_000_000f)
    count >= 10_000 -> String.format(java.util.Locale.US, "%.0fK", count / 1_000f)
    count >= 1_000 -> String.format(java.util.Locale.US, "%.1fK", count / 1_000f)
    else -> count.toString()
}
