package com.streamify.graphics.engine

import com.streamify.graphics.engine.adapter.ShaderTierLadder
import com.streamify.graphics.governor.CapabilityProbe
import com.streamify.graphics.governor.RenderTier
import com.streamify.graphics.governor.ShaderCapability
import com.streamify.graphics.governor.TierGovernor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Software-Raster Fallback Gate (S24 Track A; docs/gpu/CI_TESTING_SPEC.md
 * Job 4).
 *
 * Runs in a **dedicated JVM fork** (`testSoftwareRasterFallback`) with
 * `LIBGL_ALWAYS_SOFTWARE=1` forcing Mesa onto a CPU rasterizer — the exact
 * headless-CI / Linux-VM / WSL2 configuration that produces the black-hole
 * failure mode: SkSL compiles fine on the CPU (`makeForShader()` passes)
 * while actually rendering the refraction/blur shaders would pin a core at
 * 100%. Because the fork is dedicated, [ShaderProvider.probe]'s
 * process-lazy classification resolves *under* the forced environment —
 * the gate pins the full clamp chain end-to-end:
 *
 *  1. The live renderer-description seam reports a software marker string.
 *  2. The probe clamps to [ShaderCapability.FIXED_FUNCTION] with a
 *     [RenderTier.Tier4_Safe] ceiling (axis 2 dominates axis 1 — even with
 *     a successful CPU compile).
 *  3. The governor cold-starts — and stays — at Tier 4, so no runtime
 *     shader is ever dispatched to the software rasterizer.
 *  4. The Tier 4 lane is the flat solid-tint engine with a zero
 *     shader-program execution plan.
 */
class SoftwareRasterFallbackGateTest {

    @Test
    fun forcedSoftwareEnvironmentClampsTheLiveProbeToTier4Safe() {
        // 1. The seam sees the forced environment.
        val description = liveRendererDescription()
        assertTrue(
            description.isNotEmpty(),
            "LIBGL_ALWAYS_SOFTWARE=1 must surface a software marker in the live renderer description",
        )
        val probeFromString = CapabilityProbe(
            apiLevel = 0,
            rendererDescription = description,
            runtimeShaderCompileSupported = true,
        )
        assertTrue(probeFromString.isSoftwareRasterizer, "description=\"$description\"")

        // 2. The full platform probe — the same lazy instance the engine
        //    resolves in production — clamps before any frame renders.
        val probe = ShaderProvider.probe()
        assertTrue(
            probe.isSoftwareRasterizer,
            "the live probe must classify the forced software rasterizer",
        )
        assertEquals(ShaderCapability.FIXED_FUNCTION, probe.shaderCapability)
        assertEquals(RenderTier.Tier4_Safe, probe.hardwareCeiling)

        // 3. The governor pins Tier 4 forever — no promotion can escape the
        //    raster-kind ceiling, even with 1000 fast CPU-rasterized frames.
        val governor = TierGovernor(probe)
        assertEquals(RenderTier.Tier4_Safe, governor.currentTier)
        repeat(1000) { governor.onFrameRendered(1.0) }
        assertEquals(RenderTier.Tier4_Safe, governor.currentTier)

        // 4. Zero shader programs dispatch on the Tier 4 lane: the flat
        //    solid tint engine serves every draw.
        assertEquals(LegacyRedirectGlassEngine, ShaderProvider.engineFor(governor.currentTier))
        assertEquals(
            0,
            ShaderTierLadder.planFor(governor.currentTier, probe.shaderCapability).shaderProgramCount,
        )
        assertNotNull(ShaderProvider.engineFor(RenderTier.Tier4_Safe))
    }
}
