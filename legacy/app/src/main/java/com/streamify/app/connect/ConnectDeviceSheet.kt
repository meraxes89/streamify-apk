package com.streamify.app.connect

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothAudio
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.Watch
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.streamify.app.ui.theme.AccentBlue
import com.streamify.app.ui.theme.BgCard
import com.streamify.app.ui.theme.BgSurfaceElevated
import com.streamify.app.ui.theme.BorderSubtle
import com.streamify.app.ui.theme.TextMain
import com.streamify.app.ui.theme.TextSecondary
import com.streamify.app.ui.theme.TextTertiary

/**
 * ConnectDeviceSheet (Gap #52) — the device picker bottom sheet.
 *
 * Lists every discovered LAN/Cloud/system/Cast target with route-type icon,
 * live connection status and latency badge; the active device row expands
 * into the shared remote volume slider when the route's ACL allows it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectDeviceSheet(
    viewModel: ConnectViewModel,
    snapshotProvider: () -> PlaybackSnapshot,
    onDismiss: () -> Unit
) {
    val rows by viewModel.rows.collectAsState()
    val session by viewModel.session.collectAsState()
    val pending by viewModel.pendingTransfer.collectAsState()

    androidx.compose.runtime.LaunchedEffect(Unit) { viewModel.onSheetOpened() }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = BgSurfaceElevated,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp)
        ) {
            Text(
                text = "Play on a device",
                color = TextMain,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = session.activeDeviceName?.let { "Listening on $it" }
                    ?: "Currently playing on this phone",
                color = TextSecondary,
                fontSize = 13.sp,
                modifier = Modifier.padding(top = 4.dp)
            )

            Spacer(Modifier.height(16.dp))

            rows.forEach { row ->
                DeviceRow(
                    row = row,
                    isActive = session.isRemoteActive && session.activeDevice?.id == row.id,
                    isPending = pending == row.id,
                    showTransferButton = row.isLocal && session.isRemoteActive,
                    onPick = {
                        if (row.isLocal || row.device.supportsHandoff) {
                            viewModel.requestTransfer(row.device, snapshotProvider())
                        }
                    },
                    onVolumeChange = { volume -> viewModel.setRemoteVolume(volume) }
                )
                Spacer(Modifier.height(8.dp))
            }

            if (session.lastError != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = session.lastError ?: "",
                    color = Color(0xFFFF6B6B),
                    fontSize = 12.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun DeviceRow(
    row: ConnectDeviceRow,
    isActive: Boolean,
    isPending: Boolean,
    showTransferButton: Boolean,
    onPick: () -> Unit,
    onVolumeChange: (Float) -> Unit
) {
    val device = row.device
    val acl = ConnectVolumePolicy.forRoute(device.kind)
    var sliderValue by androidx.compose.runtime.remember(row.id, isActive) {
        androidx.compose.runtime.mutableStateOf(1f)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(if (isActive) BgCard else Color.Transparent)
            .clickable(enabled = device.supportsHandoff || device.isLocal) { onPick() }
            .padding(horizontal = 12.dp, vertical = 12.dp)
            .animateContentSize()
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(BgCard),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = routeIcon(device.kind),
                    contentDescription = null,
                    tint = if (isActive) AccentBlue else TextSecondary,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = device.name,
                    color = if (device.supportsHandoff || device.isLocal) TextMain else TextSecondary,
                    fontSize = 15.sp,
                    fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                val statusLine = when {
                    isPending -> "Connecting…"
                    row.status == DeviceLinkStatus.LOST -> "Connection lost"
                    row.status == DeviceLinkStatus.ACTIVE -> "Connected"
                    device.latencyMs != null -> "${device.latencyMs} ms"
                    else -> routeSubtitle(device)
                }
                Text(
                    text = if (row.status == DeviceLinkStatus.ACTIVE && device.latencyMs != null) {
                        "Connected · ${device.latencyMs} ms"
                    } else statusLine,
                    color = TextTertiary,
                    fontSize = 12.sp,
                    maxLines = 1
                )
            }
            when {
                isPending -> CircularProgressIndicator(
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(18.dp)
                )
                isActive -> Icon(
                    imageVector = Icons.Filled.CheckCircle,
                    contentDescription = "Active device",
                    tint = AccentBlue,
                    modifier = Modifier.size(20.dp)
                )
            }
            if (showTransferButton) {
                Spacer(Modifier.width(10.dp))
                TransferHerePill(onClick = onPick)
            }
        }

        // Shared remote volume slider — ONLY on routes whose protocol
        // carries a stream-gain channel (Chromecast / Smart TV). On
        // OS-owned-gain routes (BT / car) the slider must never appear:
        // hardware master gain is the single authority there.
        if (isActive && acl.remoteSliderShared) {
            Slider(
                value = sliderValue,
                onValueChange = { v ->
                    sliderValue = v
                    // Phase 5 — micro-tick on each crossed 10% volume
                    // increment (throttle-guarded).
                    com.streamify.app.ui.util.HapticFeedbackManager.get()
                        ?.onVolumeFractionChanged(v)
                    onVolumeChange(v)
                },
                colors = SliderDefaults.colors(
                    thumbColor = AccentBlue,
                    activeTrackColor = AccentBlue,
                    inactiveTrackColor = BorderSubtle
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 52.dp, end = 4.dp, top = 2.dp)
            )
        } else if (isActive && acl.guestLocked) {
            Text(
                text = "Volume is controlled by ${device.name}",
                color = TextTertiary,
                fontSize = 11.sp,
                modifier = Modifier.padding(start = 52.dp, top = 2.dp)
            )
        }
    }
}

@Composable
private fun TransferHerePill(onClick: () -> Unit) {
    Text(
        text = "Transfer here",
        color = AccentBlue,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(BgCard)
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 6.dp)
    )
}

private fun routeIcon(kind: ConnectRouteKind): ImageVector = when (kind) {
    ConnectRouteKind.LOCAL_PHONE -> Icons.Filled.PhoneAndroid
    ConnectRouteKind.SPEAKER -> Icons.Filled.Speaker
    ConnectRouteKind.BLUETOOTH -> Icons.Filled.BluetoothAudio
    ConnectRouteKind.A2DP_LE -> Icons.Filled.Bluetooth
    ConnectRouteKind.CAST_RECEIVER -> Icons.Filled.Cast
    ConnectRouteKind.SMART_TV -> Icons.Filled.Tv
    ConnectRouteKind.WIRED -> Icons.Filled.Headphones
    ConnectRouteKind.CAR -> Icons.Filled.DirectionsCar
    ConnectRouteKind.WATCH -> Icons.Filled.Watch
}

private fun routeSubtitle(device: ConnectDevice): String = when (device.origin) {
    DeviceOrigin.THIS_PHONE -> "This device"
    DeviceOrigin.SYSTEM_AUDIO -> "System audio route"
    DeviceOrigin.CAST -> "Google Cast target"
    DeviceOrigin.LAN_DISCOVERY -> "Wi-Fi target"
    DeviceOrigin.CLOUD -> "Cloud relay target"
    DeviceOrigin.REMEMBERED -> "Remembered target"
}
