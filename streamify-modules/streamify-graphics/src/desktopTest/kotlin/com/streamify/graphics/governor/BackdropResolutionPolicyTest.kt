package com.streamify.graphics.governor

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * S24 Track E — the TBDR dynamic resolution scaling (DRS) decision table
 * gate (docs/systems/S24-sovereign-graphics.md, Track C;
 * docs/gpu/ARCHITECTURE.md section 3).
 *
 * Pins the 2-axis [BackdropResolutionPolicy] matrix: the live tier (which
 * folds thermal + P95 headroom) against the display density scale. No
 * chipset / marketing names — older A-series devices converge on the
 * Tier 2 + 3x row through the runtime governor.
 */
class BackdropResolutionPolicyTest {

    @Test
    fun tier1EarnsFullResolutionAtEveryDensity() {
        for (density in listOf(1f, 2f, 3f)) {
            assertEquals(1f, BackdropResolutionPolicy.captureScale(RenderTier.Tier1_Ultra, density), "density=$density")
        }
    }

    @Test
    fun tier2AtRetinaThreeXDownsamplesToLogicalOneX() {
        // 1290x2796 iPhone Pro Max capture: 0.33x scale == logical 1x —
        // the ~75% TBDR tile-flush cut.
        assertEquals(1f / 3f, BackdropResolutionPolicy.captureScale(RenderTier.Tier2_Balanced, 3f), 1e-6f)
    }

    @Test
    fun tier2AtRetinaTwoXDownsamplesToHalf() {
        assertEquals(0.5f, BackdropResolutionPolicy.captureScale(RenderTier.Tier2_Balanced, 2f), 1e-6f)
    }

    @Test
    fun tier2AtIntermediateRetinaDensitiesScalesInverselyWithDensity() {
        assertEquals(0.4f, BackdropResolutionPolicy.captureScale(RenderTier.Tier2_Balanced, 2.5f), 1e-6f)
    }

    @Test
    fun tier2BelowRetinaThresholdStaysFullResolution() {
        // Logical 1x IS full resolution on a 1x/1.5x display — nothing to
        // downsample, and the DRS pass must not engage.
        for (density in listOf(1f, 1.5f, 1.99f)) {
            assertEquals(1f, BackdropResolutionPolicy.captureScale(RenderTier.Tier2_Balanced, density), "density=$density")
        }
    }

    @Test
    fun scrimLanesNeverDownsample() {
        // Tier 3 / Tier 4 run zero blur/refraction passes — there is no
        // expensive sampling pass to shrink, and their backdrop draws stay
        // as-is.
        for (tier in listOf(RenderTier.Tier3_Legacy, RenderTier.Tier4_Safe)) {
            for (density in listOf(1f, 2f, 3f)) {
                assertEquals(1f, BackdropResolutionPolicy.captureScale(tier, density), "tier=$tier density=$density")
            }
        }
    }

    @Test
    fun degenerateDensitiesFailConservativeToFullResolution() {
        for (density in listOf(0f, -3f, Float.NaN)) {
            assertEquals(1f, BackdropResolutionPolicy.captureScale(RenderTier.Tier2_Balanced, density), "density=$density")
        }
    }

    @Test
    fun hypotheticalUltraDenseDisplaysNeverDropBelowTheOneThirdFloor() {
        assertEquals(
            BackdropResolutionPolicy.MIN_CAPTURE_SCALE,
            BackdropResolutionPolicy.captureScale(RenderTier.Tier2_Balanced, 6f),
            1e-6f,
        )
    }
}
