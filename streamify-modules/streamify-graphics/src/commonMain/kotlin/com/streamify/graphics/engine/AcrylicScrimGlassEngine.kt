package com.streamify.graphics.engine

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.layer.drawLayer
import com.streamify.graphics.api.GlassRenderRequest
import com.streamify.graphics.api.SovereignGraphicsEngine
import com.streamify.graphics.tokens.PrimitiveTokens
import com.streamify.graphics.tokens.SemanticTokens

/**
 * Tier 3 (Legacy) engine — the API 26-30 lane and the demotion target
 * (docs/gpu/ARCHITECTURE.md section 3).
 *
 * Zero shader programs, zero blur passes, zero GPU uploads: the
 * caller-captured backdrop is drawn as-is, a **cached** vertical acrylic
 * gradient scrim ([Brush.verticalGradient] built once from the frozen
 * token layer — a fixed-function 2D-canvas fill, the "Hardware 2D Canvas"
 * lane of section 1) keeps the surface legible, and the rim closes the
 * glass silhouette. This is the engine behind the hard API 27 invariant —
 * it references no platform shader class at all, so a Vivo 1712 /
 * Android 8.1 process can never fail class-linking through it.
 *
 * Kyant optical parity on this lane (S24 Track D): where AGSL cannot run
 * at all, the two pillars a 2D canvas CAN express are preserved with
 * hardware gradients —
 *  * the **directional 45° highlight** renders as a dual-stop diagonal
 *    [Brush.linearGradient] stroke (full-alpha stop at the top-left
 *    boundary, 25%-alpha ambient stop at the bottom-right — the same
 *    lit/ambient split as the shader's cosine falloff), and
 *  * the **adaptive luminance scrim floor** draws the exact same
 *    `lerp(0.12, 0.50, clamp((luminance - 0.3) / 0.5))` "đục đen" rect
 *    the shader lanes fold into `darkenAmount`, so older devices match
 *    the premium dark-glass look without a single shader class.
 *
 * Differs from [LegacyRedirectGlassEngine] (Tier 4 failover) by the scrim
 * gradient: the flat failover draws a plain tint, the legacy lane carries
 * the surface with a two-stop acrylic veil built from
 * [SemanticTokens.surfaceGlassMuted] to [SemanticTokens.legacyAcrylicScrim].
 */
internal object AcrylicScrimGlassEngine : SovereignGraphicsEngine {

    /**
     * Cached scrim — immutable, shareable, built exactly once from the
     * frozen token layer (no per-frame allocation, no shader program).
     */
    private val scrimBrush = Brush.verticalGradient(
        colors = listOf(
            SemanticTokens.surfaceGlassMuted,
            SemanticTokens.legacyAcrylicScrim,
        ),
    )

    /**
     * Cached dual-stop directional rim gradient — the Tier 3 stand-in for
     * the shader's 45° specular sweep. `Brush.linearGradient` without
     * explicit start/end offsets spans the draw area from its top-left to
     * its bottom-right, so the lit stop lands exactly on the top-left
     * boundary and the ambient stop on the bottom-right, mirroring the
     * shader's `L = normalize(float2(-1.0, -1.0))` cosine distribution.
     */
    private val directionalRimBrush = Brush.linearGradient(
        colors = listOf(
            SemanticTokens.specularBorderHighlight,
            SemanticTokens.specularRimShaded,
        ),
    )

    override fun applyGlass(scope: DrawScope, request: GlassRenderRequest) {
        val outline = request.shape.createOutline(
            size = scope.size,
            layoutDirection = scope.layoutDirection,
            density = scope,
        )

        // This Compose revision has no clipOutline on DrawScope — flatten the
        // outline to a Path (shapes resolve to exactly one of three variants)
        // and clip through the proven clipPath lane instead.
        val outlinePath = Path().apply {
            when (outline) {
                is Outline.Rectangle -> addRect(outline.rect)
                is Outline.Rounded -> addRoundRect(outline.roundRect)
                is Outline.Generic -> addPath(outline.path)
            }
        }

        with(scope) {
            clipPath(outlinePath) {
                // Un-refracted, un-blurred backdrop. An unrecorded layer
                // (nothing captured yet) draws nothing — by design.
                drawLayer(request.backdrop)
                drawRect(brush = scrimBrush)
                if (request.tint != Color.Unspecified) {
                    drawRect(color = request.tint)
                }
                // Kyant parity (S24 Track D): the identical luminance-adaptive
                // "đục đen" floor the shader lanes bind as darkenAmount —
                // a plain rect here, evaluated from the request's live
                // backdrop luminance.
                drawAdaptiveScrim(request)
            }

            // 1px optical highlight stroke along the glass rim — the
            // dual-stop directional 45° sweep (lit top-left, ambient
            // bottom-right) instead of a flat uniform rim.
            drawOutline(
                outline,
                directionalRimBrush,
                style = Stroke(width = PrimitiveTokens.specularStrokeWidth.toPx()),
            )
        }
    }
}
