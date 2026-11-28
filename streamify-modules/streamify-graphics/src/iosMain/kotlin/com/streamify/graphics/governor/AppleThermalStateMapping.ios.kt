package com.streamify.graphics.governor

import platform.Foundation.NSProcessInfoThermalState

/**
 * iosMain weld of [AppleThermalStateMapping] onto the live Foundation
 * constants (S24 Track A; docs/systems/S24-sovereign-graphics.md).
 *
 * `NSProcessInfoThermalState` is an `NS_ENUM(NSInteger, ...)`, so the
 * Kotlin binding exposes the four cases with their full names
 * (`NSProcessInfoThermalState.NSProcessInfoThermalStateSerious` — the
 * same binding shape `UIDeviceOrientation` exhibits). This weld compares
 * against the imported constants directly so it is correct for any
 * binding shape, and the iosTest suite pins it 1:1 against the
 * platform-agnostic [AppleThermalStateMapping.fromRawValue] table:
 *
 *  * `Nominal`  -> [ThermalStatus.NOMINAL]  (promotion gate open)
 *  * `Fair`     -> [ThermalStatus.LIGHT]    (below demotion threshold)
 *  * `Serious`  -> [ThermalStatus.MODERATE] (instant synchronous Tier 3 demotion)
 *  * `Critical` -> [ThermalStatus.CRITICAL] (instant synchronous Tier 4 demotion)
 *
 * Unknown states fail conservative — toward CRITICAL — matching the
 * [fromRawValue] table's posture.
 */
internal fun mapThermalState(state: NSProcessInfoThermalState): ThermalStatus = when {
    state == NSProcessInfoThermalState.NSProcessInfoThermalStateCritical ->
        ThermalStatus.CRITICAL
    state == NSProcessInfoThermalState.NSProcessInfoThermalStateSerious ->
        ThermalStatus.MODERATE
    state == NSProcessInfoThermalState.NSProcessInfoThermalStateFair ->
        ThermalStatus.LIGHT
    else -> ThermalStatus.NOMINAL
}
