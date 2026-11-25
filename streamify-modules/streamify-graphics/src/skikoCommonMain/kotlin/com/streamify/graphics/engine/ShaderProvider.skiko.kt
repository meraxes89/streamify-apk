package com.streamify.graphics.engine

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
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
import kotlin.math.ceil

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
 * ## Tier 1/2 application seam
 *
 * [SkikoRuntimeShaderEngine] keeps the compiled program live and rebinds
 * request-driven uniforms every draw pass. When the facade feeds a live
 * [GlassRenderRequest.snapshot] (the S24 Track B offscreen pipeline —
 * `Modifier.streamifyGlass(rememberGlassBackdrop(layer), …)`), the engine
 * runs the real backdrop refraction through
 * [SkikoOffscreenGlassPass]: Tier 1 full-resolution dispersion, Tier 2
 * 0.5× DRS with bilinear upscale. Without a snapshot the stopgap
 * composite (backdrop draw + tint + 1px rim) renders, exactly the
 * Phase 2-B blessed behavior.
 */
actual object ShaderProvider {

    /** Cached per process — `by lazy` is synchronized; safe from any thread. */
    private val cachedProbe: CapabilityProbe by lazy {
        val failureReason = try {
            RuntimeEffect.makeForShader(LiquidGlassSource.SKSL).close()
            null
        } catch (t: Throwable) {
            // Track B parity: record why the canonical source failed to
            // compile on this host — the skiko twin of the Android probe's
            // failure reason, surfaced through
            // CapabilityProbe.compileProbeFailureReason. `simpleName` over
            // `javaClass` (JVM-only): skikoCommonMain spans wasmJs too.
            "${t::class.simpleName ?: "Throwable"}: ${t.message}"
        }
        CapabilityProbe(
            // Non-Android target: API level is meaningless (probe contract).
            apiLevel = 0,
            // S24 Track A: live renderer string — software-rasterizer markers
            // (llvmpipe / softpipe / swrast / WARP / Basic Render Driver /
            // rdpbus / SwiftShader) clamp the device to Tier 4 before the
            // compile axis is even consulted (axis 2 dominates axis 1).
            rendererDescription = liveRendererDescription(),
            runtimeShaderCompileSupported = failureReason == null,
            compileProbeFailureReason = failureReason,
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
                // Tier 2 (Balanced) on the runtime-shader lane: the UMA iGPU
                // DRS configuration — 0.5x offscreen pass, bilinear upscale.
                programEngine(
                    chromaticSpread = 0f,
                    passScale = SkikoOffscreenGlassPass.TIER2_BALANCED_SCALE,
                )
            GlassRenderStrategy.RUNTIME_SHADER_DISPERSION ->
                // Tier 1 (Ultra): full-resolution refraction + dispersion.
                programEngine(
                    chromaticSpread = PrimitiveTokens.chromaticSpreadDefault,
                    passScale = 1.0f,
                )
        }

    private fun programEngine(
        chromaticSpread: Float,
        passScale: Float,
    ): SovereignGraphicsEngine {
        val program = liquidGlassProgram
            // Program failed validation on this host — degrade to the scrim.
            ?: return AcrylicScrimGlassEngine
        return SkikoRuntimeShaderEngine(program, chromaticSpread, passScale)
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
 * **Offscreen refraction lane (S24 Track B)** — when the request carries a
 * [GlassRenderRequest.snapshot] (captured off the draw phase by
 * [rememberGlassBackdrop][com.streamify.graphics.api.rememberGlassBackdrop]),
 * the engine runs [SkikoOffscreenGlassPass] over the live backdrop pixels:
 * full-resolution dispersion on Tier 1, 0.5× Dynamic Resolution Scaling
 * with a bilinear upscale on the Tier 2 UMA-iGPU lane. The pass caches its
 * surface, conversions and result by snapshot identity, so a steady-state
 * draw is one `drawImage` + rim stroke — zero allocation.
 *
 * **Stopgap lane** — without a snapshot the request-driven uniforms are
 * rebound on the shared [SkikoLiquidGlassProgram] (kept live and
 * validated — a bind failure is contained by `runCatching` and never
 * reaches the draw), and the stable composite renders: clipped backdrop
 * draw, optional tint fill, 1px specular rim.
 */
internal class SkikoRuntimeShaderEngine(
    private val program: SkikoLiquidGlassProgram,
    private val chromaticSpread: Float,
    private val passScale: Float,
) : SovereignGraphicsEngine {

    /** Lazy offscreen pass — built on the first snapshot-fed draw, reused after. */
    private val offscreenPass by lazy {
        SkikoOffscreenGlassPass(program, chromaticSpread, passScale)
    }

    override fun applyGlass(scope: DrawScope, request: GlassRenderRequest) {
        val tint = if (request.tint == Color.Unspecified) {
            SemanticTokens.surfaceGlassMuted
        } else {
            request.tint
        }

        // Offscreen refraction lane: physical pixel extent of the draw scope
        // (Compose Desktop records the picture at width * contentScale, so
        // scope.size already IS the physical buffer size — the Track D
        // fractional-DPI contract; see SkikoOffscreenGlassPass KDoc).
        val snapshot = request.snapshot
        if (snapshot != null) {
            val widthPx = ceil(scope.size.width).toInt()
            val heightPx = ceil(scope.size.height).toInt()
            val refracted = runCatching {
                snapshot.latestInternal?.let { latest ->
                    offscreenPass.render(latest, widthPx, heightPx, tint)
                }
            }.getOrNull()
            if (refracted != null) {
                drawRefractedComposite(scope, request, refracted)
                return
            }
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

    /**
     * Clip + refracted backdrop draw + 1px specular rim. The tint is folded
     * into the shader's `tintColor` mix by the pass, so no separate fill is
     * drawn on this lane. The 0.5x Tier 2 result is upscaled with bilinear
     * filtering; the 1.0x Tier 1 result draws 1:1 unfiltered.
     */
    private fun drawRefractedComposite(
        scope: DrawScope,
        request: GlassRenderRequest,
        refracted: ImageBitmap,
    ) {
        val outlinePath = flattenedOutlinePath(scope, request)
        with(scope) {
            clipPath(outlinePath) {
                drawImage(
                    image = refracted,
                    srcOffset = IntOffset.Zero,
                    srcSize = IntSize(refracted.width, refracted.height),
                    dstOffset = IntOffset.Zero,
                    dstSize = IntSize(
                        ceil(size.width).toInt().coerceAtLeast(1),
                        ceil(size.height).toInt().coerceAtLeast(1),
                    ),
                    // Compose's FilterQuality scale: None = nearest (exact
                    // 1:1 draws), Low = bilinear — the DRS upscale lane.
                    filterQuality = if (passScale < 1.0f) {
                        FilterQuality.Low
                    } else {
                        FilterQuality.None
                    },
                )
            }
            drawOutline(
                request.shape.createOutline(
                    size = scope.size,
                    layoutDirection = scope.layoutDirection,
                    density = scope,
                ),
                SolidColor(SemanticTokens.specularBorderHighlight),
                style = Stroke(width = PrimitiveTokens.specularStrokeWidth.toPx()),
            )
        }
    }
}

/**
 * Flattens the request's shape outline to a clip path — this Compose
 * revision has no `clipOutline` on DrawScope, and shapes resolve to
 * exactly one of three outline variants.
 */
private fun flattenedOutlinePath(scope: DrawScope, request: GlassRenderRequest): Path {
    val outline = request.shape.createOutline(
        size = scope.size,
        layoutDirection = scope.layoutDirection,
        density = scope,
    )
    return Path().apply {
        when (outline) {
            is Outline.Rectangle -> addRect(outline.rect)
            is Outline.Rounded -> addRoundRect(outline.roundRect)
            is Outline.Generic -> addPath(outline.path)
        }
    }
}

/** Clip + backdrop draw + optional tint fill + 1px specular rim. */
private fun drawGlassComposite(scope: DrawScope, request: GlassRenderRequest) {
    val outlinePath = flattenedOutlinePath(scope, request)

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
            request.shape.createOutline(
                size = scope.size,
                layoutDirection = scope.layoutDirection,
                density = scope,
            ),
            SolidColor(SemanticTokens.specularBorderHighlight),
            style = Stroke(width = PrimitiveTokens.specularStrokeWidth.toPx()),
        )
    }
}
