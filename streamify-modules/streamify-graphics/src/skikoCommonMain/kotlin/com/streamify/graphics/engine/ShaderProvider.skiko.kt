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
import com.streamify.graphics.engine.adapter.GlassRenderStrategy
import com.streamify.graphics.engine.adapter.LiquidGlassSource
import com.streamify.graphics.engine.adapter.ShaderTierLadder
import com.streamify.graphics.governor.CapabilityProbe
import com.streamify.graphics.governor.RenderTier
import com.streamify.graphics.tokens.PrimitiveTokens
import com.streamify.graphics.tokens.SemanticTokens
import org.jetbrains.skia.RuntimeEffect
import org.jetbrains.skia.RuntimeShaderBuilder
import org.jetbrains.skia.Shader

/**
 * Skiko actual of the platform shader adapter (desktop JVM + wasmJs + iOS shared
 * skikoCommonMain source set) — Track A deliverable 2 of
 * docs/gpu/PHASED_PLAN.md Phase 2, per ARCHITECTURE.md section 1's
 * [Adapters] skikoCommonMain -> Skia RuntimeShaderBuilder (SkSL) line.
 *
 * ## Capability axis
 *
 * Non-Android targets have no API level — Skia `RuntimeEffect` validation
 * IS the axis (ARCHITECTURE.md section 3, axis 1): [probe] compiles the
 * canonical `LiquidGlassCore` source through
 * `RuntimeEffect.makeForShader()` on the live skiko backend. A successful
 * compile classifies `RUNTIME_SHADER` (Tier 1 ceiling); any Throwable
 * classifies `FIXED_FUNCTION` (Tier 3 ceiling) — the conservative-by-
 * contract rule of [CapabilityProbe].
 *
 * ## The Skia dialect seam
 *
 * The canonical source's `uniform shader backdrop` child is bound through
 * `RuntimeShaderBuilder.child("backdrop", ...)` — the Skia dialect's
 * counterpart of Android AGSL's setInputBuffer lane (ARCHITECTURE.md
 * section 2). All six uniforms bind through [SkikoLiquidGlassProgram].
 *
 * ## Tier 1/2 application seam (documented stopgap)
 *
 * [SkikoRuntimeShaderEngine] keeps the compiled program live and rebinds
 * request-driven uniforms every draw pass, but the refraction composite —
 * sampling the backdrop layer pixels through the `backdrop` child — needs
 * layer-snapshot access that is only available off the draw phase
 * (`GraphicsLayer.toImageBitmap()` is a suspend API). The stable composite
 * (backdrop draw + tint + 1px rim) renders meanwhile; the facade-level
 * offscreen pipeline that feeds real backdrop snapshots into
 * [SkikoLiquidGlassProgram.bindBackdrop] is the next seam, exactly the
 * pattern the TierGovernedGlassEngine stopgap of Phase 2-B blessed.
 */
actual object ShaderProvider {

    /** Cached per process — `by lazy` is synchronized; safe from any thread. */
    private val cachedProbe: CapabilityProbe by lazy {
        CapabilityProbe(
            // Non-Android target: API level is meaningless (probe contract).
            apiLevel = 0,
            // Software-rasterizer detection needs the live renderer string
            // (Phase 4 / CI_TESTING_SPEC Job 4); empty stays conservative.
            rendererDescription = "",
            runtimeShaderCompileSupported = try {
                RuntimeEffect.makeForShader(LiquidGlassSource.SKSL).close()
                true
            } catch (t: Throwable) {
                false
            },
        )
    }

    actual fun probe(): CapabilityProbe = cachedProbe

    /**
     * Cached per-tier engines — [engineFor] runs on **every draw pass**, so
     * after the one-time construction it is a plain array lookup.
     */
    private val enginesByTier: Array<SovereignGraphicsEngine?> by lazy { buildEngines() }

    actual fun engineFor(tier: RenderTier): SovereignGraphicsEngine =
        enginesByTier[tier.ordinal] ?: LegacyRedirectGlassEngine

    private fun buildEngines(): Array<SovereignGraphicsEngine?> {
        val probe = cachedProbe
        val engines = arrayOfNulls<SovereignGraphicsEngine>(RenderTier.entries.size)
        for (tier in RenderTier.entries) {
            val plan = ShaderTierLadder.planFor(tier, probe.shaderCapability)
            engines[tier.ordinal] = try {
                engineForStrategy(plan.strategy)
            } catch (t: Throwable) {
                // Shader Compilation Failure Fallback shape (Job 5): a
                // program that fails to build degrades to the scrim lane.
                AcrylicScrimGlassEngine
            }
        }
        return engines
    }

    private fun engineForStrategy(strategy: GlassRenderStrategy): SovereignGraphicsEngine =
        when (strategy) {
            GlassRenderStrategy.SOLID_TINT -> LegacyRedirectGlassEngine
            GlassRenderStrategy.ACRYLIC_SCRIM -> AcrylicScrimGlassEngine

            // Defensive arm: the RENDER_EFFECT classification is Android
            // API 31-32 only — a skiko probe never produces it, and a
            // hand-fed plan maps it to the closest fixed-function degrade.
            GlassRenderStrategy.BLUR_RENDER_EFFECT -> AcrylicScrimGlassEngine

            GlassRenderStrategy.RUNTIME_SHADER_SINGLE_TAP ->
                programEngine(chromaticSpread = 0f)
            GlassRenderStrategy.RUNTIME_SHADER_DISPERSION ->
                programEngine(chromaticSpread = PrimitiveTokens.chromaticSpreadDefault)
        }

    private fun programEngine(chromaticSpread: Float): SovereignGraphicsEngine {
        val program = liquidGlassProgram
            // Program failed validation on this host — degrade to the scrim.
            ?: return AcrylicScrimGlassEngine
        return SkikoRuntimeShaderEngine(program, chromaticSpread)
    }
}

