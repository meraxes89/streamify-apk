package com.streamify.graphics.api

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.layer.GraphicsLayer
import com.streamify.graphics.tokens.PrimitiveTokens

/**
 * Frozen render request passed from the `Modifier.streamifyGlass` facade to
 * the [SovereignGraphicsEngine] port.
 *
 * Immutable by convention — the facade builds one instance per composition
 * and reuses it across every draw pass, so implementations MUST NOT mutate
 * or retain ownership of it beyond a single [SovereignGraphicsEngine.applyGlass]
 * call.
 *
 * @property backdrop Caller-captured backdrop layer (e.g. via
 * `rememberGraphicsLayer()`). Contains the content *behind* the glass
 * surface, pre-captured by the hosting composable. May be unrecorded —
 * implementations must treat drawing it as a no-op, never an error.
 * @property shape Clip geometry of the glass surface.
 * @property tint Optional overlay fill. `Color.Unspecified` means "no tint
 * layer" — implementations skip the fill entirely.
 * @property snapshot Live backdrop capture driven off the draw phase by
 * [rememberGlassBackdrop] (S24 Track B). When present, Tier 1/2 engines
 * sample the real backdrop pixels through the offscreen refraction pass
 * (with Dynamic Resolution Scaling on the Tier 2 lane); when absent, the
 * engine keeps its stable stopgap composite. `null` by default — the
 * frozen Phase 1 call sites are unaffected.
 * @property luminance Draw-phase backdrop luminance estimate, 0..1. Read
 * per draw pass (never recomposes) and fed into the adaptive "đục đen"
 * darkening ramp: `lerp(0.12, 0.50, clamp((luminance - 0.3) / 0.5))`.
 * Surfaces that do not sample their background keep the fixed mid-luminance
 * default, mirroring Kyant's non-adapting overload. (Additive S24 field.)
 * @property scrimColor Color the adaptive scrim darkens toward — black for
 * dark-theme glass, white for the light theme (Kyant's `isDark` branch).
 * (Additive S24 field.)
 */
class GlassRenderRequest(
    val backdrop: GraphicsLayer,
    val shape: Shape,
    val tint: Color,
    val snapshot: GlassBackdropSnapshot? = null,
    val luminance: () -> Float = { PrimitiveTokens.defaultBackdropLuminance },
    val scrimColor: Color = Color.Black,
)

/**
 * Hexagonal port of the Sovereign GPU Engine — the ONLY contract through
 * which the Compose UI layer requests Liquid Glass rendering
 * (docs/gpu/ARCHITECTURE.md, section 1).
 *
 * The UI never sees a platform type: Phase 2 adapters implement this
 * interface per tier —
 *  * androidMain: AGSL `RuntimeShader` (API 33+, Tier 1/2),
 *    `RenderEffect` (API 31–32, Tier 2), acrylic scrim (API < 31, Tier 3).
 *  * skiko targets (Desktop / iOS / wasmJs): Skia `RuntimeShaderBuilder`
 *    over the canonical `LiquidGlassCore.sksl`.
 *
 * Implementations must honor the engine's core invariants:
 *  * **Draw-phase only.** Called exclusively from a draw scope; never
 *    triggers recomposition and never allocates per frame.
 *  * **Never throws.** Any shader compilation or GPU failure degrades to the
 *    Tier 4 (Safe) solid-tint path inside the adapter (see
 *    docs/gpu/CI_TESTING_SPEC.md, Shader Compilation Failure Fallback Test).
 *  * **Thread-safe.** May be invoked from any render thread.
 */
interface SovereignGraphicsEngine {

    /**
     * Renders the glass composite for [request] into [scope]: the backdrop
     * (refracted / blurred according to the active tier), the optional tint
     * fill, and the specular rim highlight, all clipped to the request shape.
     *
     * The caller draws its own content afterwards; this method paints only
     * the surface *behind* the content.
     */
    fun applyGlass(scope: DrawScope, request: GlassRenderRequest)

    /**
     * Renders a registered [com.streamify.graphics.effect.GraphicsEffect]
     * for [request] into [scope].
     *
     * Default implementation renders [EffectRenderRequest.fallbackColor]
     * clipped to [EffectRenderRequest.shape] so legacy/Tier-4 engines
     * degrade gracefully without crashing or requiring boilerplate.
     */
    fun applyEffect(scope: DrawScope, request: EffectRenderRequest) {
        drawEffectFallback(scope, request)
    }
}

/**
 * Draw-phase uniform binding for [EffectRenderRequest].
 *
 * Lambdas are evaluated strictly inside the draw pass so animated values
 * never trigger recomposition.
 */
sealed class EffectUniformSlot(val name: String) {
    class Float1(name: String, val value: () -> Float) : EffectUniformSlot(name)
    class Float2(name: String, val x: () -> Float, val y: () -> Float) : EffectUniformSlot(name)
    class Float3(name: String, val x: () -> Float, val y: () -> Float, val z: () -> Float) : EffectUniformSlot(name)
    class Float4(
        name: String,
        val x: () -> Float,
        val y: () -> Float,
        val z: () -> Float,
        val w: () -> Float,
    ) : EffectUniformSlot(name)
    class Color4(name: String, val color: () -> Color) : EffectUniformSlot(name)
}

/**
 * Immutable composition-scoped render request for [Modifier.streamifyEffect].
 */
class EffectRenderRequest(
    val effectId: String,
    val shape: Shape = RectangleShape,
    val fallbackColor: Color = Color.Unspecified,
    val resolutionUniform: String? = "iResolution",
    val uniforms: List<EffectUniformSlot> = emptyList(),
)

/**
 * Shared Tier-4 / compile-failure fallback for [SovereignGraphicsEngine.applyEffect]:
 * fills [EffectRenderRequest.fallbackColor] clipped to [EffectRenderRequest.shape]
 * when specified, or no-ops cleanly.
 */
internal fun drawEffectFallback(scope: DrawScope, request: EffectRenderRequest) {
    val color = request.fallbackColor
    if (color == Color.Unspecified || color.alpha <= 0f) return
    with(scope) {
        val outline = request.shape.createOutline(
            size = size,
            layoutDirection = layoutDirection,
            density = this,
        )
        val path = Path().apply {
            when (outline) {
                is Outline.Rectangle -> addRect(outline.rect)
                is Outline.Rounded -> addRoundRect(outline.roundRect)
                is Outline.Generic -> addPath(outline.path)
            }
        }
        clipPath(path) {
            drawRect(color = color)
        }
    }
}
