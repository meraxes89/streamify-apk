package com.streamify.graphics.governor

import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Phase 2, Track B — TierGovernor state-machine gate
 * (docs/gpu/PHASED_PLAN.md Phase 2; docs/gpu/CI_TESTING_SPEC.md Job 1
 * invariants; docs/gpu/ARCHITECTURE.md section 3, axis 3).
 *
 * Pins every transition of the 4-tier reactive governor:
 *  * Cold start: Tier 2 (Tier 3 on API 26–30 / Vivo 1712, Tier 4 on
 *    software rasterizers).
 *  * Promotion: Tier 1 only after 120 consecutive sub-8.3 ms frames with
 *    NOMINAL thermal status.
 *  * Demotion: synchronous Tier 3 on THERMAL_STATUS_MODERATE-or-worse, and
 *    on the frame where the rolling 60-frame P95 exceeds 16.6 ms.
 *  * Hysteresis: 300-frame cooldown blocks any re-promotion.
 *  * Concurrency: a thermal demotion can never be lost to a promotion.
 *
 * All deterministic tests are single-threaded (the frame clock is
 * single-threaded by contract); the one multi-threaded test asserts a
 * post-join fixed point, so the suite is flake-free by construction.
 */
class TierGovernorTest {

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /** API 34 flagship GPU with a validated AGSL probe — full Tier 1 capable. */
    private fun tier1CapableProbe() = CapabilityProbe(
        apiLevel = 34,
        rendererDescription = "Adreno (TM) 750",
        runtimeShaderCompileSupported = true,
    )

    private fun feedFrames(
        governor: TierGovernor,
        count: Int,
        frameTimeMillis: Double = FAST_FRAME_MS,
    ) {
        repeat(count) { governor.onFrameRendered(frameTimeMillis) }
    }

    // ------------------------------------------------------------------
    // 1. Cold start (docs/gpu/ARCHITECTURE.md §3: "Cold starts at Tier 2")
    // ------------------------------------------------------------------

    @Test
    fun coldStartsAtTier2OnTier1CapableHardware() {
        val governor = TierGovernor(tier1CapableProbe())
        assertEquals(RenderTier.Tier2_Balanced, governor.currentTier)
    }

    @Test
    fun coldStartsAtTier3OnApi26To30Devices() {
        // Vivo 1712 ships Android 8.1 / API 27 and pins the Tier 3 lane.
        assertEquals(
            RenderTier.Tier3_Legacy,
            TierGovernor(CapabilityProbe(apiLevel = 26, rendererDescription = "Mali-G71", runtimeShaderCompileSupported = false)).currentTier,
        )
        assertEquals(
            RenderTier.Tier3_Legacy,
            TierGovernor(CapabilityProbe(apiLevel = 27, rendererDescription = "Adreno (TM) 506", runtimeShaderCompileSupported = false)).currentTier,
        )
        assertEquals(
            RenderTier.Tier3_Legacy,
            TierGovernor(CapabilityProbe(apiLevel = 30, rendererDescription = "Mali-G72", runtimeShaderCompileSupported = false)).currentTier,
        )
    }

    @Test
    fun coldStartsAtTier4OnSoftwareRasterizers() {
        for (renderer in listOf(
            "llvmpipe (LLVM 15.0.7, 256 bits)",
            "Android Emulator OpenGL ES Translator (Google SwiftShader)",
            "Software Renderer",
        )) {
            val probe = CapabilityProbe(
                apiLevel = 34, // software raster dominates even a flagship API level
                rendererDescription = renderer,
                runtimeShaderCompileSupported = true,
            )
            // The raster-kind axis pins not just the cold start but the
            // ceiling itself — an llvmpipe device at API 34 can never
            // promote out of Tier 4.
            assertEquals(RenderTier.Tier4_Safe, probe.hardwareCeiling, "ceiling for renderer=$renderer")
            assertEquals(RenderTier.Tier4_Safe, TierGovernor(probe).currentTier, "renderer=$renderer")
        }
    }

