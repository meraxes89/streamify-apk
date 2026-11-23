package com.streamify.graphics.engine.adapter

import com.streamify.graphics.governor.CapabilityProbe
import com.streamify.graphics.governor.RenderTier
import com.streamify.graphics.governor.ShaderCapability

/**
 * Execution strategy a platform adapter implements for one (tier,
 * capability) pair — the platform-agnostic half of the tier ladder
 * (docs/gpu/ARCHITECTURE.md section 3).
 *
 * Every strategy states its GPU program budget explicitly so the CI
 * invariants ("API 27 allocates 0 GPU shader programs",
 * docs/gpu/CI_TESTING_SPEC.md section 2) are assertions over this enum,
 * not over prose.
 */
enum class GlassRenderStrategy {

    /**
     * Tier 1 (Ultra): the canonical `LiquidGlassCore` runtime shader with
     * 3-tap chromatic dispersion (`chromaticSpread > 0`). Exactly **1**
     * runtime shader program.
     */
    RUNTIME_SHADER_DISPERSION,

    /**
     * Tier 2 (Balanced) on runtime-shader-capable targets: the same program
     * with `chromaticSpread = 0.0` collapsing the dispersion to a single
     * backdrop tap. Exactly **1** runtime shader program.
     */
    RUNTIME_SHADER_SINGLE_TAP,

    /**
     * Tier 2 (Balanced) on the fixed-function-effect lane (Android API 31-32):
     * `RenderEffect.createBlurEffect()` — a GPU blur pass with **0** runtime
     * shader programs.
     */
    BLUR_RENDER_EFFECT,

    /**
     * Tier 3 (Legacy): hardware-cached acrylic scrim + 1px optical specular
     * rim (Vivo 1712 / Android 8.1 / API 26-30 lane, and the demotion
     * target). **0** runtime shader programs, **0** blur passes.
     */
    ACRYLIC_SCRIM,

    /** Tier 4 (Safe): flat solid-tint failover. **0** programs, **0** passes. */
    SOLID_TINT,
}

/**
 * The resolved execution plan for one draw context: which [RenderTier] the
 * engine runs at, which [GlassRenderStrategy] the adapter implements, and
 * how many runtime shader programs that strategy instantiates.
 *
 * Pure data — produced exclusively by [ShaderTierLadder], consumed by the
 * platform `ShaderProvider` actuals and pinned by the desktop JVM test
 * suite.
 */
data class AdapterExecutionPlan(
    val tier: RenderTier,
    val strategy: GlassRenderStrategy,
    val shaderProgramCount: Int,
)

/**
 * Pure tier ladder of the Sovereign GPU Engine (docs/gpu/ARCHITECTURE.md
 * section 3): the total (tier x capability) -> strategy mapping shared by
 * every platform adapter.
 *
 * Both inputs come from the governor contracts — the live tier from
 * [TierGovernor][com.streamify.graphics.governor.TierGovernor], the
 * capability from [CapabilityProbe] — and both outputs are data the
 * adapters mechanically honor. The ladder itself never calls a platform
 * API, so the full matrix is unit-testable in common code
 * (`desktopTest/.../adapter/ShaderTierLadderTest.kt`), including the hard
 * API 27 invariant (Tier 3, 0 shader programs).
 *
 * Defensive rows: the governor already clamps every tier below the
 * [CapabilityProbe.hardwareCeiling], so e.g. a Tier 1 demand on a
 * FIXED_FUNCTION device cannot occur through the governor — but the ladder
 * is total anyway and degrades gracefully rather than throwing.
 */
object ShaderTierLadder {

    /**
     * The total matrix. Rows are tiers, columns are capabilities:
     *
     * | Tier | RUNTIME_SHADER      | RENDER_EFFECT  | FIXED_FUNCTION |
     * |------|---------------------|----------------|----------------|
     * | T1   | DISPERSION (1 prog) | BLUR (0 progs) | SCRIM (0)      |
     * | T2   | SINGLE_TAP (1 prog) | BLUR (0 progs) | SCRIM (0)      |
     * | T3   | SCRIM (0)           | SCRIM (0)      | SCRIM (0)      |
     * | T4   | SOLID_TINT (0)      | SOLID_TINT (0) | SOLID_TINT (0) |
     */
    fun planFor(tier: RenderTier, capability: ShaderCapability): AdapterExecutionPlan =
        when (tier) {
            RenderTier.Tier4_Safe ->
                AdapterExecutionPlan(tier, GlassRenderStrategy.SOLID_TINT, shaderProgramCount = 0)

            RenderTier.Tier3_Legacy ->
                AdapterExecutionPlan(tier, GlassRenderStrategy.ACRYLIC_SCRIM, shaderProgramCount = 0)

            RenderTier.Tier2_Balanced ->
                when (capability) {
                    ShaderCapability.RUNTIME_SHADER ->
                        AdapterExecutionPlan(tier, GlassRenderStrategy.RUNTIME_SHADER_SINGLE_TAP, shaderProgramCount = 1)

                    ShaderCapability.RENDER_EFFECT ->
                        AdapterExecutionPlan(tier, GlassRenderStrategy.BLUR_RENDER_EFFECT, shaderProgramCount = 0)

                    ShaderCapability.FIXED_FUNCTION ->
                        AdapterExecutionPlan(tier, GlassRenderStrategy.ACRYLIC_SCRIM, shaderProgramCount = 0)
                }

            RenderTier.Tier1_Ultra ->
                when (capability) {
                    ShaderCapability.RUNTIME_SHADER ->
                        AdapterExecutionPlan(tier, GlassRenderStrategy.RUNTIME_SHADER_DISPERSION, shaderProgramCount = 1)

                    ShaderCapability.RENDER_EFFECT ->
                        // Defensive: the governor's promotion ceiling keeps a
                        // RENDER_EFFECT device at Tier 2, but if a Tier 1
                        // demand ever arrives here the blur lane is the best
                        // fixed-function degrade.
                        AdapterExecutionPlan(tier, GlassRenderStrategy.BLUR_RENDER_EFFECT, shaderProgramCount = 0)

                    ShaderCapability.FIXED_FUNCTION ->
                        AdapterExecutionPlan(tier, GlassRenderStrategy.ACRYLIC_SCRIM, shaderProgramCount = 0)
                }
        }

    /**
     * Signal-injection entry point: maps a raw device profile straight to
     * the best plan it could ever run (the probe's hardware ceiling), reusing
     * [CapabilityProbe]'s classification so the ladder and the governor can
     * never disagree about a device.
     *
     * `apiLevel = 27` (Vivo 1712 / Android 8.1) -> Tier 3 scrim with zero
     * shader programs. `apiLevel = 33, runtimeShaderCompileSupported = true`
     * -> Tier 1 dispersion with exactly one program. A software-rasterizer
     * marker collapses everything to Tier 4 regardless of API level
     * (axis 2 dominates axis 1).
     */
    fun bestPlanFor(
        apiLevel: Int,
        rendererDescription: String = "",
        runtimeShaderCompileSupported: Boolean = false,
    ): AdapterExecutionPlan {
        val probe = CapabilityProbe(
            apiLevel = apiLevel,
            rendererDescription = rendererDescription,
            runtimeShaderCompileSupported = runtimeShaderCompileSupported,
        )
        return planFor(tier = probe.hardwareCeiling, capability = probe.shaderCapability)
    }
}
