package com.streamify.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import com.streamify.app.ui.animations.cardPressEffect
import com.streamify.app.ui.theme.StreamifyColors
import com.streamify.app.ui.theme.StreamifyDimens
import com.streamify.app.ui.theme.StreamifyShapes
import com.streamify.app.ui.theme.StreamifyType

@Composable
fun CategoryCard(
    title: String,
    backgroundColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .height(StreamifyDimens.CategoryCardH)
            .clip(StreamifyShapes.CategoryShape)
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        backgroundColor.copy(alpha = 0.8f),
                        backgroundColor
                    )
                )
            )
            .cardPressEffect(onClick = onClick)
    ) {
        Text(
            text = title,
            style = StreamifyType.TitleLarge,
            color = StreamifyColors.TextMain,
            modifier = Modifier.padding(StreamifyDimens.SpaceSM)
        )
        
        // Tilted placeholder for category art (Spotify style)
        Box(
            modifier = Modifier
                .size(60.dp)
                .align(Alignment.BottomEnd)
                .offset(x = 16.dp, y = 8.dp)
                .graphicsLayer { rotationZ = 25f }
                .clip(StreamifyShapes.CardShape)
                .background(Color.Black.copy(alpha = 0.2f))
        )
    }
}
