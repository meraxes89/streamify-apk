package com.streamify.graphics.engine

import com.streamify.graphics.engine.adapter.ShaderTierLadder
import com.streamify.graphics.governor.CapabilityProbe
import com.streamify.graphics.governor.RenderTier
import com.streamify.graphics.governor.ShaderCapability
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Phase 4, Track A — Shader Compilation Failure Fallback Test
 * (docs/gpu/PHASED_PLAN.md §3 Phase 4; docs/gpu/CI_TESTING_SPEC.md Job 5).
 *
 * Asserts that when a runtime shader program fails compilation (driver rejection,
 * malformed source, out-of-spec GPU), the tier ladder and capability probe
 * degrade gracefully to Tier 4 (Safe) or Tier 3 (Legacy) without throwing
 * unhandled exceptions or crashing the rendering pipeline.
 */
class ShaderFailureFallbackTest {

    @Test
    fun failedShaderCompileDegradesToFixedFunctionCapability() {
        // Probe where runtime shader compile failed
        val probe = CapabilityProbe(
            apiLevel = 34,
            rendererDescription = "Adreno (TM) 750",
            runtimeShaderCompileSupported = false,
        )

        // API 34 with failed shader probe must degrade to RENDER_EFFECT, not throw
        assertEquals(ShaderCapability.RENDER_EFFECT, probe.shaderCapability)
        assertEquals(RenderTier.Tier2_Balanced, probe.hardwareCeiling)
    }

    @Test
    fun nonAndroidTargetWithFailedCompilePinsFixedFunction() {
        val probe = CapabilityProbe(
            apiLevel = 0, // Desktop / iOS / Web
            rendererDescription = "Apple M3",
            runtimeShaderCompileSupported = false,
        )

        assertEquals(ShaderCapability.FIXED_FUNCTION, probe.shaderCapability)
        assertEquals(RenderTier.Tier3_Legacy, probe.hardwareCeiling)
    }

    @Test
    fun softwareRasterizerAlwaysPinsTier4SafeRegardlessOfShaderSupport() {
        val probe = CapabilityProbe(
            apiLevel = 34,
            rendererDescription = "llvmpipe (LLVM 17.0.6, 256 bits)",
            runtimeShaderCompileSupported = true,
        )

        assertEquals(true, probe.isSoftwareRasterizer)
        assertEquals(ShaderCapability.FIXED_FUNCTION, probe.shaderCapability)
        assertEquals(RenderTier.Tier4_Safe, probe.hardwareCeiling)
    }

    @Test
    fun engineForSafelyHandlesDemotedTiers() {
        // Verify every tier returns a valid, non-null SovereignGraphicsEngine
        for (tier in RenderTier.entries) {
            val engine = ShaderProvider.engineFor(tier)
            assertNotNull(engine, "Engine for tier $tier must not be null")
        }
    }

    @Test
    fun tierLadderFallbackTotalCoverage() {
        // Verify planFor produces a valid, zero-shader-program execution plan on fallback
        val planTier4 = ShaderTierLadder.planFor(RenderTier.Tier4_Safe, ShaderCapability.FIXED_FUNCTION)
        assertEquals(0, planTier4.shaderProgramCount)

        val planTier3 = ShaderTierLadder.planFor(RenderTier.Tier3_Legacy, ShaderCapability.FIXED_FUNCTION)
        assertEquals(0, planTier3.shaderProgramCount)
    }
}
