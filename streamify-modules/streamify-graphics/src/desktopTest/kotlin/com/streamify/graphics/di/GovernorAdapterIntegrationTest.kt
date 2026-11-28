package com.streamify.graphics.di

import com.streamify.graphics.api.SovereignGraphicsEngine
import com.streamify.graphics.api.SovereignGraphicsEngineLocator
import com.streamify.graphics.engine.AcrylicScrimGlassEngine
import com.streamify.graphics.engine.ShaderProvider
import com.streamify.graphics.engine.SkikoRuntimeShaderEngine
import com.streamify.graphics.governor.CapabilityProbe
import com.streamify.graphics.governor.RenderTier
import com.streamify.graphics.governor.ThermalStatus
import com.streamify.graphics.governor.TierGovernor
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.mp.KoinPlatform

/**
 * Governor <-> adapter integration gate (Phase 2-A + 2-B wired together).
 *
 * Boots the real `streamifyGraphicsModule` Koin graph on the desktop JVM
 * lane and drives the full Phase 2 stack end-to-end, without a draw
 * surface:
 *
 *  * the probe binding resolves through the platform adapter's REAL
 *    signals (a live Skia compile probe on this host — the Phase 2-B
 *    conservative stopgap is retired),
 *  * the governor cold-starts at Tier 2 under the Tier 1 ceiling,
 *  * promotion after 120 consecutive fast frames flips the provider's
 *    Tier 1 lane to the Skia runtime engine,
 *  * a synchronous thermal demotion flips the Tier 3 lane to the scrim
 *    engine and resets the facade's cached engine binding (the
 *    onTierChanged -> SovereignGraphicsEngineLocator.reset() wiring),
 *  * the Koin graph stays resolvable through every transition.
 */
class GovernorAdapterIntegrationTest {

    @BeforeTest
    fun setUp() {
        startKoin { modules(streamifyGraphicsModule) }
    }

    @AfterTest
    fun tearDown() {
        stopKoin()
    }

    @Test
    fun koinGraphResolvesEngineGovernorAndRealProbeSignals() {
        val koin = KoinPlatform.getKoin()

        // The probe binding is the platform adapter's classification — on a
        // healthy skiko host that means a REAL validated compile.
        val probe = koin.get<CapabilityProbe>()
        assertTrue(
            probe.runtimeShaderCompileSupported,
            "the probe binding must carry the adapter's real compile-probe signal",
        )
        assertEquals(RenderTier.Tier1_Ultra, probe.hardwareCeiling)

        // Cold start: Tier 2 (conservative) under the Tier 1 ceiling.
        val governor = koin.get<TierGovernor>()
        assertEquals(RenderTier.Tier2_Balanced, governor.currentTier)

        // The engine port resolves to the tier-governed engine and the
        // facade locator serves the same Koin singleton.
        val engine = koin.get<SovereignGraphicsEngine>()
        assertSame(engine, SovereignGraphicsEngineLocator.engine())
    }

    @Test
    fun promotionDrivesTheProviderTier1Engine() {
        val governor = KoinPlatform.getKoin().get<TierGovernor>()

        repeat(TierGovernor.PROMOTION_FRAME_COUNT) {
            governor.onFrameRendered(FAST_FRAME_MS)
        }
        assertEquals(RenderTier.Tier1_Ultra, governor.currentTier)
        assertTrue(
            ShaderProvider.engineFor(governor.currentTier) is SkikoRuntimeShaderEngine,
            "the promoted Tier 1 lane must be the Skia runtime shader engine",
        )
    }

    @Test
    fun thermalDemotionDrivesTheScrimEngineAndResetsTheFacadeCache() {
        val koin = KoinPlatform.getKoin()
        val governor = koin.get<TierGovernor>()

        // Populate the facade's cached engine binding.
        SovereignGraphicsEngineLocator.engine()

        // Synchronous thermal demotion — Tier 3, from any thread.
        governor.onThermalStatusChanged(ThermalStatus.MODERATE)
        assertEquals(RenderTier.Tier3_Legacy, governor.currentTier)
        assertEquals(
            AcrylicScrimGlassEngine,
            ShaderProvider.engineFor(governor.currentTier),
            "the demoted Tier 3 lane must be the acrylic scrim engine",
        )

        // onTierChanged -> SovereignGraphicsEngineLocator.reset() fired on
        // the demoting thread; the next draw re-resolves through Koin and
        // the graph is still healthy.
        assertSame(koin.get<SovereignGraphicsEngine>(), SovereignGraphicsEngineLocator.engine())
    }

    private companion object {
        /** Comfortably under the 8.3 ms fast-frame budget. */
        private const val FAST_FRAME_MS = 4.0
    }
}
