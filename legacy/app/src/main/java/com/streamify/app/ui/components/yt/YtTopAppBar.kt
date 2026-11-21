package com.streamify.app.ui.components.yt
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.streamify.app.ui.theme.*
import com.streamify.app.ui.components.SireenBrandingBadge

@Composable
fun YtTopAppBar(
    onSearchClick: () -> Unit,
    onAvatarClick: () -> Unit,
    onCastClick: (() -> Unit)? = null,
    /** Phase 3 — "Your Updates" hub entry (hidden when null). */
    onNotificationsClick: (() -> Unit)? = null,
    avatarUrl: String? = null,
    avatarInitial: String = "S",
    modifier: Modifier = Modifier
) {
    Surface(
        color = BgBase,
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Left: YouTube Music Brand Mark
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(vertical = 4.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(Primary),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.PlayArrow,
                        contentDescription = "Music",
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                }

                Spacer(modifier = Modifier.width(6.dp))

                Text(
                    text = "Streamify",
                    style = LocalAppTypography.current.headlineMedium.copy(
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = (-0.4).sp
                    ),
                    color = TextMain
                )

                Spacer(modifier = Modifier.width(8.dp))

                SireenBrandingBadge()
            }

            // Right: Cast, Updates, Search, and Profile Avatar Actions
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { onCastClick?.invoke() }) {
                    Icon(
                        imageVector = Icons.Filled.Cast,
                        contentDescription = "Cast",
                        tint = TextMain,
                        modifier = Modifier.size(22.dp)
                    )
                }

                if (onNotificationsClick != null) {
                    IconButton(onClick = onNotificationsClick) {
                        Icon(
                            imageVector = Icons.Filled.Notifications,
                            contentDescription = "Your Updates",
                            tint = TextMain,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }

                IconButton(onClick = onSearchClick) {
                    Icon(
                        imageVector = Icons.Filled.Search,
                        contentDescription = "Search",
                        tint = TextMain,
                        modifier = Modifier.size(24.dp)
                    )
                }

                Spacer(modifier = Modifier.width(6.dp))

                // Profile Avatar
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .clip(CircleShape)
                        .border(1.dp, BorderChip, CircleShape)
                        .clickable(onClick = onAvatarClick),
                    contentAlignment = Alignment.Center
                ) {
                    if (!avatarUrl.isNullOrBlank()) {
                        AsyncImage(
                            model = avatarUrl,
                            contentDescription = "Profile",
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(BgSurfaceElevated),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = avatarInitial.take(1).uppercase(),
                                style = LocalAppTypography.current.chipText.copy(fontSize = 12.sp, fontWeight = FontWeight.Bold),
                                color = Primary
                            )
                        }
                    }
                }
            }
        }
    }
}
