package com.streamify.graphics.engine

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.layer.drawLayer
import com.streamify.graphics.api.EffectRenderRequest
import com.streamify.graphics.api.EffectUniformSlot
import com.streamify.graphics.api.GlassRenderRequest
import com.streamify.graphics.api.SovereignGraphicsEngine
import com.streamify.graphics.api.drawEffectFallback
import com.streamify.graphics.effect.GraphicsEffectRegistry
// All four adapter helpers (AgslDialect, GlassRenderStrategy,
// LiquidGlassSource, ShaderTierLadder) are consumed here — one wildcard
// keeps the isolation-file import block short.
import com.streamify.graphics.engine.adapter.*
import com.streamify.graphics.governor.CapabilityProbe
import com.streamify.graphics.governor.RenderTier
import com.streamify.graphics.tokens.PrimitiveTokens
import com.streamify.graphics.tokens.SemanticTokens
import com.streamify.graphics.visualizer.GpuDataTexture
import java.nio.ByteBuffer

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
     *
     * Track B: the compile probe runs the **canonical** source (through
     * the [AgslDialect] transpile), not a minimal synthetic program — a
     * driver that compiles toy shaders but rejects valid LiquidGlass
     * source is exactly the failure this probe exists to catch. The probe
     * runs once, at startup, inside the isolated API 33 lane
     * ([Agsl33Isolated.probeCompile]); any Throwable (driver crash,
     * rejected syntax) is caught there, recorded in
     * [CapabilityProbe.compileProbeFailureReason], and the classification
     * degrades to the RENDER_EFFECT / scrim lanes **before the Compose
     * tree ever calls drawBehind** — this object is resolved by the Koin
     * graph at engine construction time, which precedes any first draw.
     */
    private val cachedProbe: CapabilityProbe by lazy {
        val sdk = Build.VERSION.SDK_INT
        val probeResult = if (sdk >= 33) Agsl33Isolated.probeCompile() else null
        CapabilityProbe(
            apiLevel = sdk,
            // GL_RENDERER requires a live EGL context — not available at
            // bootstrap. The software-rasterizer axis (axis 2) is fed by the
            // facade/Phase 4 lane; empty keeps the classifier conservative.
            rendererDescription = "",
            runtimeShaderCompileSupported = probeResult?.compiled ?: false,
            compileProbeFailureReason = probeResult?.failureReason,
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
                agslEngine(chromaticSpread = 0f, tier = RenderTier.Tier2_Balanced)
            GlassRenderStrategy.RUNTIME_SHADER_DISPERSION ->
                agslEngine(chromaticSpread = PrimitiveTokens.chromaticSpreadDefault, tier = RenderTier.Tier1_Ultra)
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
    private fun agslEngine(chromaticSpread: Float, tier: RenderTier): SovereignGraphicsEngine =
        if (Build.VERSION.SDK_INT >= 33) Agsl33Isolated.createEngine(chromaticSpread, tier)
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
     * Outcome of the isolated startup compile probe (Track B): the
     * canonical `LiquidGlassCore` source, transpiled for AGSL, run through
     * the platform's real `RuntimeShader` compiler once at startup.
     * [failureReason] records *why* the probe failed so the demotion is a
     * reviewable diagnostic, never a silent cliff.
     */
    data class CompileProbeResult(
        val compiled: Boolean,
        val failureReason: String?,
    )

    /**
     * The preflight compile probe (Track B). Runs the **canonical**
     * transpiled source — not a minimal synthetic program — through the
     * real platform compiler, isolated in this API-33-only type so the
     * probe can never class-link `RuntimeShader` on older devices.
     *
     * Non-blocking by construction: one compile attempt, bounded by the
     * driver's own compile path, executed once per process before the
     * first draw (the provider resolves it from the Koin graph at engine
     * construction, ahead of any `drawBehind`). Conservative by contract:
     * any `Throwable` — OEM driver crash inside `glCompileShader`, AGSL
     * syntax rejection, anything — means unsupported, with the reason
     * recorded for telemetry and logs.
     */
    fun probeCompile(): CompileProbeResult = try {
        android.graphics.RuntimeShader(AgslDialect.transpile(LiquidGlassSource.SKSL))
        CompileProbeResult(compiled = true, failureReason = null)
    } catch (t: Throwable) {
        // Swallowed deliberately: a broken vendor BSP must degrade the
        // lane (Tier 2 blur / Tier 3 scrim), never crash startup. The
        // reason is recorded and surfaced through
        // CapabilityProbe.compileProbeFailureReason.
        CompileProbeResult(compiled = false, failureReason = "${t::class.simpleName ?: "Throwable"}: ${t.message}")
    }

    /** Constructs the AGSL engine; a source compile failure throws and the
     * provider's buildEngines degrades the lane to the scrim. */
    fun createEngine(chromaticSpread: Float, tier: RenderTier): SovereignGraphicsEngine =
        AgslRuntimeShaderEngine(chromaticSpread, tier)
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
private class AgslCachedEffect(
    val shader: android.graphics.RuntimeShader,
    val brush: ShaderBrush,
)

@RequiresApi(33)
private class AgslCachedDataTexture(
    val width: Int,
    val height: Int,
    val bitmap: android.graphics.Bitmap,
    val shader: android.graphics.BitmapShader,
    var lastStamp: Long = -1L,
)

@RequiresApi(33)
private class AgslRuntimeShaderEngine(
    private val chromaticSpread: Float,
    private val tier: RenderTier,
) : SovereignGraphicsEngine {

    /** Lazy cache of compiled custom [GraphicsEffectRegistry] shaders; `null` marks compile failure. */
    private val effectShaderCache = HashMap<String, AgslCachedEffect?>()
    private val dataTextureCache = HashMap<String, AgslCachedDataTexture>()
    private val effectPath = Path()

    /** Throws on an AGSL-incompatible source — deliberate; the provider degrades the lane. */
    private val runtimeShader: android.graphics.RuntimeShader =
        android.graphics.RuntimeShader(AgslDialect.transpile(LiquidGlassSource.SKSL))

    private val renderEffect: RenderEffect =
        android.graphics.RenderEffect.createRuntimeShaderEffect(runtimeShader, "backdrop")
            .asComposeRenderEffect()

    override fun applyGlass(scope: DrawScope, request: GlassRenderRequest) {
        // The effect applies in the backdrop layer's own coordinate space —
        // resolution follows the layer size, not the glass clip size.
        // GraphicsLayer.size is an IntSize; the shader takes float pixels.
        val layerSize = request.backdrop.size
        runtimeShader.setFloatUniform(
            "resolution",
            layerSize.width.toFloat(),
            layerSize.height.toFloat(),
        )
        runtimeShader.setFloatUniform("lensRadius", PrimitiveTokens.lensCurvatureStandard)
        runtimeShader.setFloatUniform("refractionIndex", PrimitiveTokens.refractionIndexDefault)
        runtimeShader.setFloatUniform("chromaticSpread", chromaticSpread)
        // Kyant optical parity (S24): vibrancy, the adaptive "đục đen" scrim
        // and the directional 45° rim strength. The darkening ramp is
        // evaluated here on the CPU (AdaptiveLuminanceScrim) and bound as a
        // uniform so the shader folds it into the composite on the GPU.
        runtimeShader.setFloatUniform("vibrancy", PrimitiveTokens.vibrancySaturation)
        runtimeShader.setFloatUniform("darkenAmount", AdaptiveLuminanceScrim.alphaFor(request.luminance()))
        runtimeShader.setFloatUniform("specularIntensity", PrimitiveTokens.directionalSpecularIntensity)
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
            // would double-apply. Same for the adaptive scrim: the
            // darkenAmount uniform already folds the "đục đen" ramp in.
            drawGlassComposite(scope, request, tintFill = false, adaptiveScrim = false)
        } finally {
            request.backdrop.renderEffect = previousEffect
        }
    }

    override fun applyEffect(scope: DrawScope, request: EffectRenderRequest) {
        val effect = GraphicsEffectRegistry.get(request.effectId)
        if (effect == null || tier < effect.minTier) {
            drawEffectFallback(scope, request)
            return
        }
        val cached = if (effectShaderCache.containsKey(effect.id)) {
            effectShaderCache[effect.id]
        } else {
            val compiled = runCatching {
                val rs = android.graphics.RuntimeShader(effect.transpileToAgsl())
                AgslCachedEffect(rs, ShaderBrush(rs))
            }.getOrNull()
            effectShaderCache[effect.id] = compiled
            compiled
        }
        if (cached == null) {
            drawEffectFallback(scope, request)
            return
        }
        val shader = cached.shader
        try {
            val resName = request.resolutionUniform
            if (resName != null) {
                shader.setFloatUniform(resName, scope.size.width, scope.size.height)
            }
            for (slot in request.uniforms) {
                when (slot) {
                    is EffectUniformSlot.Int1 -> shader.setIntUniform(slot.name, slot.value())
                    is EffectUniformSlot.Float1 -> shader.setFloatUniform(slot.name, slot.value())
                    is EffectUniformSlot.Float2 -> shader.setFloatUniform(slot.name, slot.x(), slot.y())
                    is EffectUniformSlot.Float3 -> shader.setFloatUniform(slot.name, slot.x(), slot.y(), slot.z())
                    is EffectUniformSlot.Float4 -> shader.setFloatUniform(slot.name, slot.x(), slot.y(), slot.z(), slot.w())
                    is EffectUniformSlot.Color4 -> {
                        val c = slot.color()
                        shader.setFloatUniform(slot.name, c.red, c.green, c.blue, c.alpha)
                    }
                    is EffectUniformSlot.DataTexture -> {
                        val tex = slot.texture()
                        if (tex == null) {
                            drawEffectFallback(scope, request)
                            return
                        }
                        val childShader = uploadDataTexture(slot.name, tex)
                        shader.setInputShader(slot.name, childShader)
                    }
                }
            }
            with(scope) {
                val outline = request.shape.createOutline(size, layoutDirection, this)
                effectPath.reset()
                when (outline) {
                    is Outline.Rectangle -> effectPath.addRect(outline.rect)
                    is Outline.Rounded -> effectPath.addRoundRect(outline.roundRect)
                    is Outline.Generic -> effectPath.addPath(outline.path)
                }
                clipPath(effectPath) {
                    drawRect(brush = cached.brush)
                }
            }
        } catch (_: Throwable) {
            drawEffectFallback(scope, request)
        }
    }

    private fun uploadDataTexture(slotName: String, tex: GpuDataTexture): android.graphics.BitmapShader {
        val existing = dataTextureCache[slotName]
        val entry = if (existing != null && existing.width == tex.textureWidth && existing.height == tex.textureHeight) {
            existing
        } else {
            val bmp = android.graphics.Bitmap.createBitmap(
                tex.textureWidth,
                tex.textureHeight,
                android.graphics.Bitmap.Config.ARGB_8888,
            )
            val bs = android.graphics.BitmapShader(
                bmp,
                android.graphics.Shader.TileMode.CLAMP,
                android.graphics.Shader.TileMode.CLAMP,
            )
            AgslCachedDataTexture(tex.textureWidth, tex.textureHeight, bmp, bs).also {
                dataTextureCache[slotName] = it
            }
        }
        val stamp = tex.frameStamp
        if (entry.lastStamp != stamp) {
            entry.bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(tex.payload))
            entry.lastStamp = stamp
        }
        return entry.shader
    }
}