    @Test
    fun belowModuleFloorPinsTier4() {
        // API < 26 cannot even install the module — nothing is trusted.
        val probe = CapabilityProbe(
            apiLevel = 24,
            rendererDescription = "Mali-T880",
            runtimeShaderCompileSupported = false,
        )
        assertEquals(RenderTier.Tier4_Safe, probe.hardwareCeiling)
        assertEquals(RenderTier.Tier4_Safe, TierGovernor(probe).currentTier)
    }

    // ------------------------------------------------------------------
    // 2. Promotion ("120 consecutive frames < 8.3 ms, thermal NOMINAL")
    // ------------------------------------------------------------------

    @Test
    fun promotesToTier1ExactlyAt120ConsecutiveFastFrames() {
        val governor = TierGovernor(tier1CapableProbe())
        feedFrames(governor, 119)
        assertEquals(RenderTier.Tier2_Balanced, governor.currentTier)
        governor.onFrameRendered(FAST_FRAME_MS) // 120th consecutive fast frame
        assertEquals(RenderTier.Tier1_Ultra, governor.currentTier)
    }

    @Test
    fun frameAtExactlyTheFastFrameBudgetIsNotAFastFrame() {
        val governor = TierGovernor(tier1CapableProbe())
        // Strictly less than 8.3 ms counts; exactly 8.3 ms does not.
        feedFrames(governor, 120, frameTimeMillis = TierGovernor.FAST_FRAME_BUDGET_MS)
        assertEquals(RenderTier.Tier2_Balanced, governor.currentTier)
    }

    @Test
    fun aSingleSlowFrameResetsThePromotionCounter() {
        val governor = TierGovernor(tier1CapableProbe())
        feedFrames(governor, 100)
        governor.onFrameRendered(SLOW_FRAME_MS) // streak broken
        feedFrames(governor, 19) // 19 consecutive — still under 120
        assertEquals(RenderTier.Tier2_Balanced, governor.currentTier)
        feedFrames(governor, 101) // 120 consecutive since the slow frame
        assertEquals(RenderTier.Tier1_Ultra, governor.currentTier)
    }

    @Test
    fun promotionRequiresNominalThermalStatus() {
        val governor = TierGovernor(tier1CapableProbe())
        governor.onThermalStatusChanged(ThermalStatus.LIGHT) // warm, below demotion threshold
        feedFrames(governor, 200)
        assertEquals(RenderTier.Tier2_Balanced, governor.currentTier)

        // Promotion resumes on the first frame after the status clears —
        // no cooldown ever engaged because no transition happened.
        governor.onThermalStatusChanged(ThermalStatus.NOMINAL)
        feedFrames(governor, 1)
        assertEquals(RenderTier.Tier1_Ultra, governor.currentTier)
    }

    @Test
    fun api31DeviceNeverPromotesBeyondItsRenderEffectCeiling() {
        val probe = CapabilityProbe(
            apiLevel = 32,
            rendererDescription = "Adreno (TM) 660",
            runtimeShaderCompileSupported = true, // moot below API 33: no AGSL
        )
        val governor = TierGovernor(probe)
        assertEquals(RenderTier.Tier2_Balanced, governor.currentTier)
        feedFrames(governor, 1000)
        assertEquals(RenderTier.Tier2_Balanced, governor.currentTier)
    }

    @Test
    fun softwareRasterizerPinsTier4Forever() {
        val probe = CapabilityProbe(
            apiLevel = 34,
            rendererDescription = "llvmpipe (LLVM 15.0.7, 256 bits)",
            runtimeShaderCompileSupported = true,
        )
        val governor = TierGovernor(probe)
        feedFrames(governor, 1000)
        assertEquals(RenderTier.Tier4_Safe, governor.currentTier)
        governor.onThermalStatusChanged(ThermalStatus.MODERATE) // no-op below Tier 3
        assertEquals(RenderTier.Tier4_Safe, governor.currentTier)
    }

