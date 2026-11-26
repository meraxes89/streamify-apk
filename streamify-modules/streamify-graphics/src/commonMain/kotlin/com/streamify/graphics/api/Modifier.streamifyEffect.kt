package com.streamify.graphics.api

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape

/**
 * Draw-phase facade for rendering any [com.streamify.graphics.effect.GraphicsEffect]
 * registered in [com.streamify.graphics.effect.GraphicsEffectRegistry].
 *
 * Like [streamifyGlass] and [streamifyVisualizer], this modifier is confined
 * strictly to `drawBehind`: uniform lambdas are read only during the draw
 * pass so animated uniforms (time, pointer, audio energy) never trigger
 * recomposition.
 *
 * If [effectId] is unregistered, the device's live [com.streamify.graphics.governor.RenderTier]
 * is below the effect's `minTier`, or shader compilation fails, the engine
 * degrades silently to [fallbackColor].
 */
fun Modifier.streamifyEffect(
    effectId: String,
    shape: Shape = RectangleShape,
    fallbackColor: Color = Color.Unspecified,
    resolutionUniform: String? = "iResolution",
    uniforms: List<EffectUniformSlot> = emptyList(),
): Modifier {
    val request = EffectRenderRequest(
        effectId = effectId,
        shape = shape,
        fallbackColor = fallbackColor,
        resolutionUniform = resolutionUniform,
        uniforms = uniforms,
    )
    return drawBehind {
        SovereignGraphicsEngineLocator.engine().applyEffect(this, request)
    }
}
