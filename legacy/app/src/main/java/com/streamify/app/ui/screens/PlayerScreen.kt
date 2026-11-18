package com.streamify.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.streamify.app.ui.theme.StreamifyColors
import com.streamify.app.data.models.Track

@Composable
fun PlayerScreen(track: Track?, onCollapse: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(StreamifyColors.BgBase)
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        if (track != null) {
            Text("Full Player: ${track.title}", color = StreamifyColors.TextMain)
            Text(track.artist, color = StreamifyColors.TextSub)
        } else {
            Text("No track playing", color = StreamifyColors.TextSub)
        }
    }
}
