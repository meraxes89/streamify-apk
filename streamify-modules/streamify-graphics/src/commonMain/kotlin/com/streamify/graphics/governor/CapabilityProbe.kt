package com.streamify.graphics.governor

/**
 * Classification of the programmable-shader pipeline a device can host
 * (docs/gpu/ARCHITECTURE.md, section 3, axis 1 "API / Driver Capability").
 *
 * This is a *capability axis*, not a quality verdict: it answers "which
 * rendering lane exists at all", while the [TierGovernor] decides how far up
 * that lane the engine is allowed to climb at runtime.
 */
enum class ShaderCapability {
    /**
     * Full runtime shader programs — Android AGSL (`RuntimeShader`, API 33+)
     * or Skia `RuntimeEffect.makeForShader()` on the skiko targets. Tier 1
     * (Ultra) prerequisite.
     */
    RUNTIME_SHADER,

    /**
     * Fixed-function GPU effect pipeline only — Android
     * `RenderEffect.createBlurEffect()` (API 31–32). No AGSL, but hardware
     * blur/refraction primitives exist. Tier 2 (Balanced) ceiling.
     */
    RENDER_EFFECT,

    /**
     * No programmable shader path at all — API 26–30 acrylic-scrim lane,
     * software rasterizers, and devices whose runtime-shader probe failed.
     * Tier 3 (Legacy) ceiling (or Tier 4 when below the module floor).
     */
    FIXED_FUNCTION,
}

/**
 * Hardware capability probe of the Sovereign GPU Engine
 * (docs/gpu/PHASED_PLAN.md, Phase 2-B; docs/gpu/ARCHITECTURE.md, section 3).
 *
 * A **pure classifier**: the constructor takes the raw device signals and
 * derives the tier-relevant facts. It never calls platform APIs itself —
 * platform adapters feed it `Build.VERSION.SDK_INT`, the `GL_RENDERER`
 * string, and the result of a minimal runtime-shader compile probe, so the
 * classification logic is unit-testable in common code and identical on
 * every target.
 *
 * The engine **never** relies on GPU marketing names (no "Adreno 506 vs
 * Mali-G71" table, docs/gpu/ARCHITECTURE.md section 3). Tiering is evaluated
 * along exactly two orthogonal axes:
 *
 *  1. **API / driver capability** — [apiLevel] + [runtimeShaderCompileSupported]:
 *     * API >= 33 with a compiling AGSL probe -> [ShaderCapability.RUNTIME_SHADER].
 *     * API 31–32 (or API >= 33 whose AGSL probe failed — `RenderEffect`
 *       still exists there) -> [ShaderCapability.RENDER_EFFECT].
 *     * API 26–30 (Vivo 1712 / Android 8.1 / API 27) -> [ShaderCapability.FIXED_FUNCTION].
 *     * Non-Android targets ([apiLevel] <= 0): Skia `RuntimeEffect`
 *       validation decides between RUNTIME_SHADER and FIXED_FUNCTION.
 *     * API 1–25: below the module's `minSdk = 26` floor — maximally safe.
 *  2. **Raster kind** — [rendererDescription] containing `llvmpipe`,
 *     `SwiftShader` or `Software` (case-insensitive) means a software
 *     rasterizer: pinned to **Tier 4 (Safe)** regardless of every other
 *     signal (docs/gpu/CI_TESTING_SPEC.md, Job 4).
 *
 * The derived [hardwareCeiling] is the *initial, static* ceiling — the
 * runtime governor ([TierGovernor]) may sit below it (cold start, thermal
 * demotion, P95 budget violations) but can never promote above it.
 *
 * @param apiLevel Android `Build.VERSION.SDK_INT`; `0` (or negative) on
 * non-Android targets where API level is meaningless.
 * @param rendererDescription Renderer identification string (`GL_RENDERER`
 * on Android, adapter description on desktop); empty when unknown. Used
 * only for software-rasterizer marker matching — never for marketing-name
 * tiering.
 * @param runtimeShaderCompileSupported Whether a minimal shader program
 * compiled through the platform's runtime-shader API (AGSL on Android 33+,
 * Skia `RuntimeEffect` elsewhere). **Conservative by contract**: pass `true`
 * only after a real compile succeeded; unvalidated means unsupported.
 */
class CapabilityProbe(
    val apiLevel: Int,
    val rendererDescription: String,
    val runtimeShaderCompileSupported: Boolean,
) {

    /**
     * Whether the renderer is a software rasterizer. Marker matching is
     * intentionally a dumb case-insensitive substring scan over the three
     * strings that actually occur in the wild (Mesa `llvmpipe`, emulator /
     * headless-GPU `SwiftShader`, generic `Software` renderers) — driver
     * marketing names are explicitly NOT consulted.
     */
    val isSoftwareRasterizer: Boolean = SOFTWARE_RASTERIZER_MARKERS.any { marker ->
        rendererDescription.contains(marker, ignoreCase = true)
    }

    /**
     * The programmable-shader lane available on this device (axis 1). See
     * [ShaderCapability] for the exact API-level mapping. A software
     * rasterizer always collapses to [ShaderCapability.FIXED_FUNCTION]
     * (axis 2 dominates axis 1).
     */
    val shaderCapability: ShaderCapability = when {
        isSoftwareRasterizer -> ShaderCapability.FIXED_FUNCTION
        apiLevel >= 33 ->
            if (runtimeShaderCompileSupported) {
                ShaderCapability.RUNTIME_SHADER
            } else {
                // AGSL present but the probe shader failed to compile:
                // RenderEffect (API 31+) is still a valid lane.
                ShaderCapability.RENDER_EFFECT
            }
        apiLevel in 31..32 -> ShaderCapability.RENDER_EFFECT
        apiLevel <= 0 ->
            // Non-Android target: Skia RuntimeEffect validation is the axis.
            if (runtimeShaderCompileSupported) ShaderCapability.RUNTIME_SHADER
            else ShaderCapability.FIXED_FUNCTION
        else -> ShaderCapability.FIXED_FUNCTION // API 1..30 (incl. 26..30 legacy lane)
    }

    /**
     * Initial hardware capability ceiling — the highest tier the engine may
     * ever run at on this device (docs/gpu/ARCHITECTURE.md, section 3):
     *
     * * Software rasterizer -> [RenderTier.Tier4_Safe] — axis 2 pins Safe
     *   regardless of how high the API level is (an llvmpipe device at API
     *   34 is still a software rasterizer).
     * * RUNTIME_SHADER -> [RenderTier.Tier1_Ultra]
     * * RENDER_EFFECT -> [RenderTier.Tier2_Balanced]
     * * FIXED_FUNCTION -> [RenderTier.Tier3_Legacy], or
     *   [RenderTier.Tier4_Safe] when the device is below the module's
     *   API 26 floor (cannot trust anything about it).
     */
    val hardwareCeiling: RenderTier = when {
        // Axis 2 dominates axis 1: the raster kind pins the ceiling before
        // any shader-capability lane is even considered.
        isSoftwareRasterizer -> RenderTier.Tier4_Safe
        shaderCapability == ShaderCapability.RUNTIME_SHADER -> RenderTier.Tier1_Ultra
        shaderCapability == ShaderCapability.RENDER_EFFECT -> RenderTier.Tier2_Balanced
        else ->
            if (apiLevel in 1..25) RenderTier.Tier4_Safe else RenderTier.Tier3_Legacy
    }

    private companion object {
        /** Renderer substrings that unambiguously indicate software rasterizing. */
        val SOFTWARE_RASTERIZER_MARKERS = listOf("llvmpipe", "swiftshader", "software")
    }
}
