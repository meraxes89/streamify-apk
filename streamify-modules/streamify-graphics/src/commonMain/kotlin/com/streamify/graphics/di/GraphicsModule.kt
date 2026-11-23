package com.streamify.graphics.di

import androidx.compose.ui.graphics.drawscope.DrawScope
import com.streamify.graphics.api.GlassRenderRequest
import com.streamify.graphics.api.SovereignGraphicsEngine
import com.streamify.graphics.api.SovereignGraphicsEngineLocator
import com.streamify.graphics.engine.LegacyRedirectGlassEngine
import com.streamify.graphics.governor.CapabilityProbe
import com.streamify.graphics.governor.RenderTier
import com.streamify.graphics.governor.TierGovernor
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Koin wiring for the Sovereign GPU Engine (docs/gpu/PHASED_PLAN.md,
 * Phase 2-B: "Wire into Koin").
 *
 * Phase 2 binds the [SovereignGraphicsEngine] port to the tier-governed
 * engine: a [TierGovernor] fed by a [CapabilityProbe] owns the live render
 * tier, the engine delegates per tier, and every tier transition resets the
 * draw-phase engine cache ([SovereignGraphicsEngineLocator.reset]) so the
 * next frame re-resolves against the new tier.
 *
 * **Bootstrap probe** — no platform signal source (Android SDK_INT /
 * GL_RENDERER / AGSL compile probe; the Track A platform adapters of Phase
 * 2-A) has landed yet, so the probe is constructed with the maximally
 * conservative contract: unknown API level, unknown renderer, unvalidated
 * shader support. Per [CapabilityProbe]'s conservative-by-contract rule the
 * resulting ceiling is Tier 3, which renders exactly like the merged Phase
 * 1 state — a zero-regression stopgap. Platform adapters replace this
 * binding's arguments with real signals without touching anything else.
 *
 * Registered through the suite's `loadKoinModules(...)` mechanism
 * (streamify-modules/README.md): this module defines only new
 * `com.streamify.graphics.*` types and never redefines an upstream bean.
 */
val streamifyGraphicsModule: Module = module {

    single {
        TierGovernor(
            probe = CapabilityProbe(
                // Platform adapters (Phase 2-A, Track A) feed the real
                // Build.VERSION.SDK_INT, GL_RENDERER string and AGSL/Skia
                // compile-probe result here. Until then: unknown/unvalidated.
                apiLevel = 0,
                rendererDescription = "",
                runtimeShaderCompileSupported = false,
            ),
        ).apply {
            // Every tier transition invalidates the facade's cached engine
            // binding so the next draw pass re-resolves through Koin under
            // the new tier. The callback fires on whichever thread performed
            // the transition (frame thread or thermal listener thread);
            // SovereignGraphicsEngineLocator.reset() is a volatile null-out
            // and therefore safe from any thread.
            onTierChanged = { _, _ -> SovereignGraphicsEngineLocator.reset() }
        }
    }

    single<SovereignGraphicsEngine> { TierGovernedGlassEngine(governor = get()) }
}

/**
 * [SovereignGraphicsEngine] adapter that delegates each draw to the engine
 * selected for the governor's **live** tier (read per draw pass, so tier
 * switches take effect on the very next frame without recomposition).
 *
 * **Phase 2-B stopgap**: the per-tier platform adapters (AGSL
 * `RuntimeShader` / `RenderEffect` / Skia `RuntimeShaderBuilder`) are the
 * Phase 2-A Track A deliverables and have not landed yet, so every tier
 * currently delegates to [LegacyRedirectGlassEngine] — render-identical to
 * the merged Phase 1 behavior. The [engineForTier] seam (overridable in the
 * Koin binding) is exactly where Track A's per-tier providers register;
 * the port, facade and governor contracts do not change when they do.
 */
private class TierGovernedGlassEngine(
    private val governor: TierGovernor,
    private val engineForTier: (RenderTier) -> SovereignGraphicsEngine = {
        LegacyRedirectGlassEngine
    },
) : SovereignGraphicsEngine {

    override fun applyGlass(scope: DrawScope, request: GlassRenderRequest) {
        engineForTier(governor.currentTier).applyGlass(scope, request)
    }
}
