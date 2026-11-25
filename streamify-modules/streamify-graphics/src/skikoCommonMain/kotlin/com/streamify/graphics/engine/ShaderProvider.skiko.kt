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
import com.streamify.graphics.governor.BackdropResolutionPolicy
import com.streamify.graphics.governor.CapabilityProbe
import com.streamify.graphics.governor.RenderTier
import com.streamify.graphics.tokens.PrimitiveTokens
import com.streamify.graphics.tokens.SemanticTokens
import kotlin.concurrent.Volatile
import org.jetbrains.skia.Color4f
import org.jetbrains.skia.FilterTileMode
import org.jetbrains.skia.Gradient
import org.jetbrains.skia.RuntimeEffect
import org.jetbrains.skia.RuntimeShaderBuilder
import org.jetbrains.skia.Shader
import kotlin.math.ceil

/**
 * Skiko actual of the platform shader adapter (desktop JVM + wasmJs + iOS shared
 * skikoCommonMain source set) — the skiko adapter lane of
 * docs/gpu/ARCHITECTURE.md section 1 and the Apple matrix of
 * docs/systems/S24-sovereign-graphics.md.
 *
 * ## Capability axis
 *
 * Non-Android targets have no API level — Skia `RuntimeEffect` validation
 * IS the axis (ARCHITECTURE.md section 3, axis 1): [probe] compiles the
 * canonical `LiquidGlassCore` source through
 * `RuntimeEffect.makeForShader()` on the live skiko backend. A successful
 * compile classifies `RUNTIME_SHADER` (Tier 1 ceiling); any Throwable
 * classifies `FIXED_FUNCTION` (Tier 3 ceiling) — the conservative-by-
 * contract rule of [CapabilityProbe]. The probe's renderer description
 * carries the environment diagnostics of [skikoRendererDescription]
 * (headless JVM / iOS Simulator detection, Track D) — deliberately free
 * of the software-rasterizer marker vocabulary, so headless CI and the
 * simulator stay on the full capability matrix.
 *
 * ## The Skia dialect seam
 *
 * The canonical source's `uniform shader backdrop` child is bound through
 * `RuntimeShaderBuilder.child("backdrop", ...)` — the Skia dialect's
 * counterpart of Android AGSL's setInputBuffer lane (ARCHITECTURE.md
 * section 2). All nine uniforms bind through [SkikoLiquidGlassProgram].
 *
 * ## TBDR-friendly dynamic resolution scaling (Track C)
 *
 * The Tier 2 (single-tap) engine is constructed with the capture scale
 * [BackdropResolutionPolicy] yields for the target's display density
 * ([skikoDisplayDensityScale]): on Retina 2x/3x displays the engine owns a
 * [DynamicResolutionOffscreenPass] that renders the refraction composite
 * at logical-1x resolution whenever a backdrop snapshot is bound, cutting
 * Apple-Silicon tile-buffer flushes by ~75%. Tier 1 (earned full-res) and
 * the scrim lanes construct no pass.
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
            rendererDescription = liveRendererDescription().ifEmpty { skikoRendererDescription() },
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
                programEngine(
                    chromaticSpread = 0f,
                    tier = RenderTier.Tier2_Balanced,
                )
            GlassRenderStrategy.RUNTIME_SHADER_DISPERSION ->
                programEngine(
                    chromaticSpread = PrimitiveTokens.chromaticSpreadDefault,
                    tier = RenderTier.Tier1_Ultra,
                )
        }

    private fun programEngine(
        chromaticSpread: Float,
        tier: RenderTier,
    ): SovereignGraphicsEngine {
        val program = liquidGlassProgram
            // Program failed validation on this host — degrade to the scrim.
            ?: return AcrylicScrimGlassEngine
        // Track C: the Tier 2 (single-tap) engine on a Retina-class display
        // renders its refraction composite through the DRS offscreen pass
        // at logical-1x capture scale; Tier 1 earned full resolution.
        val captureScale = BackdropResolutionPolicy.captureScale(
            tier = tier,
            displayDensityScale = skikoDisplayDensityScale(),
        )
        return SkikoRuntimeShaderEngine(
            program = program,
            chromaticSpread = chromaticSpread,
            passScale = captureScale,
        )
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
 *
 * ## Pipeline-state validation (S24 Track D)
 *
 * The constructor validates the **full** pipeline, not just the source
 * compile: after `RuntimeEffect.makeForShader` it binds a trivial backdrop
 * child and materializes one shader through `makeShader()` with trivial
 * uniforms. Any unexpected pipeline-state creation error (Metal backend
 * included) therefore throws *here*, the `liquidGlassProgram` lazy holder
 * converts it to `null`, and every engine lane degrades to
 * [AcrylicScrimGlassEngine] — never a draw-phase crash on an
 * uninitialized Metal layer or a headless framebuffer.
 */
