package com.streamify.graphics.engine

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.layer.drawLayer
import com.streamify.graphics.api.GlassRenderRequest
import com.streamify.graphics.api.SovereignGraphicsEngine
// All four adapter helpers (AgslDialect, GlassRenderStrategy,
// LiquidGlassSource, ShaderTierLadder) are consumed here — one wildcard
// keeps the isolation-file import block short.
import com.streamify.graphics.engine.adapter.*
import com.streamify.graphics.governor.CapabilityProbe
import com.streamify.graphics.governor.RenderTier
import com.streamify.graphics.tokens.PrimitiveTokens
import com.streamify.graphics.tokens.SemanticTokens

/**
 * Android actual of the platform shader adapter
 * (docs/gpu/PHASED_PLAN.md Phase 2, Track A deliverable 1).
 *
 * ## The three lanes (docs/gpu/ARCHITECTURE.md section 3)
 *
 * * **API 33+** — AGSL: the canonical `LiquidGlassCore` source, transpiled
 *   by [AgslDialect], compiled into an `android.graphics.RuntimeShader` and
 *   applied through `RenderEffect.createRuntimeShaderEffect` with the layer
 *   content fed into the shader's `backdrop` child. Tier 2 on this lane
 *   rebinds `chromaticSpread = 0.0` (single-tap) on the same program.
 * * **API 31-32** — `RenderEffect.createBlurEffect` over the backdrop layer.
 * * **API 26-30** — the cached acrylic scrim lane
 *   ([AcrylicScrimGlassEngine], zero shader classes).
 *
 * ## API 27 class-isolation invariant (hard)
 *
 * A Vivo 1712 (Android 8.1 / API 27) process must never throw
 * `ClassNotFoundException` / `NoClassDefFoundError` for `RuntimeShader`
 * (API 33+) or `RenderEffect` (API 31+) — docs/gpu/CI_TESTING_SPEC.md
 * section 2, Job 2. Every reference to those classes lives inside
 * `@RequiresApi`-annotated isolated types ([Agsl33Isolated],
 * [AgslRuntimeShaderEngine], [Blur31Isolated], [RenderEffectBlurEngine])
 * that are only referenced from behind explicit `Build.VERSION.SDK_INT`
 * gates. ART verifies lazily: a class referenced solely from a
 * not-taken branch is never loaded, so on API 27 the ladder selects the
 * FIXED_FUNCTION column ([ShaderTierLadder] pins it in common tests),
 * `buildEngines` never touches an API-31+ type, and none of the isolated
 * classes ever loads. Lane selection funnels exclusively through the
 * pure, CI-pinned [ShaderTierLadder] — this actual adds no tiering logic
 * of its own.
 */
