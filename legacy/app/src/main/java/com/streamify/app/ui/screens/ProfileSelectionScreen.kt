package com.streamify.app.ui.screens

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.streamify.app.data.models.AppMode
import com.streamify.app.data.spotify.SpotifyAuthManager
import com.streamify.app.ui.components.yt.YtLoginDialog
import com.streamify.app.ui.theme.StreamifyColors
import com.streamify.app.ui.theme.StreamifyType

@Composable
fun ProfileSelectionScreen(
    onProfileConfigured: (AppMode) -> Unit
) {
    val context = LocalContext.current
    val spotifyAuth = remember { SpotifyAuthManager(context) }
    var showSpotifyAuthDialog by remember { mutableStateOf(false) }
    var showYtAuthDialog by remember { mutableStateOf(false) }
    var isVisible by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        isVisible = true
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(StreamifyColors.BgBase)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(24.dp)
    ) {
        AnimatedVisibility(
            visible = isVisible,
            enter = fadeIn(tween(600)) + slideInVertically(tween(600)) { it / 3 }
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Glow App Tag
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(StreamifyColors.Primary.copy(alpha = 0.15f))
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = "ONBOARDING CONTINUUM",
                        color = StreamifyColors.Primary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.5.sp
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "Choose Your Experience",
                    color = Color.White,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "Select your profile provider to initialize your personalized real-time recommendation feed and acoustic continuum.",
                    color = Color(0xFF8E8E93),
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 8.dp)
                )

                Spacer(modifier = Modifier.height(32.dp))

                // 🟢 1. SPOTIFY PROFILE
                BrandedProfileCard(
                    title = "Spotify Profile",
                    subtitle = "Daily Mixes 1–6, Blends, Saved Library & Taste Graph",
                    brandColor = Color(0xFF1DB954),
                    badgeText = "VIBE ENGINE",
                    iconVector = Icons.Default.PlayArrow,
                    onClick = { showSpotifyAuthDialog = true }
                )

                Spacer(modifier = Modifier.height(16.dp))

                // 🔴 2. YOUTUBE MUSIC PROFILE
                BrandedProfileCard(
                    title = "YouTube Music Profile",
                    subtitle = "Supermix, Quick Picks, Unreleased Live & Remix Shelves",
                    brandColor = Color(0xFFFF0000),
                    badgeText = "DEEP CATALOG",
                    iconVector = Icons.Default.Radio,
                    onClick = { showYtAuthDialog = true }
                )

                Spacer(modifier = Modifier.height(16.dp))

                // 🌌 3. STREAMIFY HYBRID MODE
                BrandedProfileCard(
                    title = "Streamify Continuum",
                    subtitle = "50/50 Dual-Core Ensemble, Local Vectors & Edge Mesh",
                    brandColor = StreamifyColors.Primary,
                    badgeText = "AUTONOMOUS",
                    iconVector = Icons.Default.AutoAwesome,
                    onClick = {
                        AppMode.setAppMode(context, AppMode.STREAMIFY)
                        onProfileConfigured(AppMode.STREAMIFY)
                    }
                )
            }
        }
    }

    // Spotify Sandboxed In-App Login Modal
    com.streamify.app.ui.components.SpotifyLoginDialog(
        isOpen = showSpotifyAuthDialog,
        onDismiss = { showSpotifyAuthDialog = false },
        onAuthSuccess = { token, spDc ->
            showSpotifyAuthDialog = false
            AppMode.setAppMode(context, AppMode.SPOTIFY)
            onProfileConfigured(AppMode.SPOTIFY)
            Toast.makeText(context, "Spotify continuum configured! 🎵", Toast.LENGTH_SHORT).show()
        },
        onError = { err ->
            Toast.makeText(context, "Spotify login note: $err", Toast.LENGTH_SHORT).show()
        }
    )

    // Google 2FA Sandboxed WebView Login Modal
    YtLoginDialog(
        isOpen = showYtAuthDialog,
        onDismiss = { showYtAuthDialog = false },
        onAuthSuccess = { authHeader, cookies ->
            showYtAuthDialog = false
            AppMode.setAppMode(context, AppMode.YOUTUBE_MUSIC)
            onProfileConfigured(AppMode.YOUTUBE_MUSIC)
            Toast.makeText(context, "YouTube Music continuum configured!", Toast.LENGTH_SHORT).show()
        },
        onError = { err ->
            Toast.makeText(context, "YouTube login note: $err", Toast.LENGTH_SHORT).show()
        }
    )
}

@Composable
private fun BrandedProfileCard(
    title: String,
    subtitle: String,
    brandColor: Color,
    badgeText: String,
    iconVector: ImageVector,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF141418)),
        border = BorderStroke(1.dp, brandColor.copy(alpha = 0.25f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(brandColor.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = iconVector,
                    contentDescription = title,
                    tint = brandColor,
                    modifier = Modifier.size(28.dp)
                )
            }

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = title,
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )

                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(brandColor.copy(alpha = 0.12f))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = badgeText,
                            color = brandColor,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = subtitle,
                    color = Color(0xFFA0A0A5),
                    fontSize = 12.sp,
                    lineHeight = 16.sp
                )
            }
        }
    }
}
