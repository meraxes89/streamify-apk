package com.streamify.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.streamify.app.ui.models.VirtualShelf
import com.streamify.app.ui.models.VirtualShelfTrack

@Composable
fun UniversalHomeScreen(
    shelves: List<VirtualShelf>,
    onTrackSelected: (VirtualShelfTrack) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 96.dp)
    ) {
        items(
            items = shelves,
            key = { it.id } // Stable shelf recomposition key
        ) { shelf ->
            ShelfRow(
                shelf = shelf,
                onTrackSelected = onTrackSelected
            )
        }
    }
}

@Composable
private fun ShelfRow(
    shelf: VirtualShelf,
    onTrackSelected: (VirtualShelfTrack) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp)
    ) {
        Text(
            text = shelf.title,
            color = Color.White,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
        )

        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(
                items = shelf.tracks,
                key = { it.cadId } // Stable track recomposition key
            ) { track ->
                ShelfTrackCard(
                    track = track,
                    onClick = { onTrackSelected(track) }
                )
            }
        }
    }
}

@Composable
private fun ShelfTrackCard(
    track: VirtualShelfTrack,
    onClick: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val imageRequest = remember(track.artworkUrl) {
        coil.request.ImageRequest.Builder(context)
            .data(track.artworkUrl)
            .crossfade(false)
            .build()
    }

    Column(
        modifier = Modifier
            .width(140.dp)
            .graphicsLayer {
                shadowElevation = 4f
                shape = RoundedCornerShape(8.dp)
                clip = false
            }
            .clickable(onClick = onClick)
    ) {
        AsyncImage(
            model = imageRequest,
            contentDescription = track.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(140.dp)
                .clip(RoundedCornerShape(8.dp))
        )

        Spacer(modifier = Modifier.height(6.dp))

        Text(
            text = track.title,
            color = Color.White,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        Text(
            text = track.artist,
            color = Color(0xFF9E9E9E),
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
