package com.streamify.app.ui.components.yt
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.streamify.app.ui.theme.*

@Composable
fun YtLyricLineItem(
    text: String,
    isActive: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }

    // 120fps GPU-Accelerated Color & Scale Transitions
    val targetColor by animateColorAsState(
        targetValue = if (isActive) TextMain else TextTertiary,
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "lyricColor"
    )

    val targetScale by animateFloatAsState(
        targetValue = if (isActive) 1.05f else 1.0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "lyricScale"
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp, horizontal = 24.dp)
            .clip(RectangleShape)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            // Extreme Performance: GPU transform bypasses text layout re-measurement
            .graphicsLayer {
                scaleX = targetScale
                scaleY = targetScale
                transformOrigin = TransformOrigin(0f, 0.5f)
            }
    ) {
        Text(
            text = text,
            fontSize = 24.sp,
            fontWeight = if (isActive) FontWeight.Bold else FontWeight.SemiBold,
            color = targetColor,
            lineHeight = 34.sp,
            textAlign = TextAlign.Start,
            modifier = Modifier.fillMaxWidth()
        )
    }
}
