package com.streamify.graphics.api

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import com.streamify.graphics.engine.LegacyRedirectGlassEngine
import com.streamify.graphics.tokens.PrimitiveTokens
import com.streamify.graphics.tokens.SemanticTokens
import kotlin.concurrent.Volatile
import org.koin.mp.KoinPlatform.getKoin

/**
 * Frozen public facade of the Sovereign GPU Engine — the single entry point
 * UI composables use for Liquid Glass surfaces (docs/gpu/ARCHITECTURE.md,
 * section 5: "All composables consume `Modifier.streamifyGlass(backdrop,
 * shape, tint)`").
 *
 * Contract (frozen as of Phase 1 — additive changes only):
 *  * **Draw-phase only.** Implemented as `drawBehind`; applying the modifier
 *    never triggers recomposition, and the engine is resolved through Koin at
 *    most once per process (then cached).
 *  * **Never breaks a composable.** Until the Phase 2 platform adapters are
 *    registered (and whenever Koin has no engine bound), rendering silently
 *    redirects to the Tier-3-style [LegacyRedirectGlassEngine] fallback —
 *    clipped backdrop, optional tint, 1px specular rim.
 *  * The caller is responsible for capturing [backdrop] (e.g. with
 *    `rememberGraphicsLayer()` on the content behind the surface) and for
 *    clipping its own content if the design requires it.
 *
 * Kyant optical parity (S24, additive parameters — every default preserves
 * the frozen call shape):
 *  * [luminance] feeds the adaptive "đục đen" darkening ramp — read during
 *    drawing so a caller-sampled backdrop luminance adapts the scrim without
 *    recomposing the surface. Omit it for the fixed mid-luminance default
 *    (Kyant's non-adapting overload).
 *  * [scrimColor] picks the ramp's darkening direction — black for
 *    dark-theme glass, white for light (Kyant's `isDark` branch).
 *  * [interaction] wires the fluid press response (spring swell + touch
 *    tracking glow). Build one with [rememberGlassInteraction] — or use the
 *    [streamifyInteractiveGlass] overload that owns the wiring end to end.
 *
 * @param backdrop Pre-captured layer with the content behind the surface.
 * @param shape Clip geometry of the glass surface (see
 * `com.streamify.graphics.tokens.ComponentTokens` for standard radii).
 * @param tint Optional overlay fill; `Color.Unspecified` (default) skips the
 * tint layer. Standard fills live in
 * `com.streamify.graphics.tokens.SemanticTokens`.
 * @param luminance Draw-phase backdrop luminance estimate, 0..1.
 * @param scrimColor Direction the adaptive scrim darkens toward.
 * @param interaction Optional press/hold state driving the swell & glow.
 */
fun Modifier.streamifyGlass(
    backdrop: GraphicsLayer,
    shape: Shape,
    tint: Color = Color.Unspecified,
    luminance: () -> Float = { PrimitiveTokens.defaultBackdropLuminance },
    scrimColor: Color = Color.Black,
    interaction: GlassInteraction? = null,
): Modifier {
    // Built once per composition, reused across every draw pass — no per-frame
    // allocation in the draw phase. The luminance lambda is READ per draw, so
    // an adapting caller never recomposes this surface.
    val request = GlassRenderRequest(backdrop = backdrop, shape = shape, tint = tint, luminance = luminance, scrimColor = scrimColor)
    return drawBehind {
        SovereignGraphicsEngineLocator.engine().applyGlass(this, request)
        if (interaction != null) {
            drawPressGlow(interaction, shape)
        }
    }
}

/**
 * Fully-wired interactive variant of [Modifier.streamifyGlass] — the S24
 * Track C facade implementing Kyant's fluid "liquid glass" press response:
 *
 *  * **Spring-animated press swell** — the surface (and its content) scales
 *    toward [pressedScale] (1.12x by default) on an underdamped spring
 *    (damping ratio 0.5, stiffness 300 — the Kyant press spec, frozen in
 *    [PrimitiveTokens]), springing back on release.
 *  * **Touch-tracking radial glow** — a white radial highlight (alpha 0.18 at
 *    full press, `BlendMode.Plus`) follows the pointer through press and
 *    drag, clipped to the glass shape.
 *
 * The gesture detection is observe-only: clicks wrapped by the glass keep
 * working. Pass `interaction = null` semantics are not available here —
 * call [streamifyGlass] directly for a static surface.
 *
 * @param pressedScale Scale target at full press (wide surfaces like the
 * mini-player want a gentler 1.04x, matching Kyant's per-surface override).
 */
