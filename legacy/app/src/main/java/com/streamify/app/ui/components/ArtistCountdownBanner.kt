package com.streamify.app.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.streamify.app.data.network.ReleaseWatcherApi

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * ArtistCountdownBanner — upcoming-album countdown + Pre-Save (Phase 3, 5)
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * The promo banner on ArtistScreen: release art, title + type, a live
 * ticking countdown (fed by the caller's 1 Hz clock), and the Pre-Save
 * button wired to PreSaveStore. Gradient plate with a pulsing accent so
 * the release feels "alive" without stealing focus from the track list.
 */
@Composable
fun ArtistCountdownBanner(
    artistName: String,
    release: ReleaseWatcherApi.ReleaseCandidate,
    nowMs: Long,
    isPreSaved: Boolean,
    onTogglePreSave: () -> Unit
) {
    val remaining = release.millisRemaining(nowMs)
    val countdownText = release.countdownLabel(nowMs)

    // Breathing accent while the countdown is live.
    val pulse by animateFloatAsState(
        targetValue = if (remaining > 0L) 0.7f + (0.3f * ((nowMs / 1000L) % 2L)) else 1f,
        animationSpec = tween(650),
        label = "releasePulse"
    )

    Surface(
        shape = RoundedCornerShape(18.dp),
        color = Color(0xFF16161E),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            Color(0xFF1ED760).copy(alpha = 0.35f)
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.horizontalGradient(
                        listOf(
                            Color(0xFF1ED760).copy(alpha = 0.10f),
                            Color(0xFF16161E)
                        )
                    )
                )
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(14.dp)
            ) {
                // Release cover / fallback plate
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF1ED760).copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    if (release.coverUrl.isNotBlank()) {
                        AsyncImage(
                            model = release.coverUrl,
                            contentDescription = release.title,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(64.dp)
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Filled.Album,
                            contentDescription = null,
                            tint = Color(0xFF1ED760),
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Filled.Bolt,
                            contentDescription = null,
                            tint = Color(0xFF1ED760).copy(alpha = pulse),
                            modifier = Modifier.size(13.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "UPCOMING ${release.releaseType.uppercase()}",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.8.sp,
                            color = Color(0xFF1ED760).copy(alpha = pulse)
                        )
                    }
                    Spacer(modifier = Modifier.height(3.dp))
                    Text(
                        text = release.title,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(3.dp))
                    Text(
                        text = "$countdownText • $artistName",
                        fontSize = 12.sp,
                        color = Color(0xB3FFFFFF),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(modifier = Modifier.width(10.dp))

                // Pre-Save button
                Surface(
                    onClick = onTogglePreSave,
                    shape = CircleShape,
                    color = if (isPreSaved) Color(0xFF1ED760)
                    else Color(0xFF1ED760).copy(alpha = 0.16f),
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        if (isPreSaved) Color(0xFF1ED760) else Color(0xFF1ED760).copy(alpha = 0.5f)
                    )
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Save,
                            contentDescription = if (isPreSaved) "Pre-saved" else "Pre-save",
                            tint = if (isPreSaved) Color.Black else Color(0xFF1ED760),
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(5.dp))
                        Text(
                            text = if (isPreSaved) "Saved" else "Pre-Save",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isPreSaved) Color.Black else Color(0xFF1ED760)
                        )
                    }
                }
            }
        }
    }
}
