package com.streamify.graphics.governor

import kotlin.test.Test
import kotlin.test.assertEquals
import platform.Foundation.NSProcessInfoThermalState

/**
 * S24 Track E — the iOS weld between the live Foundation constants and the
 * platform-agnostic mapping table (docs/systems/S24-sovereign-graphics.md,
 * Track A; runs via `iosSimulatorArm64Test` on the macOS CI runner).
 *
 * `AppleThermalStateMappingTest` (desktop JVM) pins the raw-value table;
 * this suite pins the iosMain `mapThermalState` weld against it — the two
 * can never drift apart because this test fails on any divergence.
 */
class AppleThermalStateWeldTest {

    @Test
    fun nominalWeldsToTheTable() {
        assertEquals(
            AppleThermalStateMapping.fromRawValue(AppleThermalStateMapping.NS_PROCESS_INFO_THERMAL_STATE_NOMINAL),
            mapThermalState(NSProcessInfoThermalState.NSProcessInfoThermalStateNominal),
        )
    }

    @Test
    fun fairWeldsToTheTable() {
        assertEquals(
            AppleThermalStateMapping.fromRawValue(AppleThermalStateMapping.NS_PROCESS_INFO_THERMAL_STATE_FAIR),
            mapThermalState(NSProcessInfoThermalState.NSProcessInfoThermalStateFair),
        )
    }

    @Test
    fun seriousWeldsToTheTable() {
        assertEquals(
            AppleThermalStateMapping.fromRawValue(AppleThermalStateMapping.NS_PROCESS_INFO_THERMAL_STATE_SERIOUS),
            mapThermalState(NSProcessInfoThermalState.NSProcessInfoThermalStateSerious),
        )
    }

    @Test
    fun criticalWeldsToTheTable() {
        assertEquals(
            AppleThermalStateMapping.fromRawValue(AppleThermalStateMapping.NS_PROCESS_INFO_THERMAL_STATE_CRITICAL),
            mapThermalState(NSProcessInfoThermalState.NSProcessInfoThermalStateCritical),
        )
    }

    @Test
    fun theWholeFoundationConstantSetWeldsToTheFullTable() {
        val expected = listOf(
            ThermalStatus.NOMINAL,
            ThermalStatus.LIGHT,
            ThermalStatus.MODERATE,
            ThermalStatus.CRITICAL,
        )
        val actual = listOf(
            NSProcessInfoThermalState.NSProcessInfoThermalStateNominal,
            NSProcessInfoThermalState.NSProcessInfoThermalStateFair,
            NSProcessInfoThermalState.NSProcessInfoThermalStateSerious,
            NSProcessInfoThermalState.NSProcessInfoThermalStateCritical,
        ).map(::mapThermalState)
        assertEquals(expected, actual)
    }
}
