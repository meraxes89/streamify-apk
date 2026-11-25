package com.streamify.graphics.engine.adapter

import com.streamify.graphics.governor.CapabilityProbe
import com.streamify.graphics.governor.RenderTier
import com.streamify.graphics.governor.ShaderCapability
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Universal Android GPU & version spectrum matrix gate (Tracks A–D,
 * docs/gpu/ARCHITECTURE.md section 3).
 *
 * Pins the four deliverables of the universal matrix work:
 *
 *  * **Track A** — the canonical shader keeps the precision contract
 *    (highp geometry / mediump color path) and the transpiler lint
 *    rejects a regressing source.
 *  * **Track B** — a probe that fails to compile the canonical source
 *    carries the recorded failure reason and degrades the capability —
 *    never throws.
 *  * **Track C** — the API 26 -> 35+ spectrum maps to the right lanes:
 *    API 33+ AGSL Tier 1, API 31–32 blur Tier 2, API 26–30 scrim
 *    Tier 3, with the API 27 hard invariant (zero shader programs).
 *  * **Track D** — a denylist marker demotes to Tier 4 while a compliant
 *    GPU with the same marketing family is never penalized.
 */
class UniversalAndroidMatrixTest {

    // ------------------------------------------------------------------
    // Track A — precision contract
    // ------------------------------------------------------------------

    @Test
    fun canonicalShaderKeepsThePrecisionContract() {
        val source = LiquidGlassSource.SKSL
        // The explicit gate passes on the canonical source.
        AgslDialect.validatePrecisionContract(source)
        // Highp geometry: the normal chain stays float-typed end to end.
        assertTrue("float2 uv = coord / resolution;" in source)
        assertTrue("float3 n  = float3(0.0, 0.0, 1.0);" in source)
        // Mediump color path: the backdrop taps build half types.
        assertTrue("half3 c = half3(" in source)
        assertTrue("half a = backdrop.eval(coord + off).a;" in source)
        // The single highp -> mediump boundary: the Fresnel declaration.
        assertTrue("half fr = half(" in source)
    }

    @Test
    fun precisionContractSurvivesTranspilation() {
        // The transpiled AGSL text must satisfy the same contract (the
        // promotion of `uniform half4` is the one documented delta).
        val transpiled = AgslDialect.transpile(LiquidGlassSource.SKSL)
        AgslDialect.validatePrecisionContract(transpiled)
        assertTrue("half3 c = half3(" in transpiled)
        assertTrue("half fr = half(" in transpiled)
    }

    @Test
    fun precisionContractRegressionIsRejected() {
        // A source that drops the mediump boundary (float-typed Fresnel)
        // must fail the contract gate with the named regression.
        val regressed = LiquidGlassSource.SKSL
            .replace("half fr = half(", "float fr = float(")
        assertFailsWith<IllegalArgumentException> {
            AgslDialect.validatePrecisionContract(regressed)
        }
    }

    @Test
    fun floatTapRegressionIsRejected() {
        // A source that re-promotes the backdrop taps to float must fail.
        val regressed = LiquidGlassSource.SKSL
            .replace("half3 c = half3(", "float3 c = float3(")
        assertFailsWith<IllegalArgumentException> {
            AgslDialect.validatePrecisionContract(regressed)
        }
    }

    // ------------------------------------------------------------------
    // Track B — probe failure is recorded, never thrown
    // ------------------------------------------------------------------

    @Test
    fun failedCanonicalCompileProbeRecordsReasonAndDegrades() {
        val probe = CapabilityProbe(
            apiLevel = 34,
            rendererDescription = "Adreno (TM) 750",
            runtimeShaderCompileSupported = false,
            compileProbeFailureReason = "IllegalArgumentException: program: bad syntax",
        )
        // The failure reason is carried, not thrown.
        assertEquals("IllegalArgumentException: program: bad syntax", probe.compileProbeFailureReason)
        // And the lane degrades: API 34 + failed AGSL -> blur lane, Tier 2.
        assertEquals(ShaderCapability.RENDER_EFFECT, probe.shaderCapability)
        assertEquals(RenderTier.Tier2_Balanced, probe.hardwareCeiling)
    }

    @Test
    fun successfulProbeCarriesNoFailureReason() {
        val probe = CapabilityProbe(
            apiLevel = 35,
            rendererDescription = "Adreno (TM) 830",
            runtimeShaderCompileSupported = true,
        )
        assertNull(probe.compileProbeFailureReason)
        assertEquals(ShaderCapability.RUNTIME_SHADER, probe.shaderCapability)
        assertEquals(RenderTier.Tier1_Ultra, probe.hardwareCeiling)
    }

