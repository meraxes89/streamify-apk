package com.streamify.app.connect

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.streamify.app.ui.theme.AccentBlue
import com.streamify.app.ui.theme.BgChipInactive
import com.streamify.app.ui.theme.TextMain
import com.streamify.app.ui.theme.TextSecondary
import kotlinx.coroutines.flow.StateFlow

/**
 * ConnectStatusPill — "Listening on Living Room Speaker" indicator +
 * one-tap control (Gap #52).
 *
 * Shrink-wrapped so the Full Player and the MiniPlayer can both embed it
 * without duplicating state collection: tapping it opens the Connect
 * device picker via [ConnectRuntime], tapping the phone chip inside the
 * picker transfers playback back without interrupting position or queue
 * order.
 */
@Composable
fun ConnectStatusPill(
    sessionFlow: StateFlow<ConnectSessionState> = ConnectRuntime.coordinator.state,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    onOpenPicker: () -> Unit = { ConnectRuntime.openDevicePicker() }
) {
    val session by sessionFlow.collectAsState()
    val deviceName = session.activeDeviceName

    androidx.compose.animation.AnimatedVisibility(
        visible = true,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clip(RoundedCornerShape(999.dp))
                .background(BgChipInactive.copy(alpha = 0.92f))
                .clickable { onOpenPicker() }
                .padding(horizontal = 10.dp, vertical = 5.dp)
        ) {
            Icon(
                imageVector = if (deviceName != null) Icons.Filled.Cast else Icons.Filled.Devices,
                contentDescription = null,
                tint = if (deviceName != null) AccentBlue else TextSecondary,
                modifier = Modifier.size(14.dp)
            )
            Spacer(Modifier.width(6.dp))
            val label = when {
                session.phase == ConnectSessionPhase.CONNECTING -> "Connecting…"
                deviceName != null && compact -> deviceName
                deviceName != null -> "Listening on $deviceName"
                session.phase == ConnectSessionPhase.FALLING_BACK -> "Reconnecting here…"
                else -> "This phone"
            }
            Text(
                text = label,
                color = if (deviceName != null) TextMain else TextSecondary,
                fontSize = 11.sp,
                fontWeight = if (deviceName != null) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** Icon helper reused by the Wear-side mirroring surfaces. */
internal fun pillIconFor(kind: ConnectRouteKind): ImageVector = when (kind) {
    ConnectRouteKind.CAST_RECEIVER, ConnectRouteKind.SMART_TV -> Icons.Filled.Cast
    else -> Icons.Filled.Speaker
}
