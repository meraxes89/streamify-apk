package com.streamify.app.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import coil.compose.AsyncImage
import com.streamify.app.ui.animations.cardPressEffect
import com.streamify.app.ui.theme.StreamifyColors
import com.streamify.app.ui.theme.StreamifyDimens
import com.streamify.app.ui.theme.StreamifyType

@Composable
fun ArtistCircleCard(
    artistName: String,
    imageUrl: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .width(StreamifyDimens.ArtistCardSize)
            .cardPressEffect(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        AsyncImage(
            model = imageUrl,
            contentDescription = "Artist $artistName",
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(StreamifyDimens.ArtistCardSize)
                .clip(CircleShape)
        )
        Spacer(modifier = Modifier.height(StreamifyDimens.SpaceSM))
        Text(
            text = artistName,
            style = StreamifyType.CardTitle,
            color = StreamifyColors.TextMain,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )
    }
}