    // ------------------------------------------------------------------
    // 3. Thermal demotion ("immediately to Tier 3 on MODERATE")
    // ------------------------------------------------------------------

    @Test
    fun demotesInstantlyToTier3OnModerateThermalStatus() {
        val governor = TierGovernor(tier1CapableProbe())
        assertEquals(RenderTier.Tier2_Balanced, governor.currentTier)
        governor.onThermalStatusChanged(ThermalStatus.MODERATE)
        // Synchronous demotion — no frame boundary waited for.
        assertEquals(RenderTier.Tier3_Legacy, governor.currentTier)
    }

    @Test
    fun demotesPromotedTier1InstantlyOnModerateThermalStatus() {
        val governor = TierGovernor(tier1CapableProbe())
        feedFrames(governor, 120)
        assertEquals(RenderTier.Tier1_Ultra, governor.currentTier)
        governor.onThermalStatusChanged(ThermalStatus.MODERATE)
        assertEquals(RenderTier.Tier3_Legacy, governor.currentTier)
    }

    @Test
    fun severeAndEmergencyAlsoDemoteToTier3() {
        for (status in listOf(ThermalStatus.SEVERE, ThermalStatus.EMERGENCY)) {
            val governor = TierGovernor(tier1CapableProbe())
            governor.onThermalStatusChanged(status)
            assertEquals(RenderTier.Tier3_Legacy, governor.currentTier, "status=$status")
        }
    }

    @Test
    fun lightThermalStatusNeverDemotes() {
        val governor = TierGovernor(tier1CapableProbe())
        governor.onThermalStatusChanged(ThermalStatus.LIGHT)
        assertEquals(RenderTier.Tier2_Balanced, governor.currentTier)
    }

    // ------------------------------------------------------------------
    // 4. P95 demotion ("P95 > 16.6 ms over the last 60 frames")
    // ------------------------------------------------------------------

    @Test
    fun demotesToTier3WhenP95ExceedsBudgetOver60Frames() {
        val governor = TierGovernor(tier1CapableProbe())
        feedFrames(governor, 59, frameTimeMillis = SLOW_FRAME_MS)
        assertEquals(RenderTier.Tier2_Balanced, governor.currentTier) // window not full yet
        governor.onFrameRendered(SLOW_FRAME_MS) // 60th sample: 60/60 over budget
        assertEquals(RenderTier.Tier3_Legacy, governor.currentTier)
    }

    @Test
    fun briefFrameSpikesAreToleratedByTheP95Window() {
        val governor = TierGovernor(tier1CapableProbe())
        feedFrames(governor, 57)
        feedFrames(governor, 3, frameTimeMillis = SLOW_FRAME_MS) // 3/60 spikes stay under the P95 rank
        assertEquals(RenderTier.Tier2_Balanced, governor.currentTier)
        governor.onFrameRendered(SLOW_FRAME_MS) // 4th spike in the rolling window crosses it
        assertEquals(RenderTier.Tier3_Legacy, governor.currentTier)
    }

    // ------------------------------------------------------------------
    // 5. Hysteresis ("300-frame cooldown before any re-promotion")
    // ------------------------------------------------------------------

    @Test
    fun hysteresisBlocksRePromotionFor300FramesAfterThermalDemotion() {
        val governor = TierGovernor(tier1CapableProbe())
        feedFrames(governor, 120)
        assertEquals(RenderTier.Tier1_Ultra, governor.currentTier)
        governor.onThermalStatusChanged(ThermalStatus.MODERATE)
        assertEquals(RenderTier.Tier3_Legacy, governor.currentTier)
        governor.onThermalStatusChanged(ThermalStatus.NOMINAL) // clear the promotion gate

        // 120 (indeed 150) consecutive fast frames must NOT re-promote while
        // the 300-frame cooldown runs — this is the anti-flicker invariant.
        feedFrames(governor, 150)
        assertEquals(RenderTier.Tier3_Legacy, governor.currentTier)

        feedFrames(governor, 149) // 299 frames since the demotion
        assertEquals(RenderTier.Tier3_Legacy, governor.currentTier)

        governor.onFrameRendered(FAST_FRAME_MS) // 300th frame: cooldown reaches zero
        assertEquals(RenderTier.Tier1_Ultra, governor.currentTier)
    }

