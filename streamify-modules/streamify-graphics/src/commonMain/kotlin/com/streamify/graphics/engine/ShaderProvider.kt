package com.streamify.graphics.engine

import com.streamify.graphics.api.SovereignGraphicsEngine
import com.streamify.graphics.governor.CapabilityProbe
import com.streamify.graphics.governor.RenderTier

/**
 * Platform shader adapter port of the Sovereign GPU Engine
 * (docs/gpu/ARCHITECTURE.md section 1, `[Adapters] ShaderProvider (expect /
 * actual)`).
 *
 * Two actuals:
 *  * `androidMain` — `ShaderProvider.android.kt`: AGSL `RuntimeShader` +
 *    `RenderEffect.createRuntimeShaderEffect` (API 33+),
 *    `RenderEffect.createBlurEffect` (API 31-32), cached acrylic scrim
 *    (API 26-30). All API-31+ class references are isolated in
 *    `@RequiresApi`-gated classes behind explicit `SDK_INT` checks, so an
 *    API 27 process (Vivo 1712 / Android 8.1) never links
 *    `android.graphics.RenderEffect` / `RuntimeShader` — the hard invariant
 *    of docs/gpu/CI_TESTING_SPEC.md section 2 (Job 2).
 *  * `skikoCommonMain` — `ShaderProvider.skiko.kt` (desktop JVM, wasmJs,
 *    and iOS shared source set): Skia `RuntimeEffect` / `RuntimeShaderBuilder`
 *    over the canonical `LiquidGlassCore` source with all six uniforms
 *    (`backdrop` child, `resolution`, `lensRadius`, `refractionIndex`,
 *    `chromaticSpread`, `tintColor`) bound through the Skia dialect's
 *    `child("backdrop", ...)` seam.
 *
 * Contract:
 *  * [probe] is pure signal classification — it never allocates persistent
 *    GPU resources beyond a one-shot compile validation, is safe to call
 *    from any thread, and is cached per process.
 *  * [engineFor] is cheap (a cached array lookup after first construction)
 *    because the tier-governed engine calls it on **every draw pass**;
 *    the returned engine honors the [RenderTier] it is given, and the
 *    mapping is driven exclusively by
 *    [ShaderTierLadder][com.streamify.graphics.engine.adapter.ShaderTierLadder]
 *    so platform actuals stay thin, testable shells over the common
 *    ladder.
 */
expect object ShaderProvider {

    /**
     * Real platform capability signals for the governor's
     * [CapabilityProbe]: `Build.VERSION.SDK_INT` plus a live minimal AGSL
     * compile probe on Android (API 33+), Skia `RuntimeEffect` validation
     * on the skiko targets.
     */
    fun probe(): CapabilityProbe

    /**
     * The per-tier engine — the seam `di/GraphicsModule.kt` wires into the
     * tier-governed engine's tier selection. Total over every tier and
     * capability combination: never returns null, and after the one-time
     * engine construction it never throws.
     */
    fun engineFor(tier: RenderTier): SovereignGraphicsEngine
}
