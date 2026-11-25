package com.streamify.graphics.di

import androidx.compose.ui.graphics.drawscope.DrawScope
import com.streamify.graphics.api.GlassRenderRequest
import com.streamify.graphics.api.SovereignGraphicsEngine
import com.streamify.graphics.api.SovereignGraphicsEngineLocator
import com.streamify.graphics.engine.ShaderProvider
import com.streamify.graphics.governor.CapabilityProbe
import com.streamify.graphics.governor.RenderTier
import com.streamify.graphics.governor.TierGovernor
import com.streamify.graphics.governor.platformDisplayRefreshRateHz
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Koin wiring for the Sovereign GPU Engine (docs/gpu/PHASED_PLAN.md,
 * Phase 2: "Wire into Koin").
 *
 * Phase 2 binds the [SovereignGraphicsEngine] port to the tier-governed
 * engine: a [TierGovernor] owns the live render tier, per-tier engine
 * selection delegates to the platform adapter ([ShaderProvider.engineFor] —
 * the Phase 2-A Track A seam), and every tier transition resets the
 * draw-phase engine cache ([SovereignGraphicsEngineLocator.reset]) so the
 * next frame re-resolves against the new tier.
 *
 * **Capability signals are real as of Phase 2-A**: the [CapabilityProbe]
 * binding resolves through [ShaderProvider.probe], whose platform actuals
 * feed `Build.VERSION.SDK_INT` plus a live minimal AGSL compile probe on
 * Android and Skia `RuntimeEffect` validation on the desktop / wasmJs
 * targets. The probe contract stays pure — classification only, no
 * platform state escapes the adapter.
 *
 * Registered through the suite's `loadKoinModules(...)` mechanism
 * (streamify-modules/README.md): this module defines only new
 * `com.streamify.graphics.*` types and never redefines an upstream bean.
 */
val streamifyGraphicsModule: Module = module {

    // The platform adapter (Phase 2-A, Track A) is the signal source: real
    // SDK_INT / renderer / compile-probe classification, cached per process
    // inside the actual. The governor consumes the same instance.
    single { ShaderProvider.probe() }

    single {
        // S24 Track B: the governor budgets against the platform's real
        // display cadence (UIScreen.maximumFramesPerSecond on iOS, the AWT
        // display mode on desktop) instead of a static 16.6 ms assumption —
        // a 120 Hz ProMotion link budgets 4.16 ms fast / 8.33 ms P95.
        TierGovernor.forRefreshRate(
            probe = get(),
            refreshRateHz = platformDisplayRefreshRateHz(),
        ).apply {
            // Every tier transition invalidates the facade's cached engine
            // binding so the next draw pass re-resolves through Koin under
            // the new tier. The callback fires on whichever thread performed
            // the transition (frame thread or thermal listener thread);
            // SovereignGraphicsEngineLocator.reset() is a volatile null-out
            // and therefore safe from any thread.
            onTierChanged = { _, _ -> SovereignGraphicsEngineLocator.reset() }

            // S24 Track A: the platform environment bridge attaches the
            // native thermal / Low Power Mode observers (Apple lane) —
            // synchronous demotions land straight in this governor.
            GraphicsEnvironmentBridge.attach(this)
        }
    }

    single<SovereignGraphicsEngine> { TierGovernedGlassEngine(governor = get()) }
}

/**
 * [SovereignGraphicsEngine] adapter that delegates each draw to the engine
 * selected for the governor's **live** tier (read per draw pass, so tier
 * switches take effect on the very next frame without recomposition).
 *
 * Per-tier selection is the platform adapter's: [ShaderProvider.engineFor]
 * maps every tier through the common `ShaderTierLadder` onto the real
 * Phase 2-A providers — AGSL `RuntimeShader` / `RenderEffect` blur on
 * Android, Skia `RuntimeShaderBuilder` on the skiko targets (with the S24
 * Track C dynamic-resolution pass on Retina-density Tier 2 lanes), cached
 * acrylic scrim for the legacy lane and the Tier 4 failover. The default
 * stays overridable in the Koin binding for previews and tests.
 */
private class TierGovernedGlassEngine(
    private val governor: TierGovernor,
    private val engineForTier: (RenderTier) -> SovereignGraphicsEngine = { tier ->
        ShaderProvider.engineFor(tier)
    },
) : SovereignGraphicsEngine {

    override fun applyGlass(scope: DrawScope, request: GlassRenderRequest) {
        engineForTier(governor.currentTier).applyGlass(scope, request)
    }
}
