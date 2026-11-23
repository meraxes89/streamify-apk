package com.streamify.graphics.engine

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
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
 * Fallback stub / legacy redirect of the Sovereign Graphics Engine.
 *
 * Phase 1 stand-in (and permanent failover) that guarantees the frozen
 * `Modifier.streamifyGlass` facade can never break a composable:
 *
 *  * Renders the Tier-3-style approximation from docs/gpu/ARCHITECTURE.md
 *    section 3 — zero blur passes, zero shader programs, zero GPU uploads:
 *    the caller-captured backdrop drawn as-is inside the clip, an optional
 *    tint fill, and the 1px optical specular rim stroke.
 *  * Used until the Phase 2 platform adapters (AGSL / RenderEffect / Skia
 *    RuntimeShaderBuilder) are registered in Koin, whenever Koin has no
 *    binding, and as the Tier 4 (Safe) terminal fallback after any shader
 *    failure.
 *
 * Internal on purpose: it is a wiring detail of the port, not part of the
 * frozen public API. `di/GraphicsModule.kt` binds it for Phase 1.
 */
internal object LegacyRedirectGlassEngine : SovereignGraphicsEngine {

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
                if (request.tint != Color.Unspecified) {
                    drawRect(color = request.tint)
                }
            }

            // 1px optical highlight stroke along the glass rim.
            drawOutline(
                outline,
                SolidColor(SemanticTokens.specularBorderHighlight),
                style = Stroke(width = PrimitiveTokens.specularStrokeWidth.toPx()),
            )
        }
    }
}
