package com.streamify.app.ui.components.yt
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.streamify.app.ui.theme.*

@Composable
fun YtPlayerBottomTabs(
    activeTab: String = "UP NEXT",
    onQueueClick: () -> Unit,
    onLyricsClick: () -> Unit,
    onRelatedClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp, horizontal = 8.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        YtBottomTabItem(
            label = "UP NEXT",
            isActive = activeTab.equals("UP NEXT", ignoreCase = true),
            onClick = onQueueClick
        )
        YtBottomTabItem(
            label = "LYRICS",
            isActive = activeTab.equals("LYRICS", ignoreCase = true),
            onClick = onLyricsClick
        )
        YtBottomTabItem(
            label = "RELATED",
            isActive = activeTab.equals("RELATED", ignoreCase = true),
            onClick = onRelatedClick
        )
    }
}

@Composable
private fun YtBottomTabItem(
    label: String,
    isActive: Boolean,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }

    Row(
        modifier = Modifier
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = LocalAppTypography.current.chipText.copy(fontSize = 12.sp),
            color = if (isActive) TextMain else TextSecondary
        )
        Spacer(modifier = Modifier.width(4.dp))
        Icon(
            imageVector = Icons.Filled.KeyboardArrowUp,
            contentDescription = label,
            tint = if (isActive) TextMain else TextSecondary,
            modifier = Modifier.size(16.dp)
        )
    }
}
