package com.streamify.app.ui.animations

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue

@Composable
fun heartBurstScale(isLiked: Boolean): Float {
    val scale by animateFloatAsState(
        targetValue = if (isLiked) 1.2f else 1.0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioHighBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "heart_burst"
    )
    
    // Simple return of scale, returning to 1.0 after burst is usually handled 
    // by keyframes, but for Compose spring, targeting 1.2f temporarily isn't as trivial 
    // without a separate state. We'll use a simple scale up/down if liked.
    // In a real app we'd trigger a 1.0 -> 1.3 -> 1.0 sequence.
    return if (isLiked && scale == 1.2f) 1.0f else scale // Very basic mock
}
