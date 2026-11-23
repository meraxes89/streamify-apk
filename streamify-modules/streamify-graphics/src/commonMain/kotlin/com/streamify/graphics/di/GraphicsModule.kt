package com.streamify.graphics.di

import com.streamify.graphics.api.SovereignGraphicsEngine
import com.streamify.graphics.engine.LegacyRedirectGlassEngine
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Koin wiring for the Sovereign GPU Engine (docs/gpu/PHASED_PLAN.md,
 * Phase 1-B: "configure Koin module").
 *
 * Phase 1 binds the [SovereignGraphicsEngine] port to the legacy-redirect
 * stub so the wiring is exercised end-to-end the moment any composable
 * adopts `Modifier.streamifyGlass(...)`. Phase 2 swaps this binding for the
 * tier-governed platform adapter (CapabilityProbe + TierGovernor selecting
 * AGSL / RenderEffect / Skia RuntimeShaderBuilder implementations) — the
 * port and facade signatures do not change.
 *
 * Registered through the suite's `loadKoinModules(...)` mechanism
 * (streamify-modules/README.md): this module defines only new
 * `com.streamify.graphics.*` types and never redefines an upstream bean.
 */
val streamifyGraphicsModule: Module = module {
    single<SovereignGraphicsEngine> { LegacyRedirectGlassEngine }
}
