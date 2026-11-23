package com.streamify.graphics.engine.adapter

import com.streamify.graphics.governor.RenderTier
import com.streamify.graphics.governor.ShaderCapability
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pure-JVM tier-ladder matrix (docs/gpu/CI_TESTING_SPEC.md section 2,
 * Job 2's decision logic — the Robolectric lane pins the class-loading
 * half later).
 *
 * The hard API 27 (Vivo 1712 / Android 8.1) invariant is asserted HERE
 * because the Android actual's lane selection funnels exclusively through
 * this common function: sdkInt 27 can only ever select the Tier 3 scrim
 * lane, and the scrim engine references zero platform shader classes —
 * nothing for an API 27 process to fail linking.
 */
class ShaderTierLadderTest {

    @Test
    fun api27VivoLaneIsTier3WithZeroShaderPrograms() {
        val plan = ShaderTierLadder.bestPlanFor(apiLevel = 27)
        assertEquals(RenderTier.Tier3_Legacy, plan.tier)
        assertEquals(GlassRenderStrategy.ACRYLIC_SCRIM, plan.strategy)
        assertEquals(0, plan.shaderProgramCount)
    }

    @Test
    fun api26Through30AllPinTheLegacyScrimLane() {
        for (api in 26..30) {
            val plan = ShaderTierLadder.bestPlanFor(apiLevel = api)
            assertEquals(RenderTier.Tier3_Legacy, plan.tier, "API $api")
            assertEquals(GlassRenderStrategy.ACRYLIC_SCRIM, plan.strategy, "API $api")
            assertEquals(0, plan.shaderProgramCount, "API $api")
        }
    }

    @Test
    fun api33WithValidatedShaderEarnsTier1DispersionWithOneProgram() {
        val plan = ShaderTierLadder.bestPlanFor(
            apiLevel = 33,
            runtimeShaderCompileSupported = true,
        )
        assertEquals(RenderTier.Tier1_Ultra, plan.tier)
        assertEquals(GlassRenderStrategy.RUNTIME_SHADER_DISPERSION, plan.strategy)
        assertEquals(1, plan.shaderProgramCount)
    }

    @Test
    fun api33WithFailedAgslProbeDropsToTheBlurLane() {
        // The conservative-by-contract probe: an unvalidated AGSL compile
        // still leaves RenderEffect available on API 33 — Tier 2, zero
        // runtime shader programs.
        val plan = ShaderTierLadder.bestPlanFor(
            apiLevel = 33,
            runtimeShaderCompileSupported = false,
        )
        assertEquals(RenderTier.Tier2_Balanced, plan.tier)
        assertEquals(GlassRenderStrategy.BLUR_RENDER_EFFECT, plan.strategy)
        assertEquals(0, plan.shaderProgramCount)
    }

    @Test
    fun api31And32RunTheBlurRenderEffectLane() {
        for (api in 31..32) {
            val plan = ShaderTierLadder.bestPlanFor(apiLevel = api)
            assertEquals(RenderTier.Tier2_Balanced, plan.tier, "API $api")
            assertEquals(GlassRenderStrategy.BLUR_RENDER_EFFECT, plan.strategy, "API $api")
            assertEquals(0, plan.shaderProgramCount, "API $api")
        }
    }

    @Test
    fun softwareRasterizerDominatesEvenAtApi34WithValidatedShader() {
        // Axis 2 dominates axis 1 (ARCHITECTURE.md section 3): an llvmpipe
        // device at API 34 with a compiling shader is still Tier 4.
        val plan = ShaderTierLadder.bestPlanFor(
            apiLevel = 34,
            rendererDescription = "llvmpipe (LLVM 15.0.7, 256 bits)",
            runtimeShaderCompileSupported = true,
        )
        assertEquals(RenderTier.Tier4_Safe, plan.tier)
        assertEquals(GlassRenderStrategy.SOLID_TINT, plan.strategy)
        assertEquals(0, plan.shaderProgramCount)
    }

