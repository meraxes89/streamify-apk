package com.streamify.app.ui.components.yt
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.streamify.app.ui.theme.*

@Composable
fun YtSortFilterBar(
    sortLabel: String = "Recent activity",
    isGridView: Boolean = false,
    onSortClick: () -> Unit,
    onToggleView: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        // Sort Selector
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.clickable(onClick = onSortClick)
        ) {
            Text(
                text = sortLabel,
                style = LocalAppTypography.current.titleMedium.copy(fontSize = 14.sp),
                color = TextSecondary
            )
            Icon(
                imageVector = Icons.Filled.ArrowDropDown,
                contentDescription = "Sort Options",
                tint = TextSecondary,
                modifier = Modifier.size(20.dp)
            )
        }

        // Grid / List Toggle Icon
        Icon(
            imageVector = if (isGridView) Icons.Filled.ViewList else Icons.Filled.GridView,
            contentDescription = if (isGridView) "Switch to List view" else "Switch to Grid view",
            tint = TextSecondary,
            modifier = Modifier
                .size(22.dp)
                .clickable(onClick = onToggleView)
        )
    }
}