/**
 * Tier 2 (API 31-32) engine — the fixed-function blur lane.
 *
 * `RenderEffect.createBlurEffect` in pixels, rebuilt only when the density
 * changes (radius token is Dp), then cached; applied to the caller-owned
 * backdrop layer with the same set / draw / restore discipline as the AGSL
 * lane. The three-argument form (radiusX, radiusY, edgeTreatment) is the
 * API 31 surface — the two-argument convenience overload only exists from
 * API 33, so this lane pins the TileMode explicitly.
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
                .createBlurEffect(radiusPx, radiusPx, android.graphics.Shader.TileMode.CLAMP)
                .asComposeRenderEffect()
        }

        val previousEffect = request.backdrop.renderEffect
        request.backdrop.renderEffect = cachedEffect
        try {
            // tintFill = true: the blur lane cannot mix the tint inside a
            // fixed-function effect, and the adaptive scrim is drawn here as
            // a plain rect (Kyant's onDrawSurface drawRect) because no
            // runtime shader runs on this lane.
            drawGlassComposite(scope, request, tintFill = true, adaptiveScrim = true)
        } finally {
            request.backdrop.renderEffect = previousEffect
        }
    }
}

/**
 * Clip + backdrop draw + optional tint fill + adaptive scrim + 1px specular
 * rim — the draw half shared by the AGSL and blur lanes (the effect
 * attached to the layer does the refract / blur work during `drawLayer`).
 *
 * [adaptiveScrim] draws the "đục đen" ramp as a rect for lanes that cannot
 * fold it into a shader (the API 31-32 blur lane); the AGSL lane passes
 * `false` because its `darkenAmount` uniform already applies the identical
 * curve inside `LiquidGlassCore.sksl`.
 */
private fun drawGlassComposite(
    scope: DrawScope,
    request: GlassRenderRequest,
    tintFill: Boolean,
    adaptiveScrim: Boolean,
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
            if (adaptiveScrim) {
                drawAdaptiveScrim(request)
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
