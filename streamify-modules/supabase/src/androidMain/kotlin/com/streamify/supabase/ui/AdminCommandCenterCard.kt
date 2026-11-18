package com.streamify.supabase.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.streamify.supabase.SupabaseClient

/**
 * Special Admin Command Center Card for SettingsScreen.
 * Only rendered for server-verified administrative accounts (SEC-09).
 */
@Composable
fun AdminCommandCenterCard(
    onNavigateToAdmin: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (!SupabaseClient.isAdmin) return

    val adminGold = Color(0xFFFFD700)
    val cardBg = Color(0xFF161305)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(cardBg)
            .border(1.dp, adminGold.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
            .clickable { onNavigateToAdmin() }
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "ADMINISTRATOR PRIVILEGES",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.2.sp,
                    color = adminGold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Open Admin Command Center",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }

            Text(
                text = "LAUNCH ➔",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = adminGold,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(adminGold.copy(alpha = 0.2f))
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Active Streams • Telemetry • Session Moderation • Live Logs",
            fontSize = 12.sp,
            color = Color(0xFFC0B080)
        )
    }
}
