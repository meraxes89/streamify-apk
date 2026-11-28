package com.streamify.graphics.engine

import androidx.compose.ui.graphics.drawscope.DrawScope
import com.streamify.graphics.api.GlassRenderRequest
import com.streamify.graphics.tokens.PrimitiveTokens

/**
 * Adaptive luminance scrim — the "đục đen" darkening ramp of Kyant optical
 * parity (S24 Track B), shared by every lane:
 *
 * * the AGSL / skiko runtime-shader engines evaluate [alphaFor] on the CPU
 *   and bind the result as the shader's `darkenAmount` uniform (the ramp
 *   itself is applied inside `LiquidGlassCore.sksl`);
 * * the fixed-function lanes (Android API 31-32 blur, Tier 3 acrylic scrim,
 *   Tier 4 failover) draw it as a plain scrim rect via [drawAdaptiveScrim],
 *   exactly like Kyant's `onDrawSurface` drawRect.
 *
 * The curve is the one pinned by the reference implementation
 * (`composeApp/.../LiquidGlassContainer.kt`):
 *
 * ```
 * darken = lerp(0.12, 0.50, clamp((luminance - 0.3) / 0.5, 0.0, 1.0))
 * ```
 *
 * Over bright album art the glass therefore deepens its dark tint so
 * overlaying typography never becomes illegible; over dark content it
 * settles on the subtle 0.12 floor.
 */
internal object AdaptiveLuminanceScrim {

    /**
     * Scrim alpha for a backdrop luminance estimate in 0..1. Values outside
     * the ramp clamp at the [PrimitiveTokens.scrimDarkenMin] /
     * [PrimitiveTokens.scrimDarkenMax] ends.
     */
    fun alphaFor(luminance: Float): Float {
        val ramp =
            ((luminance - PrimitiveTokens.scrimLuminanceFloor) / PrimitiveTokens.scrimLuminanceRange)
                .coerceIn(0f, 1f)
        return PrimitiveTokens.scrimDarkenMin +
            (PrimitiveTokens.scrimDarkenMax - PrimitiveTokens.scrimDarkenMin) * ramp
    }
}

/**
 * Draws the adaptive scrim as a full-surface rect in the given
 * [scrimColor] (black for dark glass, white for light glass), at the alpha
 * the "đục đen" ramp yields for the request's current backdrop luminance.
 *
 * Fixed-function lane only — the runtime-shader lanes fold the same value
 * into the shader via the `darkenAmount` uniform. No allocation: the ramp
 * result is a packed [androidx.compose.ui.graphics.Color] value.
 */
internal fun DrawScope.drawAdaptiveScrim(request: GlassRenderRequest) {
    val alpha = AdaptiveLuminanceScrim.alphaFor(request.luminance())
    drawRect(color = request.scrimColor.copy(alpha = alpha))
}
