package com.streamify.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.streamify.app.media.audio.EqualizerManager
import com.streamify.app.ui.components.yt.YtPresetFilterChips
import com.streamify.app.ui.components.yt.YtStudioArcDial
import com.streamify.app.ui.components.yt.YtVerticalEqSlider
import com.streamify.app.ui.theme.*

@Composable
fun EqualizerScreen(onBack: () -> Unit) {
    val isEqEnabled by EqualizerManager.isEqEnabled.collectAsState()
    val activePresetName by EqualizerManager.activePresetName.collectAsState()
    val isLoudnessEnabled by EqualizerManager.isLoudnessNormalizationEnabled.collectAsState()
    val bands by EqualizerManager.bands.collectAsState()
    val bassStrength by EqualizerManager.bassStrength.collectAsState()
    val virtualizerStrength by EqualizerManager.virtualizerStrength.collectAsState()
    val scrollState = rememberScrollState()

    val presetNames = remember {
        EqualizerManager.PRESET_PROFILES.map { it.name }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BgBase)
            .statusBarsPadding()
            .verticalScroll(scrollState)
            .padding(bottom = 120.dp) // Protect docked player
    ) {
        // 1. Top App Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = TextMain,
                        modifier = Modifier.size(24.dp)
                    )
                }
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "Equalizer & DSP Studio",
                    style = LocalAppTypography.current.headlineMedium.copy(fontSize = 18.sp),
                    color = TextMain
                )
            }

            IconButton(
                onClick = { EqualizerManager.applyPresetByName("Flat") },
                enabled = isEqEnabled
            ) {
                Icon(
                    imageVector = Icons.Filled.Refresh,
                    contentDescription = "Reset EQ",
                    tint = if (isEqEnabled) TextSecondary else TextTertiary,
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // 2. Master DSP Power Card
        Surface(
            color = BgSurfaceElevated,
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(
                            text = "Master Equalizer",
                            style = LocalAppTypography.current.titleLarge.copy(fontSize = 15.sp),
                            color = TextMain
                        )
                        Text(
                            text = if (isEqEnabled) "Active: $activePresetName" else "DSP Bypassed",
                            style = LocalAppTypography.current.songArtist.copy(fontSize = 12.sp),
                            color = if (isEqEnabled) ActiveControl else TextSecondary
                        )
                    }

                    Switch(
                        checked = isEqEnabled,
                        onCheckedChange = { EqualizerManager.setEqEnabled(it) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = TextOnActiveChip,
                            checkedTrackColor = ActiveControl,
                            uncheckedThumbColor = TextSecondary,
                            uncheckedTrackColor = BgSurface
                        )
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider(color = Divider)
                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Loudness Normalization",
                        style = LocalAppTypography.current.titleLarge.copy(fontSize = 14.sp),
                        color = TextMain
                    )
                    Switch(
                        checked = isLoudnessEnabled,
                        onCheckedChange = { EqualizerManager.setLoudnessNormalization(it) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = TextOnActiveChip,
                            checkedTrackColor = ActiveControl,
                            uncheckedThumbColor = TextSecondary,
                            uncheckedTrackColor = BgSurface
                        )
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 3. Acoustic Presets Rail
        Text(
            text = "Acoustic Presets",
            style = LocalAppTypography.current.songArtist.copy(
                fontSize = 12.sp,
                letterSpacing = 0.5.sp
            ),
            color = TextSecondary,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
        Spacer(modifier = Modifier.height(4.dp))

        YtPresetFilterChips(
            selectedPreset = activePresetName,
            presets = presetNames,
            enabled = isEqEnabled,
            onPresetSelected = { EqualizerManager.applyPresetByName(it) }
        )

        Spacer(modifier = Modifier.height(16.dp))

        // 4. Multi-Band Graphic Equalizer Matrix
        Text(
            text = "Frequency Sculpting",
            style = LocalAppTypography.current.songArtist.copy(
                fontSize = 12.sp,
                letterSpacing = 0.5.sp
            ),
            color = TextSecondary,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
        Spacer(modifier = Modifier.height(6.dp))

        Surface(
            color = BgSurfaceElevated,
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
        ) {
            if (bands.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Start playing audio to initialize DSP bands",
                        style = LocalAppTypography.current.songArtist,
                        color = TextSecondary
                    )
                }
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 14.dp, horizontal = 4.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.Bottom
                ) {
                    bands.forEachIndexed { index, band ->
                        val freqLabel = if (band.centerFreqHz >= 1000) {
                            "${band.centerFreqHz / 1000}k"
                        } else {
                            "${band.centerFreqHz}Hz"
                        }

                        YtVerticalEqSlider(
                            label = freqLabel,
                            level = band.level,
                            minLevel = EqualizerManager.minEqLevel,
                            maxLevel = EqualizerManager.maxEqLevel,
                            enabled = isEqEnabled,
                            onLevelChange = { newLevel ->
                                EqualizerManager.setBandLevel(index.toShort(), newLevel)
                            },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 5. Studio Rotary Arc Dials (Sub-Bass Boost & 3D Spatial Virtualizer)
        Text(
            text = "Studio Rotary Dials",
            style = LocalAppTypography.current.songArtist.copy(
                fontSize = 12.sp,
                letterSpacing = 0.5.sp
            ),
            color = TextSecondary,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
        Spacer(modifier = Modifier.height(6.dp))

        Surface(
            color = BgSurfaceElevated,
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                YtStudioArcDial(
                    label = "SUB-BASS BOOST",
                    value = (bassStrength / 1000f).coerceIn(0f, 1f),
                    enabled = isEqEnabled,
                    onValueChange = { fraction ->
                        val newStrength = (fraction * 1000).toInt().toShort()
                        EqualizerManager.setBassStrength(newStrength)
                    }
                )

                YtStudioArcDial(
                    label = "3D SPATIAL TUNNEL",
                    value = (virtualizerStrength / 1000f).coerceIn(0f, 1f),
                    enabled = isEqEnabled,
                    onValueChange = { fraction ->
                        val newStrength = (fraction * 1000).toInt().toShort()
                        EqualizerManager.setVirtualizerStrength(newStrength)
                    }
                )
            }
        }
    }
}
