package com.maxrave.simpmusic.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.streamify.media.hal.DolbySpatialManager

/**
 * Universal Dolby Atmos & Spatial Audio Status Card.
 * Renders spatializer capability and hardware stage status across Android and Desktop.
 */
@Composable
fun DolbyAtmosCard(
    modifier: Modifier = Modifier
) {
    val isDetected by DolbySpatialManager.isDolbyAtmosDetected.collectAsStateWithLifecycle()
    val hardwareDetail by DolbySpatialManager.hardwareDetail.collectAsStateWithLifecycle()
    val hasHeadTracking by DolbySpatialManager.hasHeadTracking.collectAsStateWithLifecycle()

    val accentColor = if (isDetected) Color(0xFFD4AF37) else Color(0xFF888888)
    val containerBg = Color(0xFF141414)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(containerBg)
            .border(1.dp, accentColor.copy(alpha = 0.4f), RoundedCornerShape(16.dp))
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "DOLBY ATMOS & SPATIAL AUDIO",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.2.sp,
                    color = accentColor
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = if (isDetected) "Hardware Stage Active" else "Studio 32-bit DSP",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White
                )
            }

            Text(
                text = if (isDetected) "LIVE" else "OFF",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = if (isDetected) Color(0xFF4CAF50) else Color(0xFF888888),
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (isDetected) Color(0x224CAF50) else Color(0x22888888))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = hardwareDetail,
            fontSize = 12.sp,
            color = Color(0xFFAAAAAA)
        )

        if (hasHeadTracking) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Dynamic Head Tracking: Available",
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = Color(0xFF81C784)
            )
        }
    }
}
