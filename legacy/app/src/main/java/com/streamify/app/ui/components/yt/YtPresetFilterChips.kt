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
fun YtPresetFilterChips(
    selectedPreset: String,
    onPresetSelected: (String) -> Unit,
    presets: List<String> = listOf("Flat", "Bass Boost", "Electronic", "Hip-Hop", "Rock", "Vocal", "Acoustic", "Deep Club"),
    enabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier.fillMaxWidth()
    ) {
        items(presets, key = { it }) { preset ->
            val isActive = preset.equals(selectedPreset, ignoreCase = true)
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = if (isActive) BgChipActive else BgChipInactive,
                border = if (isActive) null else BorderStroke(1.dp, BorderChip),
                modifier = Modifier
                    .height(32.dp)
                    .clickable(enabled = enabled) { onPresetSelected(preset) }
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.padding(horizontal = 12.dp)
                ) {
                    Text(
                        text = preset,
                        style = LocalAppTypography.current.chipText.copy(fontSize = 13.sp),
                        color = if (isActive) TextOnActiveChip else if (enabled) TextMain else TextTertiary
                    )
                }
            }
        }
    }
}
