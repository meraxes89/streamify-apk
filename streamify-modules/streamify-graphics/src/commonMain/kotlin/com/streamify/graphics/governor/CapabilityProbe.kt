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
 * One Track D driver-quirk denylist entry (docs/gpu/ARCHITECTURE.md
 * section 3, axis 3): a **proven compiler defect** with its identifying
 * renderer markers and the workaround — Tier 4 (Safe) demotion.
 *
 * Surgical by contract:
 *  * markers name the broken driver *revision*, not the whole GPU family
 *    (a healthy Adreno 5xx / Mali-G52 must still classify RUNTIME_SHADER);
 *  * the table only **demotes** — a quirk hit can pin Tier 4 but can never
 *    lift a device above its probed capability;
 *  * each entry exists because the defect was observed in the wild, not
 *    because the marketing name looked slow.
 *
 * @param id Stable identifier for telemetry / docs cross-reference.
 * @param markers Case-insensitive substrings identifying the broken
 * driver revision inside `GL_RENDERER` / adapter descriptions.
 * @param defect Human-readable description of the proven defect.
 */
data class DriverQuirk(
    val id: String,
    val markers: List<String>,
    val defect: String,
)

/**
 * Hardware capability probe of the Sovereign GPU Engine
 * (docs/gpu/ARCHITECTURE.md, section 3).
 *
 * A **pure classifier**: the constructor takes the raw device signals and
 * derives the tier-relevant facts. It never calls platform APIs itself —
 * platform adapters feed it `Build.VERSION.SDK_INT`, the `GL_RENDERER`
 * string, and the result of the isolated startup compile probe, so the
 * classification logic is unit-testable in common code and identical on
 * every target.
 *
 * The engine **never** relies on GPU marketing names for tiering
 * (docs/gpu/ARCHITECTURE.md section 3) — with one surgical exception, the
 * [DriverQuirk] denylist (Track D): a *quirk* entry names a proven
 * compiler defect (not a performance judgment), is keyed to marker
 * substrings that identify the broken driver revision, and demotes to
 * Tier 4 only for that defect. Tiering otherwise follows exactly three
 * orthogonal axes:
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
 *  3. **Known driver quirk** (Track D) — [rendererDescription] matching a
 *     [DriverQuirk] marker means the driver revision itself is broken
 *     (defective half-float division, NaN mishandling, AGSL rejection of
 *     valid source): the lane is pinned to **Tier 4 (Safe)** — the quirk
 *     table demotes, never promotes, and a compliant GPU passing the same
 *     markers' absence is never penalized.
 *
 * The derived [hardwareCeiling] is the *initial, static* ceiling — the
 * runtime governor ([TierGovernor]) may sit below it (cold start, thermal
 * demotion, P95 budget violations) but can never promote above it.
 *
 * @param apiLevel Android `Build.VERSION.SDK_INT`; `0` (or negative) on
 * non-Android targets where API level is meaningless.
 * @param rendererDescription Renderer identification string (`GL_RENDERER`
 * on Android, adapter description on desktop); empty when unknown. Used
 * only for software-rasterizer marker matching and the Track D quirk
 * table — never for marketing-name tiering.
 * @param runtimeShaderCompileSupported Whether the canonical shader class
 * compiled through the platform's isolated startup probe (AGSL on Android
 * 33+, Skia `RuntimeEffect` elsewhere). **Conservative by contract**:
 * `true` only after a real compile succeeded; unvalidated or rejected
 * means unsupported.
 * @param compileProbeFailureReason Why the compile probe failed, when it
 * ran and failed — the recorded diagnostic (Track B) that made this probe
 * conservative. `null` when the probe never ran (below API 33 on
 * Android), succeeded, or the failure was silent.
 */
class CapabilityProbe(
    val apiLevel: Int,
    val rendererDescription: String,
    val runtimeShaderCompileSupported: Boolean,
    val compileProbeFailureReason: String? = null,
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
     * The matched Track D driver quirk, if any (Track D denylist). Marker
     * matching is the same dumb case-insensitive substring scan — the
     * entries name proven compiler defects, not performance judgments,
     * so a quirk hit pins Tier 4 while every compliant GPU (which simply
     * does not carry the marker) is never penalized.
     */
    val matchedDriverQuirk: DriverQuirk? = DRIVER_QUIRK_DENYLIST.firstOrNull { quirk ->
        quirk.markers.any { marker -> rendererDescription.contains(marker, ignoreCase = true) }
    }

    /**
     * The programmable-shader lane available on this device (axis 1). See
     * [ShaderCapability] for the exact API-level mapping. A software
     * rasterizer always collapses to [ShaderCapability.FIXED_FUNCTION]
     * (axis 2 dominates axis 1).
     */
    val shaderCapability: ShaderCapability = when {
        isSoftwareRasterizer -> ShaderCapability.FIXED_FUNCTION
        matchedDriverQuirk != null -> ShaderCapability.FIXED_FUNCTION
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
     * * Matched driver quirk -> [RenderTier.Tier4_Safe] — axis 3 pins Safe
     *   the same way: the quirk table demotes broken driver revisions, and
     *   a device that passes the markers' absence is never penalized.
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
        // Axis 3 (Track D): a known-broken driver revision pins Safe —
        // demotion only, never promotion.
        matchedDriverQuirk != null -> RenderTier.Tier4_Safe
        shaderCapability == ShaderCapability.RUNTIME_SHADER -> RenderTier.Tier1_Ultra
        shaderCapability == ShaderCapability.RENDER_EFFECT -> RenderTier.Tier2_Balanced
        else ->
            if (apiLevel in 1..25) RenderTier.Tier4_Safe else RenderTier.Tier3_Legacy
    }

    private companion object {
        /** Renderer substrings that unambiguously indicate software rasterizing. */
        val SOFTWARE_RASTERIZER_MARKERS = listOf("llvmpipe", "swiftshader", "software")

        /**
         * Track D driver-quirk denylist (docs/gpu/ARCHITECTURE.md section
         * 3, axis 3). Each entry is a *proven compiler defect* keyed to
         * marker substrings that identify the broken driver revision — not
         * a marketing-name performance judgment, and never a whole family
         * (a healthy Adreno 5xx must classify RUNTIME_SHADER). A hit
         * demotes to Tier 4 only; demotion never promotes.
         */
        val DRIVER_QUIRK_DENYLIST = listOf(
            // Android Studio emulator software-GLES renderer: broken half
            // division + NaN handling in fragment shaders.
            DriverQuirk(
                id = "emu-gles-swiftshader-fragmentdivision",
                markers = listOf("SwiftShader (Software)"),
                defect = "software GL fragment half-float division munges NaN",
            ),
            // Headless emulator ANGLE/Vulkan adapter: broken half division.
            DriverQuirk(
                id = "emu-angle-halfdiv",
                markers = listOf("ANGLE (Android Emulator, Vulkan)"),
                defect = "emulated Vulkan half division produces NaN in edge cases",
            ),
        )
    }
}