internal class SkikoLiquidGlassProgram internal constructor() {

    /** Throws on an invalid source — the lazy holder converts this to `null`. */
    val effect: RuntimeEffect = RuntimeEffect.makeForShader(LiquidGlassSource.SKSL)

    private val builder: RuntimeShaderBuilder = RuntimeShaderBuilder(effect)

    init {
        // Track D: full pipeline-state validation (see class KDoc). The
        // trivial child/uniform values are irrelevant — this exercises the
        // child binding + shader materialization path once, off any draw.
        builder.child(
            "backdrop",
            Shader.makeLinearGradient(
                0f,
                0f,
                1f,
                0f,
                Gradient(
                    Gradient.Colors(
                        arrayOf(
                            Color4f(0f, 0f, 0f, 1.0f),
                            Color4f(1f, 1f, 1f, 1.0f),
                        ),
                        null,
                        FilterTileMode.CLAMP,
                    ),
                ),
            ),
        )
        builder.uniform("resolution", 1f, 1f)
        builder.uniform("lensRadius", 0.25f)
        builder.uniform("refractionIndex", 1.25f)
        builder.uniform("chromaticSpread", 0f)
        builder.uniform("tintColor", 1f, 1f, 1f, 1f)
        builder.makeShader().close()
    }

    /** Whether a backdrop child is currently bound (the offscreen snapshot seam). */
    @Volatile
    private var backdropBound: Boolean = false

    /** True once [bindBackdrop] has bound a snapshot shader for this program. */
    val hasBoundBackdrop: Boolean
        get() = backdropBound

    /**
     * The Skia dialect seam: binds the caller's backdrop as the shader's
     * `backdrop` child — `child("backdrop", ...)` where the Android lane
     * uses setInputBuffer (ARCHITECTURE.md section 2). Binding also arms
     * the Tier 2 engine's dynamic-resolution path (S24 Track C).
     */
    fun bindBackdrop(backdrop: Shader) {
        builder.child("backdrop", backdrop)
        backdropBound = true
    }

    /**
     * Binds all eight non-child uniforms:
     *  * `resolution` (float2) — the drawing surface size,
     *  * `lensRadius` / `refractionIndex` / `chromaticSpread` (float) —
     *    lens curvature boundary, IOR and dispersion delta,
     *  * `tintColor` (half4, promoted to float4 by the AGSL dialect on
     *    Android) — the translucent surface fill,
     *  * `vibrancy` / `darkenAmount` / `specularIntensity` (float) — the
     *    Kyant parity pillars: 1.5x luminance-preserving saturation, the
     *    adaptive "đục đen" scrim alpha and the directional 45° rim
     *    strength.
     *
     * The S24 defaults resolve from the frozen token layer so a caller that
     * names nothing but the chromatic spread and the tint still renders
     * with full Kyant parity. `darkenAmount` defaults to 0 (no scrim) — the
     * engine always binds the value [AdaptiveLuminanceScrim] computes from
     * the request's live backdrop luminance.
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
        vibrancy: Float = PrimitiveTokens.vibrancySaturation,
        darkenAmount: Float = 0f,
        specularIntensity: Float = PrimitiveTokens.directionalSpecularIntensity,
    ) {
        builder.uniform("resolution", width, height)
        builder.uniform("lensRadius", lensRadius)
        builder.uniform("refractionIndex", refractionIndex)
        builder.uniform("chromaticSpread", chromaticSpread)
        builder.uniform("tintColor", tintColorR, tintColorG, tintColorB, tintColorA)
        builder.uniform("vibrancy", vibrancy)
        builder.uniform("darkenAmount", darkenAmount)
        builder.uniform("specularIntensity", specularIntensity)
    }

    /** Runs the final uniform-layout validation and builds the shader. */
    fun makeShader(): Shader = builder.makeShader()
}