actual object ShaderProvider {

    /**
     * Cached per process — `by lazy` is synchronized, so the probe is safe
     * to request from the Koin graph, any draw pass and any thread.
     */
    private val cachedProbe: CapabilityProbe by lazy {
        CapabilityProbe(
            apiLevel = Build.VERSION.SDK_INT,
            // GL_RENDERER requires a live EGL context — not available at
            // bootstrap. The software-rasterizer axis (axis 2) is fed by the
            // facade/Phase 4 lane; empty keeps the classifier conservative.
            rendererDescription = "",
            runtimeShaderCompileSupported =
                if (Build.VERSION.SDK_INT >= 33) Agsl33Isolated.compileProbe() else false,
        )
    }

    actual fun probe(): CapabilityProbe = cachedProbe

    /**
     * Cached per-tier engines — [engineFor] runs on **every draw pass**, so
     * after the one-time construction it is a plain array lookup (zero
     * allocation, no locks on the hot path).
     */
    private val enginesByTier: Array<SovereignGraphicsEngine?> by lazy { buildEngines() }

    actual fun engineFor(tier: RenderTier): SovereignGraphicsEngine =
        enginesByTier[tier.ordinal] ?: LegacyRedirectGlassEngine

    /**
     * One-time construction of all four tier lanes. Strategy comes from the
     * common ladder; a lane whose program fails to build (e.g. an OEM AGSL
     * compiler rejecting the source) degrades to the scrim engine instead of
     * throwing — the Shader Compilation Failure Fallback shape of
     * docs/gpu/CI_TESTING_SPEC.md section 2, Job 5.
     */
    private fun buildEngines(): Array<SovereignGraphicsEngine?> {
        val probe = cachedProbe
        val engines = arrayOfNulls<SovereignGraphicsEngine>(RenderTier.entries.size)
        for (tier in RenderTier.entries) {
            val plan = ShaderTierLadder.planFor(tier, probe.shaderCapability)
            engines[tier.ordinal] = try {
                engineForStrategy(plan.strategy)
            } catch (t: Throwable) {
                // A program that fails to build degrades the lane, never the
                // process: the scrim engine renders every tier without a
                // single platform shader class reference.
                AcrylicScrimGlassEngine
            }
        }
        return engines
    }

    private fun engineForStrategy(strategy: GlassRenderStrategy): SovereignGraphicsEngine =
        when (strategy) {
            GlassRenderStrategy.SOLID_TINT -> LegacyRedirectGlassEngine
            GlassRenderStrategy.ACRYLIC_SCRIM -> AcrylicScrimGlassEngine
            GlassRenderStrategy.BLUR_RENDER_EFFECT -> blurEngine()
            GlassRenderStrategy.RUNTIME_SHADER_SINGLE_TAP ->
                agslEngine(chromaticSpread = 0f)
            GlassRenderStrategy.RUNTIME_SHADER_DISPERSION ->
                agslEngine(chromaticSpread = PrimitiveTokens.chromaticSpreadDefault)
        }

    /**
     * Double-gated blur lane: the ladder strategy already implies an
     * API 31+ classification, and the explicit SDK check keeps the isolated
     * class unlinkable even if a future caller bypasses the ladder.
     */
    private fun blurEngine(): SovereignGraphicsEngine =
        if (Build.VERSION.SDK_INT >= 31) Blur31Isolated.createEngine()
        else AcrylicScrimGlassEngine

    /** Double-gated AGSL lane (same reasoning as [blurEngine], for API 33+). */
    private fun agslEngine(chromaticSpread: Float): SovereignGraphicsEngine =
        if (Build.VERSION.SDK_INT >= 33) Agsl33Isolated.createEngine(chromaticSpread)
        else blurEngine()
}

/**
 * API 33+ isolated lane — AGSL `RuntimeShader` +
 * `RenderEffect.createRuntimeShaderEffect`. Never class-linked below API 33:
 * every reference sits behind the `SDK_INT >= 33` gate in [ShaderProvider],
 * and the type itself is only ever loaded on first use of those gated arms.
 */
@RequiresApi(33)
private object Agsl33Isolated {

    /**
     * The compile probe behind `runtimeShaderCompileSupported`: a minimal
     * AGSL program through the platform compiler. Conservative by contract —
     * any Throwable (unavailable AGSL, driver rejection) means unsupported.
     */
    fun compileProbe(): Boolean = try {
        // Keep this inlined in this isolated type only — the probe must not
        // reference the canonical source (that compile belongs to the engine).
        android.graphics.RuntimeShader(
            "half4 main(float2 fragCoord) { return half4(0.0, 0.0, 0.0, 1.0); }",
        )
        true
    } catch (t: Throwable) {
        false
    }

    /** Constructs the AGSL engine; a source compile failure throws and the
     * provider's buildEngines degrades the lane to the scrim. */
    fun createEngine(chromaticSpread: Float): SovereignGraphicsEngine =
        AgslRuntimeShaderEngine(chromaticSpread)
}

/** API 31-32 isolated lane — `RenderEffect.createBlurEffect`. */
@RequiresApi(31)
private object Blur31Isolated {

    fun createEngine(): SovereignGraphicsEngine = RenderEffectBlurEngine()
}