/**
 * The live LiquidGlass shader program for the skiko targets — compiled once
 * per process from the canonical source. `null` after a compile failure;
 * holders degrade their lane instead of throwing.
 *
 * Internal so the desktop JVM test suite can render through the exact
 * production pipeline (clean-render gate, CI_TESTING_SPEC.md section 2).
 */
internal val liquidGlassProgram: SkikoLiquidGlassProgram? by lazy {
    try {
        SkikoLiquidGlassProgram()
    } catch (t: Throwable) {
        null
    }
}

/**
 * A compiled `LiquidGlassCore` runtime program — the six-uniform binding
 * surface shared by the engine and the CI render gates.
 *
 * The [effect] handle and the [RuntimeShaderBuilder] are constructed once
 * and reused; per-draw work is uniform rebinding only (zero allocation,
 * honoring the port's draw-phase invariant).
 */
internal class SkikoLiquidGlassProgram internal constructor() {

    /** Throws on an invalid source — the lazy holder converts this to `null`. */
    val effect: RuntimeEffect = RuntimeEffect.makeForShader(LiquidGlassSource.SKSL)

    private val builder: RuntimeShaderBuilder = RuntimeShaderBuilder(effect)

    /**
     * The Skia dialect seam: binds the caller's backdrop as the shader's
     * `backdrop` child — `child("backdrop", ...)` where the Android lane
     * uses setInputBuffer (ARCHITECTURE.md section 2).
     */
    fun bindBackdrop(backdrop: Shader) {
        builder.child("backdrop", backdrop)
    }

    /**
     * Binds all five non-child uniforms:
     *  * `resolution` (float2) — the drawing surface size,
     *  * `lensRadius` / `refractionIndex` / `chromaticSpread` (float) —
     *    lens curvature boundary, IOR and dispersion delta,
     *  * `tintColor` (half4, promoted to float4 by the AGSL dialect on
     *    Android) — the translucent surface fill.
     */
    fun bindUniforms(
        width: Float,
        height: Float,
        lensRadius: Float = PrimitiveTokens.lensCurvatureStandard,
        refractionIndex: Float = PrimitiveTokens.refractionIndexDefault,
        chromaticSpread: Float,
        tintColorR: Float,
        tintColorG: Float,
        tintColorB: Float,
        tintColorA: Float,
    ) {
        builder.uniform("resolution", width, height)
        builder.uniform("lensRadius", lensRadius)
        builder.uniform("refractionIndex", refractionIndex)
        builder.uniform("chromaticSpread", chromaticSpread)
        builder.uniform("tintColor", tintColorR, tintColorG, tintColorB, tintColorA)
    }

    /** Runs the final uniform-layout validation and builds the shader. */
    fun makeShader(): Shader = builder.makeShader()
}

/**
 * Tier 1 / Tier 2 engine for the skiko targets.
 *
 * Per draw pass the request-driven uniforms are rebound on the shared
 * [SkikoLiquidGlassProgram] (kept live and validated — a bind failure is
 * contained by `runCatching` and never reaches the draw), while the stable
 * composite renders: clipped backdrop draw, optional tint fill, 1px
 * specular rim. See the class-level KDoc of [ShaderProvider] for the
 * facade-side refraction seam this engine is wired for.
 */
internal class SkikoRuntimeShaderEngine(
    private val program: SkikoLiquidGlassProgram,
    private val chromaticSpread: Float,
) : SovereignGraphicsEngine {

    override fun applyGlass(scope: DrawScope, request: GlassRenderRequest) {
        val tint = if (request.tint == Color.Unspecified) {
            SemanticTokens.surfaceGlassMuted
        } else {
            request.tint
        }
        runCatching {
            program.bindUniforms(
                width = scope.size.width,
                height = scope.size.height,
                chromaticSpread = chromaticSpread,
                tintColorR = tint.red,
                tintColorG = tint.green,
                tintColorB = tint.blue,
                tintColorA = tint.alpha,
            )
        }
        drawGlassComposite(scope, request)
    }
}

/** Clip + backdrop draw + optional tint fill + 1px specular rim. */
private fun drawGlassComposite(scope: DrawScope, request: GlassRenderRequest) {
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
            // Un-refracted backdrop until the facade feeds layer snapshots
            // into the program's `backdrop` child (documented seam). An
            // unrecorded layer draws nothing — by design.
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