    // ------------------------------------------------------------------
    // Track C — the API 26 -> 35+ spectrum
    // ------------------------------------------------------------------

    @Test
    fun apiSpectrumMapsToTheDocumentedLanes() {
        // API 33-35+ (Android 13-15/16): full AGSL, Tier 1 ceiling.
        for (api in 33..35) {
            val plan = ShaderTierLadder.bestPlanFor(api, "Adreno (TM) 750", runtimeShaderCompileSupported = true)
            assertEquals(RenderTier.Tier1_Ultra, plan.tier, "API $api")
            assertEquals(GlassRenderStrategy.RUNTIME_SHADER_DISPERSION, plan.strategy, "API $api")
            assertEquals(1, plan.shaderProgramCount, "API $api")
        }

        // API 31-32 (Android 12/12L): hardware blur, Tier 2 ceiling, zero programs.
        for (api in 31..32) {
            val plan = ShaderTierLadder.bestPlanFor(api, "Adreno (TM) 660")
            assertEquals(RenderTier.Tier2_Balanced, plan.tier, "API $api")
            assertEquals(GlassRenderStrategy.BLUR_RENDER_EFFECT, plan.strategy, "API $api")
            assertEquals(0, plan.shaderProgramCount, "API $api")
        }

        // API 28-30 (Android 9-11): RenderNode clipping lane, Tier 3, zero programs.
        for (api in 28..30) {
            val plan = ShaderTierLadder.bestPlanFor(api, "Mali-G72")
            assertEquals(RenderTier.Tier3_Legacy, plan.tier, "API $api")
            assertEquals(GlassRenderStrategy.ACRYLIC_SCRIM, plan.strategy, "API $api")
            assertEquals(0, plan.shaderProgramCount, "API $api")
        }

        // API 26-27 (Android 8.x, Vivo 1712): the zero-shader hard lane.
        for (api in 26..27) {
            val plan = ShaderTierLadder.bestPlanFor(api, "Adreno (TM) 509")
            assertEquals(RenderTier.Tier3_Legacy, plan.tier, "API $api")
            assertEquals(GlassRenderStrategy.ACRYLIC_SCRIM, plan.strategy, "API $api")
            assertEquals(0, plan.shaderProgramCount, "API $api")
        }
    }

    // ------------------------------------------------------------------
    // Track D — the denylist demotes, never penalizes
    // ------------------------------------------------------------------

    @Test
    fun denylistMarkerDemotesToTier4Safe() {
        // The emulator software-GLES renderer with the broken half division.
        val probe = CapabilityProbe(
            apiLevel = 34,
            rendererDescription = "SwiftShader (Software) 5.0",
            runtimeShaderCompileSupported = true,
        )
        assertEquals("emu-gles-swiftshader-fragmentdivision", probe.matchedDriverQuirk?.id)
        assertEquals(ShaderCapability.FIXED_FUNCTION, probe.shaderCapability)
        assertEquals(RenderTier.Tier4_Safe, probe.hardwareCeiling)
    }

    @Test
    fun denylistOnlyDemotesNeverPenalizesCompliantGpus() {
        // A compliant GPU: same vendors, no quirk markers, full capability.
        for (renderer in listOf(
            "Adreno (TM) 509",
            "Adreno (TM) 750",
            "Mali-G72 MP12",
            "Mali-G715-Immortalis MC11",
            "Xclipse 940",
            "PowerVR Rogue GE8320",
            "PowerVR BXM-8-256",
            "Vivo 1712 GPU",
        )) {
            val probe = CapabilityProbe(
                apiLevel = 34,
                rendererDescription = renderer,
                runtimeShaderCompileSupported = true,
            )
            assertNull(probe.matchedDriverQuirk, renderer)
            assertEquals(ShaderCapability.RUNTIME_SHADER, probe.shaderCapability, renderer)
            assertEquals(RenderTier.Tier1_Ultra, probe.hardwareCeiling, renderer)
        }
    }

    @Test
    fun denylistEntryCarriesIdAndDefectForDiagnostics() {
        val probe = CapabilityProbe(
            apiLevel = 33,
            rendererDescription = "ANGLE (Android Emulator, Vulkan)",
            runtimeShaderCompileSupported = true,
        )
        val quirk = probe.matchedDriverQuirk
        assertTrue(quirk != null)
        assertTrue(quirk.id.isNotBlank())
        assertTrue(quirk.defect.isNotBlank())
        assertEquals(RenderTier.Tier4_Safe, probe.hardwareCeiling)
    }
}
