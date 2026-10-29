package com.streamify.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Download
import com.streamify.app.BuildConfig
import com.streamify.app.data.repository.TrackRepository
import com.streamify.app.data.update.StreamifyUpdateManager
import com.streamify.app.data.update.UpdateState
import com.streamify.app.ui.theme.StreamifyColors
import com.streamify.app.ui.theme.StreamifyDimens
import com.streamify.app.ui.theme.StreamifyType
import com.streamify.app.viewmodel.PlayerViewModel
import com.streamify.app.media.audio.CrossfadeAudioProcessor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.streamify.app.util.SLog

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title.uppercase(),
        style = StreamifyType.LabelSmall.copy(letterSpacing = 1.2.sp),
        color = StreamifyColors.Primary,
        modifier = Modifier.padding(bottom = StreamifyDimens.SpaceSM)
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    playerViewModel: PlayerViewModel,
    onBack: () -> Unit,
    onNavigateToEq: () -> Unit = {},
    onNavigateToAdmin: () -> Unit = {},
    onNavigateToProfile: () -> Unit = {},
    onNavigateToWrapped: () -> Unit = {},
    onNavigateToCommunity: () -> Unit = {},
    onNavigateToProfileSelection: () -> Unit = {},
    onNavigateToTerminal: () -> Unit = {}
) {
    val playerState by playerViewModel.playerState.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    val audioPrefs = remember { context.getSharedPreferences("audio_settings", android.content.Context.MODE_PRIVATE) }
    var selectedQuality by remember { mutableStateOf(audioPrefs.getString("download_quality", "320") ?: "320") }
    var crossfadeValue by remember { mutableStateOf(CrossfadeAudioProcessor.crossfadeDurationMs / 1000f) }
    var showConnectAccountsSheet by remember { mutableStateOf(false) }

    val user by com.streamify.app.data.supabase.SupabaseClient.currentUser.collectAsState()
    val isSpotifyConnected by com.streamify.app.data.spotify.SpotifyAuthManager.isSpotifyConnectedFlow.collectAsState()
    val isYtConnected by com.streamify.app.data.spotify.SpotifyAuthManager.isYtConnectedFlow.collectAsState()
    val currentAppMode by com.streamify.app.data.models.AppMode.currentMode.collectAsState()
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(StreamifyColors.BgBase)
    ) {
        Spacer(modifier = Modifier.height(StreamifyDimens.SpaceGiant))

        // Top App Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = StreamifyDimens.SpaceLG),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "Back", tint = StreamifyColors.TextMain)
            }
            Spacer(modifier = Modifier.width(StreamifyDimens.SpaceMD))
            Text("Settings", style = StreamifyType.HeadlineLarge, color = StreamifyColors.TextMain)
        }

        Spacer(modifier = Modifier.height(StreamifyDimens.SpaceLG))

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = StreamifyDimens.SpaceLG,
                end = StreamifyDimens.SpaceLG,
                bottom = StreamifyDimens.PlayerBarHeight + StreamifyDimens.SpaceXL
            ),
            verticalArrangement = Arrangement.spacedBy(StreamifyDimens.SpaceLG)
        ) {
            // CONNECTED STREAMING ACCOUNTS & TASTE SYNC
            item {
                SectionHeader("Connected Streaming Accounts")
                Card(
                    colors = CardDefaults.cardColors(containerColor = StreamifyColors.BgCard),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Music Services & Continuum", style = StreamifyType.TitleMedium, color = StreamifyColors.TextMain)
                                Text("Active Mode: ${currentAppMode.name.replace('_', ' ')}", style = StreamifyType.Caption, color = StreamifyColors.Primary)
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                if (isSpotifyConnected) {
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(androidx.compose.ui.graphics.Color(0xFF1DB954).copy(alpha = 0.2f))
                                            .padding(horizontal = 6.dp, vertical = 2.dp)
                                    ) {
                                        Text("SPOTIFY", color = androidx.compose.ui.graphics.Color(0xFF1DB954), fontSize = 9.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                                    }
                                }
                                if (isYtConnected) {
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(androidx.compose.ui.graphics.Color(0xFFFF0000).copy(alpha = 0.2f))
                                            .padding(horizontal = 6.dp, vertical = 2.dp)
                                    ) {
                                        Text("YTM", color = androidx.compose.ui.graphics.Color(0xFFFF4444), fontSize = 9.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = { showConnectAccountsSheet = true },
                                colors = ButtonDefaults.buttonColors(containerColor = StreamifyColors.Primary),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Manage Accounts", style = StreamifyType.CaptionBold, color = androidx.compose.ui.graphics.Color.Black)
                            }
                            OutlinedButton(
                                onClick = onNavigateToProfileSelection,
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Switch Mode", style = StreamifyType.CaptionBold)
                            }
                        }
                    }
                }
            }

            // SUPABASE CLOUD & COMMUNITY SECTION
            item {
                SectionHeader("Cloud & Social Hub")
                Card(
                    colors = CardDefaults.cardColors(containerColor = StreamifyColors.BgCard),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        if (user == null) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Sign In with Google", style = StreamifyType.TitleMedium, color = StreamifyColors.TextMain)
                                    Text("Sync likes, playlists & Jam rooms across devices", style = StreamifyType.Caption, color = StreamifyColors.TextSub)
                                }
                            }
                            Spacer(modifier = Modifier.height(12.dp))
                            Button(
                                onClick = {
                                    scope.launch {
                                        val res = com.streamify.app.data.supabase.AuthManager.signInWithGoogle(context)
                                        if (res.isSuccess) {
                                            android.widget.Toast.makeText(context, "Welcome, ${res.getOrNull()?.displayName}!", android.widget.Toast.LENGTH_SHORT).show()
                                        } else {
                                            android.widget.Toast.makeText(context, "Sign-in note: ${res.exceptionOrNull()?.message}", android.widget.Toast.LENGTH_LONG).show()
                                        }
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = StreamifyColors.Primary),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Filled.AccountCircle, contentDescription = null, tint = androidx.compose.ui.graphics.Color.Black)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Continue with Google (1-Tap)", style = StreamifyType.TitleSmall, color = androidx.compose.ui.graphics.Color.Black)
                            }
                        } else {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onNavigateToProfile() },
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (user?.avatarUrl.isNullOrBlank()) {
                                        Box(
                                            modifier = Modifier
                                                .size(44.dp)
                                                .clip(CircleShape)
                                                .background(StreamifyColors.Primary.copy(alpha = 0.2f)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(user?.displayName?.take(1)?.uppercase() ?: "U", style = StreamifyType.TitleMedium, color = StreamifyColors.Primary)
                                        }
                                    } else {
                                        coil.compose.AsyncImage(
                                            model = user?.avatarUrl,
                                            contentDescription = null,
                                            modifier = Modifier
                                                .size(44.dp)
                                                .clip(CircleShape)
                                        )
                                    }

                                    Spacer(modifier = Modifier.width(12.dp))

                                    Column {
                                        Text(user?.displayName ?: "User", style = StreamifyType.TitleMedium, color = StreamifyColors.TextMain)
                                        Text("Tap to edit profile & view stats", style = StreamifyType.Caption, color = StreamifyColors.Primary)
                                    }
                                }

                                TextButton(onClick = { com.streamify.app.data.supabase.SupabaseClient.signOut() }) {
                                    Text("Sign Out", color = StreamifyColors.ErrorRed)
                                }
                            }

                            Spacer(modifier = Modifier.height(14.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedButton(
                                    onClick = onNavigateToWrapped,
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("Wrapped 2026", style = StreamifyType.CaptionBold)
                                }
                                OutlinedButton(
                                    onClick = onNavigateToCommunity,
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("Community Hub", style = StreamifyType.CaptionBold)
                                }
                            }

                            // Special Admin Command Center Card
                            if (com.streamify.app.data.supabase.SupabaseClient.isAdmin || user?.isAdmin == true) {
                                Spacer(modifier = Modifier.height(16.dp))
                                Button(
                                    onClick = onNavigateToAdmin,
                                    colors = ButtonDefaults.buttonColors(containerColor = StreamifyColors.Primary),
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Icon(Icons.Filled.AdminPanelSettings, contentDescription = null, tint = androidx.compose.ui.graphics.Color.Black)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Open Admin Command Center", style = StreamifyType.TitleSmall, color = androidx.compose.ui.graphics.Color.Black)
                                }
                            }

                            // Diagnostic Terminal — available to EVERYONE.
                            // Capture is opt-in (switch inside the terminal) and
                            // self-disables after 2 hours.
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedButton(
                                onClick = onNavigateToTerminal,
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(
                                    Icons.Filled.Terminal,
                                    contentDescription = null,
                                    tint = androidx.compose.ui.graphics.Color(0xFF4AF626)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Diagnostic Terminal", style = StreamifyType.TitleSmall)
                            }
                        }
                    }
                }
            }

            item {
                SectionHeader("Audio Quality")
                Spacer(modifier = Modifier.height(StreamifyDimens.SpaceMD))
                
                Card(
                    colors = CardDefaults.cardColors(containerColor = StreamifyColors.BgCard),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "Streaming & Download Bitrate",
                            style = StreamifyType.BodyMedium,
                            color = StreamifyColors.TextMain
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Higher quality produces richer sound clarity but uses more storage space.",
                            style = StreamifyType.Caption,
                            color = StreamifyColors.TextSub
                        )
                        Spacer(modifier = Modifier.height(12.dp))

                        val qualityOptions = listOf(
                            Pair("128", "Low (128 kbps) - Fast download, low data"),
                            Pair("192", "Normal (192 kbps) - Standard quality"),
                            Pair("256", "High (256 kbps) - High fidelity audio"),
                            Pair("320", "Extreme (320 kbps) - Maximum audio clarity (Default)")
                        )

                        qualityOptions.forEach { (kbps, label) ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        selectedQuality = kbps
                                        audioPrefs.edit().putString("download_quality", kbps).apply()
                                    }
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = label,
                                    style = StreamifyType.BodyMedium,
                                    color = if (selectedQuality == kbps) StreamifyColors.Primary else StreamifyColors.TextMain,
                                    modifier = Modifier.weight(1f)
                                )
                                RadioButton(
                                    selected = (selectedQuality == kbps),
                                    onClick = {
                                        selectedQuality = kbps
                                        audioPrefs.edit().putString("download_quality", kbps).apply()
                                    },
                                    colors = RadioButtonDefaults.colors(
                                        selectedColor = StreamifyColors.Primary,
                                        unselectedColor = StreamifyColors.TextSub
                                    )
                                )
                            }
                        }
                    }
                }
            }

            item {
                // ── AUDIO QUALITY & DSP (Gaps #39 & #41) ────────────────────
                // Loudness normalization (-23…-11 LUFS, default -14), gapless,
                // crossfade 0–12s, mono + stereo balance, true-peak limiter at
                // -1.0 dBFS — all applied to the live render path through the
                // frozen NativeDspEngine JNI contract.
                SectionHeader("Audio Quality & DSP")
                Spacer(modifier = Modifier.height(StreamifyDimens.SpaceMD))
                DspSettingsSection()
                Spacer(modifier = Modifier.height(StreamifyDimens.SpaceMD))
            }

            item {
                // ── RUST AUDIO DOGFOOD (Phase 5 / MP-CUT01) ────────────────
                // Debug builds only: the USE_RUST_AUDIO two-week dogfood
                // window's observability surface — route decisions, underrun
                // rate, DAC drift, cold-start latency and the zero-crash
                // fallback tally, rendered by the SHARED CMP component
                // (:shared-android — MP-UI03's Android consumption edge).
                // R8 removes the dead branch in release builds entirely.
                if (BuildConfig.DEBUG) {
                    SectionHeader("Rust Audio Dogfood (MP-CUT01)")
                    Spacer(modifier = Modifier.height(StreamifyDimens.SpaceMD))
                    RustAudioDogfoodSection()
                    Spacer(modifier = Modifier.height(StreamifyDimens.SpaceMD))
                }
            }

            item {
                SectionHeader("Storage & Integration")
                Spacer(modifier = Modifier.height(StreamifyDimens.SpaceMD))
                
                var isLocalAudioEnabled by remember { mutableStateOf(audioPrefs.getBoolean("enable_local_audio", false)) }

                Card(
                    colors = CardDefaults.cardColors(containerColor = StreamifyColors.BgCard),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Enable Local Device Audio", style = StreamifyType.BodyMedium, color = StreamifyColors.TextMain)
                            Text("Scan and display MP3 files from device storage. When disabled, Streamify runs 100% cloud-first with instant multi-device synchronization.", style = StreamifyType.Caption, color = StreamifyColors.TextSub)
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Switch(
                            checked = isLocalAudioEnabled,
                            onCheckedChange = { checked ->
                                isLocalAudioEnabled = checked
                                audioPrefs.edit().putBoolean("enable_local_audio", checked).apply()
                                scope.launch(Dispatchers.IO) {
                                    TrackRepository.refresh()
                                }
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = androidx.compose.ui.graphics.Color.Black,
                                checkedTrackColor = StreamifyColors.Primary
                            )
                        )
                    }
                }
            }

            item {
                SectionHeader("Audio Effects")
                Spacer(modifier = Modifier.height(StreamifyDimens.SpaceMD))
                
                Card(
                    colors = CardDefaults.cardColors(containerColor = StreamifyColors.BgCard),
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onNavigateToEq
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("Equalizer", style = StreamifyType.BodyMedium, color = StreamifyColors.TextMain)
                            Text("Tune bass, treble, and surround sound", style = StreamifyType.Caption, color = StreamifyColors.TextSub)
                        }
                        Icon(androidx.compose.material.icons.Icons.Filled.ArrowForward, contentDescription = null, tint = StreamifyColors.TextSub)
                    }
                }
            }

            item {
                SectionHeader("Playback")
                Spacer(modifier = Modifier.height(StreamifyDimens.SpaceMD))
                
                Card(
                    colors = CardDefaults.cardColors(containerColor = StreamifyColors.BgCard),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Crossfade", style = StreamifyType.BodyMedium, color = StreamifyColors.TextMain)
                            Text("${crossfadeValue.toInt()}s", style = StreamifyType.BodyMedium, color = StreamifyColors.TextSub)
                        }
                        Slider(
                            value = crossfadeValue,
                            onValueChange = { 
                                crossfadeValue = it
                                CrossfadeAudioProcessor.crossfadeDurationMs = (it * 1000).toLong()
                                audioPrefs.edit().putFloat("crossfade_val", it).apply()
                            },
                            valueRange = 0f..12f,
                            steps = 11,
                            colors = SliderDefaults.colors(
                                thumbColor = StreamifyColors.Primary,
                                activeTrackColor = StreamifyColors.Primary,
                                inactiveTrackColor = StreamifyColors.TextSub.copy(alpha = 0.3f)
                            )
                        )
                        Text("Allows a smooth transition between songs.", style = StreamifyType.Caption, color = StreamifyColors.TextSub)
                    }
                }
            }

            item {
                SectionHeader("Sleep Timer")
                Spacer(modifier = Modifier.height(StreamifyDimens.SpaceMD))
                
                Card(
                    colors = CardDefaults.cardColors(containerColor = StreamifyColors.BgCard),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        if (playerState.sleepTimerMinutesLeft != null || playerState.sleepTimerEndTrack) {
                            val status = if (playerState.sleepTimerEndTrack) "End of track" else "${playerState.sleepTimerMinutesLeft} min"
                            Text("Timer active: $status", style = StreamifyType.BodyMedium, color = StreamifyColors.Primary)
                            Spacer(modifier = Modifier.height(16.dp))
                        }

                        val options = listOf("Off", "15 minutes", "30 minutes", "45 minutes", "1 hour", "End of track")
                        options.forEach { option ->
                            TextButton(
                                onClick = {
                                    when (option) {
                                        "Off" -> playerViewModel.setSleepTimer(null, false)
                                        "15 minutes" -> playerViewModel.setSleepTimer(15)
                                        "30 minutes" -> playerViewModel.setSleepTimer(30)
                                        "45 minutes" -> playerViewModel.setSleepTimer(45)
                                        "1 hour" -> playerViewModel.setSleepTimer(60)
                                        "End of track" -> playerViewModel.setSleepTimer(null, true)
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = option,
                                    modifier = Modifier.fillMaxWidth(),
                                    color = StreamifyColors.TextMain,
                                    style = StreamifyType.BodyMedium
                                )
                            }
                        }
                    }
                }
            }
            item {
                SectionHeader("Playback & Acoustic Dynamics")
                Spacer(modifier = Modifier.height(StreamifyDimens.SpaceMD))

                Card(
                    colors = CardDefaults.cardColors(containerColor = StreamifyColors.BgCard),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        // Dolby Atmos & Spatial Audio Hardware Statement
                        val isDolbyDetected by com.streamify.app.media.audio.DolbySpatialManager.isDolbyAtmosDetected.collectAsState()
                        val dolbyDetail by com.streamify.app.media.audio.DolbySpatialManager.hardwareDetail.collectAsState()

                        LaunchedEffect(Unit) {
                            com.streamify.app.media.audio.DolbySpatialManager.checkHardwareCapabilities(context)
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isDolbyDetected) StreamifyColors.Primary.copy(alpha = 0.12f) else StreamifyColors.BgElevated)
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(if (isDolbyDetected) StreamifyColors.Primary else StreamifyColors.BgSurfaceElevated),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = if (isDolbyDetected) Icons.Filled.CheckCircle else Icons.Filled.Warning,
                                    contentDescription = null,
                                    tint = if (isDolbyDetected) androidx.compose.ui.graphics.Color.White else StreamifyColors.TextSub,
                                    modifier = Modifier.size(20.dp)
                                )

                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = if (isDolbyDetected) "Dolby Atmos / Spatializer" else "Dolby Atmos Hardware",
                                        style = StreamifyType.BodyMedium.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold),
                                        color = if (isDolbyDetected) StreamifyColors.Primary else StreamifyColors.TextMain
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(if (isDolbyDetected) StreamifyColors.Primary else StreamifyColors.BgSurfaceElevated)
                                            .padding(horizontal = 5.dp, vertical = 2.dp)
                                    ) {
                                        Text(
                                            text = if (isDolbyDetected) "DETECTED" else "NOT DETECTED",
                                            style = StreamifyType.Caption.copy(
                                                fontSize = 9.sp,
                                                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                                            ),
                                            color = if (isDolbyDetected) androidx.compose.ui.graphics.Color.White else StreamifyColors.TextSub
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = dolbyDetail,
                                    style = StreamifyType.Caption,
                                    color = if (isDolbyDetected) StreamifyColors.TextMain else StreamifyColors.TextSub
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        val isLoudnessOn by com.streamify.app.media.audio.EqualizerManager.isLoudnessNormalizationEnabled.collectAsState()
                        var autoDownloadLiked by remember {
                            mutableStateOf(audioPrefs.getBoolean("auto_download_liked", false))
                        }

                        // Loudness Normalization
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {

                            Column(modifier = Modifier.weight(1f)) {
                                Text("Loudness Normalization", style = StreamifyType.BodyMedium, color = StreamifyColors.TextMain)
                                Text("Normalizes volume across tracks (-14 LUFS standard)", style = StreamifyType.Caption, color = StreamifyColors.TextSub)
                            }
                            Switch(
                                checked = isLoudnessOn,
                                onCheckedChange = { com.streamify.app.media.audio.EqualizerManager.setLoudnessNormalization(it) },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = StreamifyColors.Primary,
                                    checkedTrackColor = StreamifyColors.Primary.copy(alpha = 0.5f)
                                )
                            )
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        // Auto-Download Liked Songs
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Auto-Download Liked Songs", style = StreamifyType.BodyMedium, color = StreamifyColors.TextMain)
                                Text("Automatically saves liked online songs for offline listening", style = StreamifyType.Caption, color = StreamifyColors.TextSub)
                            }
                            Switch(
                                checked = autoDownloadLiked,
                                onCheckedChange = {
                                    autoDownloadLiked = it
                                    audioPrefs.edit().putBoolean("auto_download_liked", it).apply()
                                },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = StreamifyColors.Primary,
                                    checkedTrackColor = StreamifyColors.Primary.copy(alpha = 0.5f)
                                )
                            )
                        }
                    }
                }
            }

            item {
                SectionHeader("Storage & 5-Tier Cache")
                Spacer(modifier = Modifier.height(StreamifyDimens.SpaceMD))

                var storageInfo by remember { mutableStateOf<com.streamify.app.data.persistence.StorageBreakdown?>(null) }
                val scope = rememberCoroutineScope()

                LaunchedEffect(Unit) {
                    storageInfo = com.streamify.app.data.persistence.StorageManager.getStorageBreakdown(context)
                }

                Card(
                    colors = CardDefaults.cardColors(containerColor = StreamifyColors.BgCard),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text("Audio Stream Cache", style = StreamifyType.BodyMedium, color = StreamifyColors.TextMain)
                                Text("250 MB LRU progressive buffer", style = StreamifyType.Caption, color = StreamifyColors.TextSub)
                            }
                            Text(
                                text = com.streamify.app.data.persistence.StorageManager.formatBytes(storageInfo?.audioCacheBytes ?: 0L),
                                style = StreamifyType.BodyMedium,
                                color = StreamifyColors.TextMain
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text("Cover Art Disk Cache", style = StreamifyType.BodyMedium, color = StreamifyColors.TextMain)
                                Text("100 MB HD image cache", style = StreamifyType.Caption, color = StreamifyColors.TextSub)
                            }
                            Text(
                                text = com.streamify.app.data.persistence.StorageManager.formatBytes(storageInfo?.imageCacheBytes ?: 0L),
                                style = StreamifyType.BodyMedium,
                                color = StreamifyColors.TextMain
                            )
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        Button(
                            onClick = {
                                scope.launch {
                                    com.streamify.app.data.persistence.StorageManager.clearAllCache(context)
                                    storageInfo = com.streamify.app.data.persistence.StorageManager.getStorageBreakdown(context)
                                    android.widget.Toast.makeText(context, "Cache cleared successfully", android.widget.Toast.LENGTH_SHORT).show()
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = StreamifyColors.BgElevated),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Clear All Cache (${com.streamify.app.data.persistence.StorageManager.formatBytes(storageInfo?.totalCacheBytes ?: 0L)})", color = StreamifyColors.Primary)
                        }
                    }
                }
            }

            item {
                SectionHeader("Backup & Data Migration")
                Spacer(modifier = Modifier.height(StreamifyDimens.SpaceMD))

                val scope = rememberCoroutineScope()
                val importLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
                    contract = androidx.activity.result.contract.ActivityResultContracts.GetContent()
                ) { uri ->
                    uri?.let {
                        scope.launch {
                            try {
                                val jsonStr = context.contentResolver.openInputStream(it)?.bufferedReader().use { r -> r?.readText() } ?: ""
                                val result = com.streamify.app.data.persistence.BackupManager.importLibraryBackup(context, jsonStr)
                                if (result.isSuccess) {
                                    android.widget.Toast.makeText(context, "Restored ${result.getOrNull()} items successfully", android.widget.Toast.LENGTH_LONG).show()
                                } else {
                                    android.widget.Toast.makeText(context, "Failed to restore backup", android.widget.Toast.LENGTH_SHORT).show()
                                }
                            } catch (e: Exception) {
                                SLog.st("SettingsScreen", "SettingsScreen backup-restore failed", e)
                            }
                        }
                    }
                }

                Card(
                    colors = CardDefaults.cardColors(containerColor = StreamifyColors.BgCard),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    scope.launch {
                                        val res = com.streamify.app.data.persistence.BackupManager.exportLibraryBackup(context)
                                        if (res.isSuccess) {
                                            android.widget.Toast.makeText(context, "Backup exported to Documents/Streamify", android.widget.Toast.LENGTH_LONG).show()
                                        } else {
                                            android.widget.Toast.makeText(context, "Export failed", android.widget.Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                }
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text("Export Library Backup", style = StreamifyType.BodyMedium, color = StreamifyColors.TextMain)
                                Text("Save liked songs & playlists to JSON file", style = StreamifyType.Caption, color = StreamifyColors.TextSub)
                            }
                            Icon(Icons.Filled.ArrowForward, contentDescription = null, tint = StreamifyColors.TextSub)
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { importLauncher.launch("application/json") }
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text("Restore Library from JSON", style = StreamifyType.BodyMedium, color = StreamifyColors.TextMain)
                                Text("Import tracks and playlists from backup", style = StreamifyType.Caption, color = StreamifyColors.TextSub)
                            }
                            Icon(Icons.Filled.ArrowForward, contentDescription = null, tint = StreamifyColors.TextSub)
                        }
                    }
                }
            }

            item {
                SectionHeader("Danger Zone")
                Spacer(modifier = Modifier.height(StreamifyDimens.SpaceMD))

                var showConfirmDialog by remember { mutableStateOf(false) }
                val nukeState by com.streamify.app.data.persistence.NuclearResetManager.nukeState.collectAsState()
                val isNuking = nukeState !is com.streamify.app.data.persistence.NukeState.Idle && nukeState !is com.streamify.app.data.persistence.NukeState.Error

                Card(
                    colors = CardDefaults.cardColors(containerColor = androidx.compose.ui.graphics.Color(0xFF200A0A)),
                    shape = RoundedCornerShape(16.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, androidx.compose.ui.graphics.Color(0xFFE53935).copy(alpha = 0.5f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Filled.Warning,
                                contentDescription = "Danger",
                                tint = androidx.compose.ui.graphics.Color(0xFFE53935),
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                "Nuke Database & Start Fresh",
                                style = StreamifyType.HeadlineSmall.copy(fontSize = 16.sp),
                                color = androidx.compose.ui.graphics.Color.White,
                                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            "Atomically wipes all local offline tracks, metadata, and acoustic embeddings. Your liked songs and custom playlists are backed up to the cloud first, and the feed is instantly re-seeded with fresh global trending tracks.",
                            style = StreamifyType.Caption,
                            color = androidx.compose.ui.graphics.Color(0xFFEF9A9A)
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        Button(
                            onClick = { showConfirmDialog = true },
                            enabled = !isNuking,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = androidx.compose.ui.graphics.Color(0xFFD32F2F),
                                disabledContainerColor = androidx.compose.ui.graphics.Color(0xFF5C1D1D)
                            ),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                "Nuke Database & Clean Start",
                                color = androidx.compose.ui.graphics.Color.White,
                                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                            )
                        }
                    }
                }

                if (showConfirmDialog) {
                    AlertDialog(
                        onDismissRequest = { showConfirmDialog = false },
                        title = {
                            Text(
                                "Confirm Nuclear Purge",
                                color = StreamifyColors.TextMain,
                                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                            )
                        },
                        text = {
                            Text(
                                "Are you sure you want to purge all local databases? Your cloud profile will be synced first to prevent data loss, and fresh trending charts will populate your feed immediately.",
                                color = StreamifyColors.TextSub
                            )
                        },
                        confirmButton = {
                            Button(
                                onClick = {
                                    showConfirmDialog = false
                                    scope.launch {
                                        com.streamify.app.data.persistence.NuclearResetManager.executeNuclearReset(context)
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = androidx.compose.ui.graphics.Color(0xFFD32F2F))
                            ) {
                                Text("Yes, Nuke & Rebirth", color = androidx.compose.ui.graphics.Color.White, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { showConfirmDialog = false }) {
                                Text("Cancel", color = StreamifyColors.TextSub)
                            }
                        },
                        containerColor = StreamifyColors.BgSurfaceElevated,
                        shape = RoundedCornerShape(16.dp)
                    )
                }
            }

            item {
                SectionHeader("App Updates & CI/CD Builds")
                Spacer(modifier = Modifier.height(StreamifyDimens.SpaceMD))
                
                val updateState by StreamifyUpdateManager.updateState.collectAsState()
                val isChecking = updateState is UpdateState.Checking

                Card(
                    colors = CardDefaults.cardColors(containerColor = StreamifyColors.BgCard),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Installed Version", style = StreamifyType.BodyMedium, color = StreamifyColors.TextMain)
                                Text(
                                    "Build ${BuildConfig.VERSION_CODE} • v${BuildConfig.VERSION_NAME}",
                                    style = StreamifyType.Caption,
                                    color = StreamifyColors.TextSub
                                )
                            }
                            
                            Button(
                                onClick = {
                                    scope.launch {
                                        StreamifyUpdateManager.checkForUpdates(context, isManual = true)
                                    }
                                },
                                enabled = !isChecking,
                                colors = ButtonDefaults.buttonColors(containerColor = StreamifyColors.Primary),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                if (isChecking) {
                                    CircularProgressIndicator(
                                        color = androidx.compose.ui.graphics.Color.Black,
                                        strokeWidth = 2.dp,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Checking...", style = StreamifyType.TitleSmall, color = androidx.compose.ui.graphics.Color.Black)
                                } else {
                                    Icon(
                                        imageVector = Icons.Filled.Refresh,
                                        contentDescription = null,
                                        tint = androidx.compose.ui.graphics.Color.Black,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Check Updates", style = StreamifyType.TitleSmall, color = androidx.compose.ui.graphics.Color.Black)
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))
                        HorizontalDivider(color = StreamifyColors.Border, thickness = 0.5.dp)
                        Spacer(modifier = Modifier.height(10.dp))

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    StreamifyUpdateManager.openReleasePage(context, "https://github.com/zephyr4289/streamify-apk/releases")
                                }
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.OpenInBrowser, contentDescription = null, tint = StreamifyColors.TextSub, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("View GitHub Releases & CI Builds", style = StreamifyType.BodyMedium, color = StreamifyColors.TextMain)
                            }
                            Icon(Icons.Filled.ArrowForward, contentDescription = null, tint = StreamifyColors.TextSub, modifier = Modifier.size(16.dp))
                        }
                    }
                }

                // Interactive Dialogs for Manual Updates
                when (val st = updateState) {
                    is UpdateState.UpdateAvailable -> {
                        val buildInfo = st.buildInfo
                        AlertDialog(
                            onDismissRequest = { StreamifyUpdateManager.resetState() },
                            title = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Filled.SystemUpdate, contentDescription = null, tint = StreamifyColors.Primary)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("New Build Available!", color = StreamifyColors.TextMain, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                                }
                            },
                            text = {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(
                                        "Streamify Build ${buildInfo.buildNumber} is now available (Current: Build ${BuildConfig.VERSION_CODE}).",
                                        color = StreamifyColors.TextMain,
                                        style = StreamifyType.BodyMedium
                                    )
                                    if (buildInfo.changelog.isNotBlank()) {
                                        Text(
                                            "Changelog:\n${buildInfo.changelog}",
                                            color = StreamifyColors.TextSub,
                                            style = StreamifyType.Caption
                                        )
                                    }
                                }
                            },
                            confirmButton = {
                                Button(
                                    onClick = {
                                        StreamifyUpdateManager.dispatchUpdate(context, buildInfo)
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = StreamifyColors.Primary)
                                ) {
                                    Icon(Icons.Filled.Download, contentDescription = null, tint = androidx.compose.ui.graphics.Color.Black, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Download Update", color = androidx.compose.ui.graphics.Color.Black, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                                }
                            },
                            dismissButton = {
                                Row {
                                    TextButton(onClick = {
                                        StreamifyUpdateManager.openReleasePage(context, buildInfo.releaseHtmlUrl)
                                    }) {
                                        Text("Release Page", color = StreamifyColors.TextSub)
                                    }
                                    TextButton(onClick = { StreamifyUpdateManager.resetState() }) {
                                        Text("Dismiss", color = StreamifyColors.TextSub)
                                    }
                                }
                            },
                            containerColor = StreamifyColors.BgSurfaceElevated,
                            shape = RoundedCornerShape(16.dp)
                        )
                    }
                    is UpdateState.UpToDate -> {
                        if (st.isManual) {
                            AlertDialog(
                                onDismissRequest = { StreamifyUpdateManager.resetState() },
                                title = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = androidx.compose.ui.graphics.Color(0xFF1DB954))
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text("Up to Date", color = StreamifyColors.TextMain, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                                    }
                                },
                                text = {
                                    Text(
                                        "You are on the latest build (Build ${st.currentBuild}). No new updates found! 🎉",
                                        color = StreamifyColors.TextSub,
                                        style = StreamifyType.BodyMedium
                                    )
                                },
                                confirmButton = {
                                    TextButton(onClick = { StreamifyUpdateManager.resetState() }) {
                                        Text("OK", color = StreamifyColors.Primary, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                                    }
                                },
                                containerColor = StreamifyColors.BgSurfaceElevated,
                                shape = RoundedCornerShape(16.dp)
                            )
                        }
                    }
                    is UpdateState.Error -> {
                        if (st.isManual) {
                            AlertDialog(
                                onDismissRequest = { StreamifyUpdateManager.resetState() },
                                title = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Filled.Warning, contentDescription = null, tint = androidx.compose.ui.graphics.Color(0xFFD32F2F))
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text("Update Check Failed", color = StreamifyColors.TextMain, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                                    }
                                },
                                text = {
                                    Text(
                                        st.message,
                                        color = StreamifyColors.TextSub,
                                        style = StreamifyType.BodyMedium
                                    )
                                },
                                confirmButton = {
                                    TextButton(onClick = { StreamifyUpdateManager.resetState() }) {
                                        Text("Dismiss", color = StreamifyColors.Primary)
                                    }
                                },
                                containerColor = StreamifyColors.BgSurfaceElevated,
                                shape = RoundedCornerShape(16.dp)
                            )
                        }
                    }
                    else -> {}
                }
            }

            item {
                SectionHeader("About")
                Spacer(modifier = Modifier.height(StreamifyDimens.SpaceMD))
                
                Card(
                    colors = CardDefaults.cardColors(containerColor = StreamifyColors.BgCard),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Edition", style = StreamifyType.BodyMedium, color = StreamifyColors.TextMain)
                            Text("Flagship Open Source", style = StreamifyType.Caption, color = StreamifyColors.TextSub)
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Build Number", style = StreamifyType.BodyMedium, color = StreamifyColors.TextMain)
                            Text("Build ${BuildConfig.VERSION_CODE}", style = StreamifyType.Caption, color = StreamifyColors.Primary)
                        }
                    }
                }
            }
        }

        // Full-screen Nuclear Progress Overlay
        val nukeState by com.streamify.app.data.persistence.NuclearResetManager.nukeState.collectAsState()
        if (nukeState !is com.streamify.app.data.persistence.NukeState.Idle) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.85f)),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    when (nukeState) {
                        is com.streamify.app.data.persistence.NukeState.BackingUp -> {
                            CircularProgressIndicator(color = androidx.compose.ui.graphics.Color.White, strokeWidth = 3.dp)
                            Text("Backing up to Cloud DB...", color = androidx.compose.ui.graphics.Color.White, style = StreamifyType.BodyLarge)
                        }
                        is com.streamify.app.data.persistence.NukeState.Purging -> {
                            CircularProgressIndicator(color = androidx.compose.ui.graphics.Color(0xFFD32F2F), strokeWidth = 3.dp)
                            Text("Purging Local SQLite & Caches...", color = androidx.compose.ui.graphics.Color.White, style = StreamifyType.BodyLarge)
                        }
                        is com.streamify.app.data.persistence.NukeState.Seeding -> {
                            CircularProgressIndicator(color = androidx.compose.ui.graphics.Color(0xFF1DB954), strokeWidth = 3.dp)
                            Text("Re-seeding Fresh Content...", color = androidx.compose.ui.graphics.Color.White, style = StreamifyType.BodyLarge)
                        }
                        is com.streamify.app.data.persistence.NukeState.Success -> {
                            Icon(
                                Icons.Filled.CheckCircle,
                                contentDescription = "Done",
                                tint = androidx.compose.ui.graphics.Color(0xFF1DB954),
                                modifier = Modifier.size(48.dp)
                            )
                            Text("Clean Start Complete! 🚀", color = androidx.compose.ui.graphics.Color.White, style = StreamifyType.BodyLarge)
                        }
                        is com.streamify.app.data.persistence.NukeState.Error -> {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = "Error",
                                tint = androidx.compose.ui.graphics.Color(0xFFD32F2F),
                                modifier = Modifier.size(48.dp)
                            )
                            Text(
                                (nukeState as com.streamify.app.data.persistence.NukeState.Error).message,
                                color = androidx.compose.ui.graphics.Color.White,
                                style = StreamifyType.BodyLarge
                            )
                            Button(onClick = { com.streamify.app.data.persistence.NuclearResetManager.resetState() }) {
                                Text("Dismiss")
                            }
                        }
                        else -> {}
                    }
                }
            }
        }

        // Connect Accounts Modal Bottom Sheet
        com.streamify.app.ui.components.ConnectAccountsSheet(
            isOpen = showConnectAccountsSheet,
            onDismiss = { showConnectAccountsSheet = false }
        )
    }
}
