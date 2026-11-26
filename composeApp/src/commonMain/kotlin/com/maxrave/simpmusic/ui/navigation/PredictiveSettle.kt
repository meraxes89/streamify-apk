package com.maxrave.simpmusic.ui.navigation

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import kotlinx.coroutines.CoroutineScope

/**
 * RFC-003 §3.3: a predictive-back overlay must settle LINEARLY from wherever the finger
 * left it — re-applying a deceleration curve mid-gesture is what makes the release snap.
 * This helper holds a SHEET SCALE (rest = 1f): a cancelled gesture returns to rest, a
 * committed one runs to the collapsed value so the sheet's own hide animation takes over.
 */
suspend fun Animatable<Float, *>.settlePredictiveRelease(commit: Boolean, scope: CoroutineScope) {
    val target = if (commit) 0.92f else 1f
    animateTo(target, animationSpec = tween(durationMillis = 120, easing = LinearEasing))
}
