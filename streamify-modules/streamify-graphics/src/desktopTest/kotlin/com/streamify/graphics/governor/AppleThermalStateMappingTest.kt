package com.streamify.graphics.governor

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * S24 Track E — the Apple thermal state mapping table gate
 * (docs/systems/S24-sovereign-graphics.md, Track A contract;
 * docs/gpu/CI_TESTING_SPEC.md Job 1).
 *
 * Pins the exact `NSProcessInfoThermalState` -> [ThermalStatus] weld the
 * iosMain environment bridge dispatches into
 * [TierGovernor.onThermalStatusChanged], including the demotion
 * consequence of every row:
 *
 *  * `nominal`  (0) -> NOMINAL  — promotion gate open
 *  * `fair`     (1) -> LIGHT    — below the demotion threshold
 *  * `serious`  (2) -> MODERATE — instant synchronous Tier 3 demotion
 *  * `critical` (3) -> CRITICAL — instant synchronous Tier 4 demotion
 */
class AppleThermalStateMappingTest {

    @Test
    fun nominalMapsToNominal() {
        assertEquals(
            ThermalStatus.NOMINAL,
            AppleThermalStateMapping.fromRawValue(AppleThermalStateMapping.NS_PROCESS_INFO_THERMAL_STATE_NOMINAL),
        )
    }

    @Test
    fun fairMapsToLight() {
        assertEquals(
            ThermalStatus.LIGHT,
            AppleThermalStateMapping.fromRawValue(AppleThermalStateMapping.NS_PROCESS_INFO_THERMAL_STATE_FAIR),
        )
    }

    @Test
    fun seriousMapsToModerate() {
        assertEquals(
            ThermalStatus.MODERATE,
            AppleThermalStateMapping.fromRawValue(AppleThermalStateMapping.NS_PROCESS_INFO_THERMAL_STATE_SERIOUS),
        )
    }

    @Test
    fun criticalMapsToCritical() {
        assertEquals(
            ThermalStatus.CRITICAL,
            AppleThermalStateMapping.fromRawValue(AppleThermalStateMapping.NS_PROCESS_INFO_THERMAL_STATE_CRITICAL),
        )
    }

    @Test
    fun theWholeTableIsExhaustiveOverTheRawConstantRange() {
        val expected = listOf(
            ThermalStatus.NOMINAL,
            ThermalStatus.LIGHT,
            ThermalStatus.MODERATE,
            ThermalStatus.CRITICAL,
        )
        val actual = (0..3).map { AppleThermalStateMapping.fromRawValue(it) }
        assertEquals(expected, actual)
    }

    @Test
    fun unknownRawValuesFailConservativeTowardCritical() {
        // Future / out-of-range thermal states must read as CRITICAL —
        // the engine throttles a display it does not understand.
        for (rawValue in listOf(4, 7, 100)) {
            assertEquals(
                ThermalStatus.CRITICAL,
                AppleThermalStateMapping.fromRawValue(rawValue),
                "rawValue=$rawValue",
            )
        }
    }

    @Test
    fun nonPositiveRawValuesFailConservativeTowardNominal() {
        for (rawValue in listOf(-1, -100)) {
            assertEquals(
                ThermalStatus.NOMINAL,
                AppleThermalStateMapping.fromRawValue(rawValue),
                "rawValue=$rawValue",
            )
        }
    }

    // ------------------------------------------------------------------
    // Governor consequence of every mapping row (the table's second half)
    // ------------------------------------------------------------------

    @Test
    fun everyMappedStatusDrivesTheDocumentedDemotion() {
        val probe = CapabilityProbe(
            apiLevel = 34,
            rendererDescription = "Adreno (TM) 750",
            runtimeShaderCompileSupported = true,
        )

        // nominal / fair: no demotion (Tier 1 survives).
        for (status in listOf(ThermalStatus.NOMINAL, ThermalStatus.LIGHT)) {
            val promoted = TierGovernor(probe)
            promoted.onThermalStatusChanged(status)
            assertEquals(RenderTier.Tier2_Balanced, promoted.currentTier, "status=$status")
        }

        // serious -> MODERATE: instant Tier 3.
        TierGovernor(probe).apply {
            onThermalStatusChanged(
                AppleThermalStateMapping.fromRawValue(AppleThermalStateMapping.NS_PROCESS_INFO_THERMAL_STATE_SERIOUS),
            )
            assertEquals(RenderTier.Tier3_Legacy, currentTier)
        }

        // critical -> CRITICAL: instant Tier 4.
        TierGovernor(probe).apply {
            onThermalStatusChanged(
                AppleThermalStateMapping.fromRawValue(AppleThermalStateMapping.NS_PROCESS_INFO_THERMAL_STATE_CRITICAL),
            )
            assertEquals(RenderTier.Tier4_Safe, currentTier)
        }
    }
}