/**
 * Tier 1 / Tier 2 engine for the skiko targets.
 *
 * **Offscreen refraction lane (S24 Track B/C)** — when the request carries a
 * [GlassRenderRequest.snapshot] (captured off the draw phase by
 * [rememberGlassBackdrop][com.streamify.graphics.api.rememberGlassBackdrop]),
 * the engine runs [SkikoOffscreenGlassPass] over the live backdrop pixels:
 * full-resolution dispersion on Tier 1, Dynamic Resolution Scaling (0.5x / 0.33x)
 * with a bilinear upscale on the Tier 2 lane. The pass caches its
 * surface, conversions and result by snapshot identity, so a steady-state
 * draw is one `drawImage` + rim stroke — zero allocation.
 *
 * **TBDR dynamic resolution path** — when [backdropCaptureScale] is below 1.0
 * and the program carries a bound backdrop snapshot shader, the refraction
 * composite renders through [DynamicResolutionOffscreenPass] at the reduced
 * capture resolution and is upscaled back into the destination outline with
 * hardware bilinear filtering.
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
    passScale: Float = 1.0f,
    backdropCaptureScale: Float = passScale,
) : SovereignGraphicsEngine {

    val passScale: Float = if (backdropCaptureScale != 1.0f && passScale == 1.0f) backdropCaptureScale else passScale
    val backdropCaptureScale: Float get() = this.passScale

    /** Lazy offscreen pass — built on the first snapshot-fed draw, reused after. */
    private val offscreenPass by lazy {
        SkikoOffscreenGlassPass(program, chromaticSpread, this.passScale)
    }

    /** Instantiated lazily-once at construction — only when the policy downsamples. */
    private val dynamicResolutionPass: DynamicResolutionOffscreenPass? =
        if (this.passScale < 1f) DynamicResolutionOffscreenPass(this.passScale) else null

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
            val darkenAmount = AdaptiveLuminanceScrim.alphaFor(request.luminance())
            val refracted = runCatching {
                snapshot.latestInternal?.let { latest ->
                    offscreenPass.render(latest, widthPx, heightPx, tint, darkenAmount)
                }
            }.getOrNull()
            if (refracted != null) {
                drawRefractedComposite(scope, request, refracted)
                return
            }
        }

        val drsPass = dynamicResolutionPass
        if (drsPass != null && program.hasBoundBackdrop) {
            val darkenAmount = AdaptiveLuminanceScrim.alphaFor(request.luminance())
            val composite = runCatching {
                drsPass.render(
                    program = program,
                    destinationWidthPx = scope.size.width,
                    destinationHeightPx = scope.size.height,
                    chromaticSpread = chromaticSpread,
                    tint = tint,
                    darkenAmount = darkenAmount,
                )
            }.getOrNull()
            if (composite != null) {
                drawRefractedComposite(scope, request, composite)
                return
            }
            // Pass failure on this host — fall through to the stable
            // composite; a draw must never crash (Track D).
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
                // Kyant optical parity (S24): the "đục đen" ramp evaluated on
                // the CPU from the request's live luminance and bound as the
                // shader's darkenAmount uniform (vibrancy and the directional
                // 45° rim strength resolve from the token defaults above).
                darkenAmount = AdaptiveLuminanceScrim.alphaFor(request.luminance()),
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
            // Kyant parity (S24): the adaptive "đục đen" scrim over the
            // composite — over bright artwork the surface deepens so
            // overlaying typography stays legible.
            drawAdaptiveScrim(request)
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