    @Test
    fun p95DemotionAlsoArmsTheHysteresisCooldown() {
        val governor = TierGovernor(tier1CapableProbe())
        feedFrames(governor, 60, frameTimeMillis = SLOW_FRAME_MS) // P95 demotion
        assertEquals(RenderTier.Tier3_Legacy, governor.currentTier)
        feedFrames(governor, 299)
        assertEquals(RenderTier.Tier3_Legacy, governor.currentTier)
        governor.onFrameRendered(FAST_FRAME_MS) // 300th frame after demotion
        assertEquals(RenderTier.Tier1_Ultra, governor.currentTier)
    }

    // ------------------------------------------------------------------
    // 6. Transition callback (Koin wiring: onTierChanged -> locator reset)
    // ------------------------------------------------------------------

    @Test
    fun onTierChangedFiresOncePerTransitionButNeverForColdStart() {
        val transitions = mutableListOf<Pair<RenderTier, RenderTier>>()
        val governor = TierGovernor(tier1CapableProbe())
        governor.onTierChanged = { from, to -> transitions += from to to }

        feedFrames(governor, 120) // Tier 2 -> Tier 1
        governor.onThermalStatusChanged(ThermalStatus.MODERATE) // Tier 1 -> Tier 3
        governor.onThermalStatusChanged(ThermalStatus.NOMINAL) // status only — no callback
        feedFrames(governor, 300) // Tier 3 -> Tier 1 (cooldown elapsed)

        assertEquals(
            listOf(
                RenderTier.Tier2_Balanced to RenderTier.Tier1_Ultra,
                RenderTier.Tier1_Ultra to RenderTier.Tier3_Legacy,
                RenderTier.Tier3_Legacy to RenderTier.Tier1_Ultra,
            ),
            transitions,
        )
    }

    // ------------------------------------------------------------------
    // 7. Race-freedom (PR invariant: "no race conditions")
    // ------------------------------------------------------------------

    @Test
    fun concurrentThermalDemotionIsNeverLostToPromotion() {
        val governor = TierGovernor(tier1CapableProbe())

        val thermal = thread {
            repeat(200) {
                governor.onThermalStatusChanged(ThermalStatus.MODERATE)
                governor.onThermalStatusChanged(ThermalStatus.NOMINAL)
            }
            governor.onThermalStatusChanged(ThermalStatus.MODERATE) // terminal report
        }
        val render = thread { feedFrames(governor, 2_000) }

        thermal.join()
        render.join()

        // Post-join fixed point: the last thermal report is MODERATE and
        // demotion is synchronous, while any promotion must CAS against a
        // NOMINAL state — so no in-flight or later promotion can survive
        // the terminal demotion. The settled tier must be exactly Tier 3.
        assertEquals(RenderTier.Tier3_Legacy, governor.currentTier)
    }

    @Test
    fun refreshRateGovernorAdaptsBudgetCorrectly() {
        val probe = tier1CapableProbe()
        val gov60 = TierGovernor.forRefreshRate(probe, 60.0)
        assertEquals(8.3, gov60.fastFrameBudgetMs, 0.05)
        assertEquals(16.66, gov60.p95FrameBudgetMs, 0.05)

        val gov120 = TierGovernor.forRefreshRate(probe, 120.0)
        assertEquals(4.16, gov120.fastFrameBudgetMs, 0.05)
        assertEquals(8.33, gov120.p95FrameBudgetMs, 0.05)
    }

    private companion object {
        /** Comfortably under the 8.3 ms fast-frame budget. */
        private const val FAST_FRAME_MS = 8.0

        /** Comfortably over the 16.6 ms P95 budget. */
        private const val SLOW_FRAME_MS = 20.0
    }
}