    @Test
    fun belowMinSdkFloorIsMaximallySafe() {
        val plan = ShaderTierLadder.bestPlanFor(apiLevel = 25)
        assertEquals(RenderTier.Tier4_Safe, plan.tier)
        assertEquals(GlassRenderStrategy.SOLID_TINT, plan.strategy)
        assertEquals(0, plan.shaderProgramCount)
    }

    /**
     * The full 12-cell matrix, pinned literally so any future ladder edit
     * is a deliberate, review-visible change to this table.
     */
    @Test
    fun fullTierCapabilityMatrixIsPinned() {
        val expected = listOf(
            // Tier 4 — flat failover regardless of capability
            (RenderTier.Tier4_Safe to ShaderCapability.RUNTIME_SHADER) to
                (GlassRenderStrategy.SOLID_TINT to 0),
            (RenderTier.Tier4_Safe to ShaderCapability.RENDER_EFFECT) to
                (GlassRenderStrategy.SOLID_TINT to 0),
            (RenderTier.Tier4_Safe to ShaderCapability.FIXED_FUNCTION) to
                (GlassRenderStrategy.SOLID_TINT to 0),
            // Tier 3 — scrim regardless of capability
            (RenderTier.Tier3_Legacy to ShaderCapability.RUNTIME_SHADER) to
                (GlassRenderStrategy.ACRYLIC_SCRIM to 0),
            (RenderTier.Tier3_Legacy to ShaderCapability.RENDER_EFFECT) to
                (GlassRenderStrategy.ACRYLIC_SCRIM to 0),
            (RenderTier.Tier3_Legacy to ShaderCapability.FIXED_FUNCTION) to
                (GlassRenderStrategy.ACRYLIC_SCRIM to 0),
            // Tier 2 — single-tap on shader-capable, blur on 31-32, scrim below
            (RenderTier.Tier2_Balanced to ShaderCapability.RUNTIME_SHADER) to
                (GlassRenderStrategy.RUNTIME_SHADER_SINGLE_TAP to 1),
            (RenderTier.Tier2_Balanced to ShaderCapability.RENDER_EFFECT) to
                (GlassRenderStrategy.BLUR_RENDER_EFFECT to 0),
            (RenderTier.Tier2_Balanced to ShaderCapability.FIXED_FUNCTION) to
                (GlassRenderStrategy.ACRYLIC_SCRIM to 0),
            // Tier 1 — dispersion on shader-capable, defensive degrades below
            (RenderTier.Tier1_Ultra to ShaderCapability.RUNTIME_SHADER) to
                (GlassRenderStrategy.RUNTIME_SHADER_DISPERSION to 1),
            (RenderTier.Tier1_Ultra to ShaderCapability.RENDER_EFFECT) to
                (GlassRenderStrategy.BLUR_RENDER_EFFECT to 0),
            (RenderTier.Tier1_Ultra to ShaderCapability.FIXED_FUNCTION) to
                (GlassRenderStrategy.ACRYLIC_SCRIM to 0),
        )
        for ((input, output) in expected) {
            val (tier, capability) = input
            val plan = ShaderTierLadder.planFor(tier, capability)
            assertEquals(output.first, plan.strategy, "$tier x $capability")
            assertEquals(output.second, plan.shaderProgramCount, "$tier x $capability")
            assertEquals(tier, plan.tier, "$tier x $capability")
        }
    }

    @Test
    fun onlyRuntimeShaderStrategiesAllocatePrograms() {
        for (tier in RenderTier.entries) {
            for (capability in ShaderCapability.entries) {
                val plan = ShaderTierLadder.planFor(tier, capability)
                val runtimeShaderLane =
                    plan.strategy == GlassRenderStrategy.RUNTIME_SHADER_DISPERSION ||
                        plan.strategy == GlassRenderStrategy.RUNTIME_SHADER_SINGLE_TAP
                assertEquals(
                    if (runtimeShaderLane) 1 else 0,
                    plan.shaderProgramCount,
                    "$tier x $capability",
                )
            }
        }
    }
}