@Composable
fun Modifier.streamifyInteractiveGlass(
    backdrop: GraphicsLayer,
    shape: Shape,
    tint: Color = Color.Unspecified,
    luminance: () -> Float = { PrimitiveTokens.defaultBackdropLuminance },
    scrimColor: Color = Color.Black,
    pressedScale: Float = PrimitiveTokens.pressScaleSwell,
): Modifier {
    val interaction = rememberGlassInteraction()
    return this
        // Press swell wraps the glass draw and the surface content in one
        // scale layer — the lambda reads the animated progress per frame, so
        // the spring never recomposes the surface.
        .graphicsLayer {
            val scale = 1f + (pressedScale - 1f) * interaction.pressProgress
            scaleX = scale
            scaleY = scale
        }
        .streamifyGlass(
            backdrop = backdrop,
            shape = shape,
            tint = tint,
            luminance = luminance,
            scrimColor = scrimColor,
            interaction = interaction,
        )
        .pointerInput(interaction) { interaction.detectPress(this) }
}

/**
 * Kyant press glow: a radial white highlight tracking the pointer while the
 * surface is pressed, additively blended (`BlendMode.Plus`) and clipped to
 * the glass shape. Peak alpha (0.18) scales with the spring-animated press
 * progress; the radius is 1.2x the surface's min dimension — the exact
 * catalog constants from `LiquidGlassContainer.kt`.
 */
private fun DrawScope.drawPressGlow(interaction: GlassInteraction, shape: Shape) {
    val press = interaction.pressProgress
    if (press <= 0f) return

    val outline = shape.createOutline(
        size = size,
        layoutDirection = layoutDirection,
        density = this,
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

    clipPath(outlinePath) {
        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(
                    SemanticTokens.pressGlowHighlight.copy(alpha = PrimitiveTokens.pressGlowAlpha * press),
                    Color.Transparent,
                ),
                center = interaction.touchPosition,
                radius = size.minDimension * PrimitiveTokens.pressGlowRadiusFraction,
            ),
            blendMode = BlendMode.Plus,
        )
    }
}

/**
 * Offscreen-refraction variant of [Modifier.streamifyGlass] (S24 Track B):
 * the facade consumes a [GlassBackdropSnapshot] captured off the draw
 * phase by [rememberGlassBackdrop] instead of drawing the raw layer.
 *
 * On the skiko targets the Tier 1 / Tier 2 engines then run the real
 * `LiquidGlassCore` refraction over the captured pixels — full resolution
 * with chromatic dispersion on Tier 1, half-resolution Dynamic Resolution
 * Scaling with a bilinear upscale on the Tier 2 UMA-iGPU lane. Callers
 * keep the exact same shape / tint contract as the frozen overload.
 */
fun Modifier.streamifyGlass(
    snapshot: GlassBackdropSnapshot,
    shape: Shape,
    tint: Color = Color.Unspecified,
): Modifier {
    // Built once per composition, reused across every draw pass — the
    // engine reads the holder's volatile latest snapshot per frame.
    val request = GlassRenderRequest(
        backdrop = snapshot.layer,
        shape = shape,
        tint = tint,
        snapshot = snapshot,
    )
    return drawBehind {
        SovereignGraphicsEngineLocator.engine().applyGlass(this, request)
    }
}

/**
 * Draw-phase resolver for the [SovereignGraphicsEngine] port.
 *
 * Resolution order:
 *  1. The engine bound in Koin (`streamifyGraphicsModule`, overridable by the
 *     Phase 2 tier-governed adapter module), cached after first success.
 *  2. [LegacyRedirectGlassEngine] while Koin is not yet started, has no
 *     binding, or resolution fails for any reason — a draw must never crash.
 *
 * The not-found path deliberately stays uncached so a `loadKoinModules(...)`
 * that happens after the first frame is still picked up.
 */
internal object SovereignGraphicsEngineLocator {

    @Volatile
    private var cached: SovereignGraphicsEngine? = null

    fun engine(): SovereignGraphicsEngine {
        cached?.let { return it }
        val resolved = runCatching {
            getKoin().get<SovereignGraphicsEngine>()
        }.getOrNull()
        if (resolved != null) {
            cached = resolved
            return resolved
        }
        return LegacyRedirectGlassEngine
    }

    /** Drops the cached binding so the next draw re-resolves through Koin. */
    fun reset() {
        cached = null
    }
}
