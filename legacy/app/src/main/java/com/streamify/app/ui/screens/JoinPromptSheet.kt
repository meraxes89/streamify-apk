package com.streamify.app.ui.screens

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.streamify.app.jam.JoinFabric
import com.streamify.app.ui.theme.*
import com.streamify.app.viewmodel.JamViewModel
import com.streamify.app.viewmodel.PlayerViewModel

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * ZERO-FRICTION JOIN PROMPTS (BEHIND.md Gap #12, Phase 1)
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * Sleek auto-prompt surface for the Home screen, fed by the three Join
 * Fabric rails:
 *
 *  • BLE tap-to-join      → "Join [Host]'s Jam" — one tap, zero PIN
 *  • LAN room announce    → "[Host] is listening together" — one tap
 *  • LAN mesh beacon 0x09 → "A Jam is active on this Wi-Fi" — deep-links
 *                           into the join form
 *  • Speaker connect      → "Start a Jam on this speaker" — one tap hosting
 *
 * BLE permission is requested in-flow (Android 12+: BLUETOOTH_SCAN with
 * neverForLocation; ≤11: fine location) — denial degrades the BLE rail to
 * a silent no-op; LAN rails need no permission and keep working.
 *
 * LIFECYCLE: the sheet renders ONLY when a live sighting exists — it is a
 * transient prompt, not persistent UI. Dismissal is remembered per room.
 */
@Composable
fun JoinPromptRail(
    jamViewModel: JamViewModel,
    playerViewModel: PlayerViewModel,
    onOpenJamScreen: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val sightings by JoinFabric.sightings.collectAsState()
    val speakerPrompt by JoinFabric.speakerPrompt.collectAsState()

    // ── BLE permission flow (in-prompt, one ask) ──────────────────────────
    var blePermissionAsked by remember { mutableStateOf(false) }
    val blePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        // Whatever landed, restart discovery — the LAN rails need nothing
        // and the BLE rail activates iff the grant arrived.
        JoinFabric.startDiscovery()
    }
    LaunchedEffect(Unit) {
        if (!blePermissionAsked &&
            !JoinFabric.canBleScan(context) &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        ) {
            blePermissionAsked = true
            blePermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_ADVERTISE,
                    Manifest.permission.BLUETOOTH_CONNECT
                )
            )
        }
    }

    // ── Auto-prompt: newest sighting wins ─────────────────────────────────
    val sighting = sightings.firstOrNull()

    if (sighting != null) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Surface(
                color = BgSurfaceElevated,
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Pulse dot — a live room is on the air
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Primary.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = when (sighting.source) {
                                JoinFabric.Source.BLE_TAP -> Icons.Filled.PhoneAndroid
                                JoinFabric.Source.LAN_BEACON,
                                JoinFabric.Source.LAN_ANNOUNCE -> Icons.Filled.Wifi
                                JoinFabric.Source.SPEAKER_CONNECT -> Icons.Filled.Speaker
                            },
                            contentDescription = null,
                            tint = Primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = when (sighting.source) {
                                JoinFabric.Source.BLE_TAP -> "Join ${sighting.hostName.ifBlank { "the host" }}'s Jam"
                                JoinFabric.Source.LAN_BEACON -> "A Streamify Jam is live"
                                JoinFabric.Source.LAN_ANNOUNCE -> "Join ${sighting.hostName.ifBlank { "the host" }}'s Jam"
                                JoinFabric.Source.SPEAKER_CONNECT -> "Start a Jam on this speaker"
                            },
                            style = LocalAppTypography.current.songTitle.copy(fontSize = 14.sp),
                            color = TextMain,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = sighting.subtitle,
                            style = LocalAppTypography.current.songArtist.copy(fontSize = 11.sp),
                            color = TextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { JoinFabric.dismiss(sighting) }, modifier = Modifier.size(34.dp)) {
                            Icon(Icons.Filled.Close, contentDescription = "Dismiss", tint = TextTertiary, modifier = Modifier.size(16.dp))
                        }
                        Spacer(modifier = Modifier.width(2.dp))
                        Button(
                            onClick = {
                                val code = sighting.sessionCode
                                if (code != null) {
                                    // One-tap zero-PIN join (BLE / LAN announce).
                                    jamViewModel.joinJam(code, playerViewModel)
                                    JoinFabric.dismiss(sighting)
                                    onOpenJamScreen()
                                } else {
                                    // LAN beacon sighting: open the Jam screen's
                                    // join form (QR/PIN path stays the fallback).
                                    JoinFabric.dismiss(sighting)
                                    onOpenJamScreen()
                                }
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = ActiveControl,
                                contentColor = TextOnActiveChip
                            ),
                            shape = RoundedCornerShape(20.dp),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                        ) {
                            Text("JOIN", fontSize = 12.sp, fontWeight = FontWeight.Black, letterSpacing = 0.6.sp)
                        }
                    }
                }
            }
        }
    }

    // ── Speaker-connect prompt (host affordance) ──────────────────────────
    val speaker = speakerPrompt
    if (speaker != null && sighting == null) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Surface(
                color = Color(0xFF38BDF8).copy(alpha = 0.10f),
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFF38BDF8).copy(alpha = 0.16f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Filled.Speaker, contentDescription = null, tint = Color(0xFF38BDF8), modifier = Modifier.size(20.dp))
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Connected to ${speaker.deviceName}",
                            style = LocalAppTypography.current.songTitle.copy(fontSize = 14.sp),
                            color = TextMain,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "Host a Jam everyone controls from their phone",
                            style = LocalAppTypography.current.songArtist.copy(fontSize = 11.sp),
                            color = TextSecondary,
                            maxLines = 1
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { JoinFabric.dismissSpeakerPrompt() }, modifier = Modifier.size(34.dp)) {
                            Icon(Icons.Filled.Close, contentDescription = "Dismiss", tint = TextTertiary, modifier = Modifier.size(16.dp))
                        }
                        Spacer(modifier = Modifier.width(2.dp))
                        Button(
                            onClick = {
                                JoinFabric.dismissSpeakerPrompt()
                                onOpenJamScreen()
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF38BDF8),
                                contentColor = Color(0xFF02202E)
                            ),
                            shape = RoundedCornerShape(20.dp),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                        ) {
                            Text("START", fontSize = 12.sp, fontWeight = FontWeight.Black, letterSpacing = 0.6.sp)
                        }
                    }
                }
            }
        }
    }
}
