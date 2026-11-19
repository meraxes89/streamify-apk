package com.streamify.app.ui.animations

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring

object PlayerTransition {
    val springSpec = spring<Float>(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMedium
    )
}
