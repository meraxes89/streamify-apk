package com.streamify.graphics.governor

/**
 * TBDR-friendly dynamic resolution scaling (DRS) policy for the Sovereign
 * GPU Engine's Retina lanes (docs/systems/S24-sovereign-graphics.md,
 * Track C; docs/gpu/ARCHITECTURE.md section 3).
 *
 * Apple Silicon GPUs — like every mobile GPU — render through small
 * on-chip tile memories (Tile-Based Deferred Rendering). A full-resolution
 * backdrop capture and multi-pass refraction at Retina 3x (1290 x 2796 on
 * an iPhone Pro Max, over 3.6 million pixels) forces the GPU to flush
 * on-chip tile memory back to system RAM repeatedly, burning battery and
 * choking older A-series parts (A9–A13, iPhone 7–11). Downsampling the
 * backdrop capture to **logical 1x** before the blur/refraction pass cuts
 * tile flushes and memory bandwidth by ~75% (a 3x capture has 9x the
 * pixels of the logical-1x capture; the pass cost scales with it).
 *
 * ## The 2-axis decision table (no marketing names)
 *
 * Per the engine's architectural invariant, the policy is evaluated
 * strictly along the capability axes — the **live tier** (which already
 * folds thermal and P95 headroom) and the **display density scale**.
 * There is deliberately no A-series / M-series chipset table: older
 * A-series devices converge on Tier 2 (Balanced) at 3x Retina *through
 * the governor* (they cannot sustain the 120 Hz fast-frame streak, and
 * thermal/P95 events demote them), which is exactly the row that engages
 * DRS.
 *
 * | Live tier | Density scale | Capture scale          | Rationale                                            |
 * |-----------|---------------|------------------------|------------------------------------------------------|
 * | Tier 1    | any           | 1.0 (full resolution)  | earned: sustained fast frames under the live budget  |
 * | Tier 2    | >= 2.0        | 1 / density (>= 1/3)   | logical 1x: 0.5 at 2x, 0.33 at 3x Retina             |
 * | Tier 2    | < 2.0         | 1.0                    | logical 1x *is* full resolution on 1x displays       |
 * | Tier 3/4  | any           | 1.0                    | scrim lanes: zero blur/refraction passes to shrink  |
 *
 * The blur/refraction pass that consumes the capture (the skiko
 * `DynamicResolutionOffscreenPass`) renders at `captureScale` resolution
 * and upscales back into the destination outline with hardware bilinear
 * filtering; the upscale is the cheap fixed-function pass, the expensive
 * multi-tap sampling runs at 1/9th the pixels at 3x.
 */
object BackdropResolutionPolicy {

    /**
     * Lower bound of the capture scale. A 3x Retina capture at 1/3 scale
     * IS logical 1x; even denser hypothetical displays never go below it.
     */
    const val MIN_CAPTURE_SCALE = 1f / 3f

    /**
     * Density scale at (or above) which the Tier 2 lane downsamples:
     * Retina-class rendering (2x / 3x), where the TBDR tile-flush cost of
     * a full-resolution backdrop dominates.
     */
    const val RETINA_DENSITY_THRESHOLD = 2.0f

    /**
     * The backdrop capture scale for one (live tier, display density)
     * pair — see the decision table above. The result multiplies both the
     * capture dimensions and the blur/refraction pass resolution.
     *
     * Density is a *display property* (UIScreen.scale on iOS, AWT uiScale
     * on desktop), never a marketing-name signal. Non-finite or
     * non-positive density is treated as 1x (conservative: no downsample).
     */
    fun captureScale(tier: RenderTier, displayDensityScale: Float): Float {
        val density =
            if (displayDensityScale.isNaN() || displayDensityScale <= 0f) {
                1f
            } else {
                displayDensityScale
            }
        return when {
            // Tier 1 earned full-resolution rendering; scrim lanes have no
            // blur/refraction pass to downsample.
            tier != RenderTier.Tier2_Balanced -> 1f
            density < RETINA_DENSITY_THRESHOLD -> 1f
            else -> (1f / density).coerceAtLeast(MIN_CAPTURE_SCALE)
        }
    }
}
