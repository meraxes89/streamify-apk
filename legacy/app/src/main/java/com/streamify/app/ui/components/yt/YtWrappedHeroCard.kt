package com.streamify.app.ui.components.yt
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.streamify.app.ui.theme.*

@Composable
fun YtWrappedHeroCard(
    totalMinutes: Int,
    totalTracks: Int,
    likedSongs: Int,
    topPlayedCount: Int,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = BgSurfaceElevated,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                text = "YOUR SOUNDTRACK IN NUMBERS",
                style = LocalAppTypography.current.songArtist.copy(
                    fontSize = 11.sp,
                    letterSpacing = 1.sp,
                    fontWeight = FontWeight.Bold
                ),
                color = TextSecondary
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "$totalMinutes Minutes Listened",
                style = LocalAppTypography.current.headlineLarge.copy(
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Black
                ),
                color = TextMain
            )

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                StatBlock(value = "$totalTracks", label = "Total Tracks")
                StatBlock(value = "$likedSongs", label = "Liked Songs")
                StatBlock(value = "$topPlayedCount", label = "Top Rotations")
            }
        }
    }
}

@Composable
private fun StatBlock(value: String, label: String) {
    Column {
        Text(
            text = value,
            style = LocalAppTypography.current.headlineMedium.copy(
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            ),
            color = ActiveControl
        )
        Text(
            text = label,
            style = LocalAppTypography.current.songArtist.copy(fontSize = 11.sp),
            color = TextSecondary
        )
    }
}
