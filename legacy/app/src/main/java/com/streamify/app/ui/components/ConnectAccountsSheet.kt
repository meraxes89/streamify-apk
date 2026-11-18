package com.streamify.app.ui.components

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.streamify.app.data.spotify.SpotifyAuthManager
import com.streamify.app.ui.theme.StreamifyColors
import com.streamify.app.ui.theme.StreamifyType
import com.streamify.app.ui.components.yt.YtLoginDialog

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectAccountsSheet(
    isOpen: Boolean,
    onDismiss: () -> Unit,
    onYtConnected: () -> Unit = {},
    onSpotifyConnected: () -> Unit = {}
) {
    if (!isOpen) return

    val context = LocalContext.current
    var showSpotifyDialog by remember { mutableStateOf(false) }
    var showYtDialog by remember { mutableStateOf(false) }
    val spotifyAuth = remember { SpotifyAuthManager(context) }
    val isSpotifyConnected by SpotifyAuthManager.isSpotifyConnectedFlow.collectAsState()
    val isYtConnected by SpotifyAuthManager.isYtConnectedFlow.collectAsState()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF141418),
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(top = 12.dp, bottom = 8.dp)
                    .width(40.dp)
                    .height(4.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF33333A))
            )
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(start = 24.dp, end = 24.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "Connect Your Taste",
                color = Color.White,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Sync your playlists, liked songs, and daily mixes from Spotify and YouTube Music into Streamify's algorithmic continuum.",
                color = Color(0xFFA0A0A5),
                fontSize = 13.sp,
                lineHeight = 18.sp
            )

            Spacer(modifier = Modifier.height(4.dp))

            // 1. Spotify Connection Card
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1C1C22)),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(42.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF1DB954).copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.PlayArrow,
                                contentDescription = null,
                                tint = Color(0xFF1DB954),
                                modifier = Modifier.size(24.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Spotify", style = StreamifyType.TitleMedium, color = Color.White)
                                Spacer(modifier = Modifier.width(6.dp))
                                if (isSpotifyConnected) {
                                    Icon(
                                        imageVector = Icons.Default.CheckCircle,
                                        contentDescription = "Connected",
                                        tint = Color(0xFF1DB954),
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                            Text(
                                text = if (isSpotifyConnected) "Connected (Daily Mixes active)" else "Daily Mixes, Blends & Saved Library",
                                style = StreamifyType.Caption,
                                color = if (isSpotifyConnected) Color(0xFF1DB954) else Color(0xFF8E8E93)
                            )
                        }
                    }

                    if (isSpotifyConnected) {
                        TextButton(onClick = {
                            spotifyAuth.clearTokens()
                            Toast.makeText(context, "Spotify disconnected", Toast.LENGTH_SHORT).show()
                        }) {
                            Text("Disconnect", color = StreamifyColors.ErrorRed, style = StreamifyType.CaptionBold)
                        }
                    } else {
                        Button(
                            onClick = {
                                showSpotifyDialog = true
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1DB954)),
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                        ) {
                            Text("Connect", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        }
                    }
                }
            }

            // 2. YouTube Music Connection Card
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1C1C22)),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(42.dp)
                                .clip(CircleShape)
                                .background(Color(0xFFFF0000).copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.PlayArrow,
                                contentDescription = null,
                                tint = Color(0xFFFF0000),
                                modifier = Modifier.size(24.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("YouTube Music", style = StreamifyType.TitleMedium, color = Color.White)
                                Spacer(modifier = Modifier.width(6.dp))
                                if (isYtConnected) {
                                    Icon(
                                        imageVector = Icons.Default.CheckCircle,
                                        contentDescription = "Connected",
                                        tint = Color(0xFFFF4444),
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                            Text(
                                text = if (isYtConnected) "Connected (Supermix active)" else "Supermix, Quick Picks & Live Gems",
                                style = StreamifyType.Caption,
                                color = if (isYtConnected) Color(0xFFFF4444) else Color(0xFF8E8E93)
                            )
                        }
                    }

                    if (isYtConnected) {
                        TextButton(onClick = {
                            spotifyAuth.clearYtSession()
                            Toast.makeText(context, "YouTube Music disconnected", Toast.LENGTH_SHORT).show()
                        }) {
                            Text("Disconnect", color = StreamifyColors.ErrorRed, style = StreamifyType.CaptionBold)
                        }
                    } else {
                        Button(
                            onClick = { showYtDialog = true },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF0000)),
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                        ) {
                            Text("Connect", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        }
                    }
                }
            }
        }
    }

    // Spotify In-App Login Dialog
    SpotifyLoginDialog(
        isOpen = showSpotifyDialog,
        onDismiss = { showSpotifyDialog = false },
        onAuthSuccess = { token, spDc ->
            showSpotifyDialog = false
            onSpotifyConnected()
            Toast.makeText(context, "Spotify connected successfully! 🎵", Toast.LENGTH_SHORT).show()
        },
        onError = { err ->
            Toast.makeText(context, "Spotify login note: $err", Toast.LENGTH_SHORT).show()
        }
    )

    // Google WebView Login Dialog
    YtLoginDialog(
        isOpen = showYtDialog,
        onDismiss = { showYtDialog = false },
        onAuthSuccess = { authHeader, cookies ->
            showYtDialog = false
            onYtConnected()
            Toast.makeText(context, "YouTube Music connected successfully!", Toast.LENGTH_SHORT).show()
        },
        onError = { err ->
            Toast.makeText(context, "YouTube login note: $err", Toast.LENGTH_SHORT).show()
        }
    )
}
