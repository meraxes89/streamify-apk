package com.streamify.app.ui.components.yt
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.streamify.app.ui.theme.*

@Composable
fun YtMoodFilterRail(
    selectedMood: String,
    onMoodSelected: (String) -> Unit,
    moods: List<String> = listOf("All", "Relax", "Workout", "Energize", "Focus", "Commute", "Podcasts"),
    modifier: Modifier = Modifier
) {
    Surface(
        color = BgBase,
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp)
    ) {
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            items(moods, key = { it }) { mood ->
                val isActive = mood.equals(selectedMood, ignoreCase = true)
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = if (isActive) BgChipActive else BgChipInactive,
                    border = if (isActive) null else BorderStroke(1.dp, BorderChip),
                    modifier = Modifier
                        .height(32.dp)
                        .clickable { onMoodSelected(mood) }
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.padding(horizontal = 12.dp)
                    ) {
                        Text(
                            text = mood,
                            style = LocalAppTypography.current.chipText.copy(fontSize = 13.sp),
                            color = if (isActive) TextOnActiveChip else TextMain
                        )
                    }
                }
            }
        }
    }
}