/**
 * Tier 1 / Tier 2 (shader-capable) engine — the real AGSL refraction lane.
 *
 * The [RuntimeShader][android.graphics.RuntimeShader] is compiled once in
 * the constructor and the `RenderEffect` wrapper is built once too:
 * `RuntimeShader` uniform state flows through the effect, so the per-frame
 * cost is uniform rebinding only (zero allocation, honoring the draw-phase
 * invariant of the port).
 *
 * Application mechanics: the caller-owned backdrop layer's
 * `GraphicsLayer.renderEffect` property is set for the duration of this
 * draw and restored afterwards — `createRuntimeShaderEffect` feeds the
 * layer's content into the shader's `backdrop` child, so the refraction
 * composites on the GPU with no readback (docs/gpu/ARCHITECTURE.md
 * section 1: no CPU pixel stalls).
 */
@RequiresApi(33)
private class AgslRuntimeShaderEngine(
    private val chromaticSpread: Float,
) : SovereignGraphicsEngine {

    /** Throws on an AGSL-incompatible source — deliberate; the provider degrades the lane. */
    private val runtimeShader: android.graphics.RuntimeShader =
        android.graphics.RuntimeShader(AgslDialect.transpile(LiquidGlassSource.SKSL))

    private val renderEffect: RenderEffect =
        android.graphics.RenderEffect.createRuntimeShaderEffect(runtimeShader, "backdrop")
            .asComposeRenderEffect()

    override fun applyGlass(scope: DrawScope, request: GlassRenderRequest) {
        // The effect applies in the backdrop layer's own coordinate space —
        // resolution follows the layer size, not the glass clip size.
        val layerSize = request.backdrop.size
        runtimeShader.setFloatUniform("resolution", layerSize.width, layerSize.height)
        runtimeShader.setFloatUniform("lensRadius", PrimitiveTokens.lensCurvatureStandard)
        runtimeShader.setFloatUniform("refractionIndex", PrimitiveTokens.refractionIndexDefault)
        runtimeShader.setFloatUniform("chromaticSpread", chromaticSpread)
        val tint = if (request.tint == Color.Unspecified) {
            SemanticTokens.surfaceGlassMuted
        } else {
            request.tint
        }
        runtimeShader.setFloatUniform("tintColor", tint.red, tint.green, tint.blue, tint.alpha)

        val previousEffect = request.backdrop.renderEffect
        request.backdrop.renderEffect = renderEffect
        try {
            // tintFill = false: the tint is already mixed inside the shader
            // (mix(half4(c, a), tintColor, tintColor.a)) — drawing it again
            // would double-apply.
            drawGlassComposite(scope, request, tintFill = false)
        } finally {
            request.backdrop.renderEffect = previousEffect
        }
    }
}

/**
 * Tier 2 (API 31-32) engine — the fixed-function blur lane.
 *
 * `RenderEffect.createBlurEffect` in pixels, rebuilt only when the density
 * changes (radius token is Dp), then cached; applied to the caller-owned
 * backdrop layer with the same set / draw / restore discipline as the AGSL
 * lane.
 */
@RequiresApi(31)
private class RenderEffectBlurEngine : SovereignGraphicsEngine {

    private var cachedRadiusPx = -1f
    private var cachedEffect: RenderEffect? = null

    override fun applyGlass(scope: DrawScope, request: GlassRenderRequest) {
        val radiusPx = with(scope) { PrimitiveTokens.blurRadiusMedium.toPx() }
        if (cachedRadiusPx != radiusPx || cachedEffect == null) {
            cachedRadiusPx = radiusPx
            cachedEffect = android.graphics.RenderEffect
                .createBlurEffect(radiusPx, radiusPx)
                .asComposeRenderEffect()
        }

        val previousEffect = request.backdrop.renderEffect
        request.backdrop.renderEffect = cachedEffect
        try {
            drawGlassComposite(scope, request, tintFill = true)
        } finally {
            request.backdrop.renderEffect = previousEffect
        }
    }
}

/**
 * Clip + backdrop draw + optional tint fill + 1px specular rim — the draw
 * half shared by the AGSL and blur lanes (the effect attached to the layer
 * does the refract / blur work during `drawLayer`).
 */
private fun drawGlassComposite(
    scope: DrawScope,
    request: GlassRenderRequest,
    tintFill: Boolean,
) {
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
            drawLayer(request.backdrop)
            if (tintFill && request.tint != Color.Unspecified) {
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
