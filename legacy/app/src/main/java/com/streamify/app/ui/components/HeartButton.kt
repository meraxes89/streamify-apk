package com.streamify.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import com.streamify.app.ui.theme.StreamifyColors
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun HeartButton(
    isLiked: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    val coroutineScope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val scale = remember { Animatable(1f) }
    val rotation = remember { Animatable(0f) }
    val fillProgress = remember { Animatable(if (isLiked) 1f else 0f) }
    val particlesState = remember { Animatable(0f) }

    val tint by animateColorAsState(
        targetValue = if (isLiked) StreamifyColors.Primary else StreamifyColors.TextSub,
        animationSpec = tween(durationMillis = 200),
        label = "heart_color"
    )

    Box(
        modifier = modifier
            .scale(scale.value)
            .graphicsLayer { rotationZ = rotation.value }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {
                onToggle()
                if (!isLiked) { // was not liked, now liking
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    coroutineScope.launch {
                        launch {
                            scale.animateTo(1.3f, animationSpec = tween(150, easing = FastOutSlowInEasing))
                            scale.animateTo(1f, animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow))
                        }
                        launch {
                            fillProgress.snapTo(0f)
                            fillProgress.animateTo(1f, animationSpec = tween(200, easing = LinearOutSlowInEasing))
                        }
                        launch {
                            particlesState.snapTo(0f)
                            particlesState.animateTo(1f, animationSpec = tween(600, easing = FastOutLinearInEasing))
                        }
                    }
                } else { // was liked, now unliking
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    coroutineScope.launch {
                        launch {
                            rotation.animateTo(8f, animationSpec = tween(50))
                            rotation.animateTo(-8f, animationSpec = tween(50))
                            rotation.animateTo(8f, animationSpec = tween(50))
                            rotation.animateTo(0f, animationSpec = tween(50))
                        }
                        launch {
                            fillProgress.animateTo(0f, animationSpec = tween(150))
                        }
                    }
                }
            }
            .padding(8.dp)
            .drawBehind {
                val p = particlesState.value
                if (p > 0f && p < 1f) {
                    val radius = size.minDimension / 2f
                    val centerOffset = Offset(size.width / 2f, size.height / 2f)
                    for (i in 0 until 12) {
                        val angle = (i * 30.0) * (Math.PI / 180.0)
                        val r = radius + (p * radius * 2f)
                        val drop = p * p * 20.dp.toPx() // gravity fall
                        val x = centerOffset.x + (r * cos(angle)).toFloat()
                        val y = centerOffset.y + (r * sin(angle)).toFloat() + drop
                        val alpha = 1f - p
                        drawCircle(
                            color = StreamifyColors.Primary.copy(alpha = alpha),
                            radius = 3.dp.toPx() * (1f - p),
                            center = Offset(x, y)
                        )
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Outlined.FavoriteBorder,
            contentDescription = "Like",
            tint = StreamifyColors.TextSub
        )
        if (fillProgress.value > 0f) {
            Icon(
                imageVector = Icons.Filled.Favorite,
                contentDescription = null,
                tint = StreamifyColors.Primary,
                modifier = Modifier.drawWithContent {
                    val height = size.height
                    clipRect(
                        top = height - (height * fillProgress.value)
                    ) {
                        this@drawWithContent.drawContent()
                    }
                }
            )
        }
    }
}
