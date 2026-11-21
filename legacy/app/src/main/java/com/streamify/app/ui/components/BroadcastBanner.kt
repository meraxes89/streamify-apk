package com.streamify.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.streamify.app.ui.theme.StreamifyColors
import com.streamify.app.ui.theme.StreamifyDimens
import com.streamify.app.ui.theme.StreamifyType

@Composable
fun BroadcastBanner(
    broadcasts: List<String>,
    modifier: Modifier = Modifier
) {
    if (broadcasts.isEmpty()) return

    var isDismissed by remember(broadcasts) { mutableStateOf(false) }
    val latestMessage = broadcasts.firstOrNull() ?: return

    AnimatedVisibility(
        visible = !isDismissed,
        enter = slideInVertically() + fadeIn(),
        exit = slideOutVertically() + fadeOut(),
        modifier = modifier
    ) {
        Surface(
            color = StreamifyColors.Primary.copy(alpha = 0.15f),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Campaign,
                    contentDescription = null,
                    tint = StreamifyColors.Primary,
                    modifier = Modifier.size(22.dp)
                )

                Spacer(modifier = Modifier.width(10.dp))

                Text(
                    text = latestMessage,
                    style = StreamifyType.BodySmallBold,
                    color = StreamifyColors.TextMain,
                    modifier = Modifier.weight(1f)
                )

                IconButton(
                    onClick = { isDismissed = true },
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "Dismiss",
                        tint = StreamifyColors.TextSub,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}
