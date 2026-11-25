package com.streamify.graphics.governor

/**
 * The Apple `NSProcessInfoThermalState` mapping table of the Sovereign GPU
 * Engine's Apple lane (docs/systems/S24-sovereign-graphics.md, Track A;
 * docs/gpu/ARCHITECTURE.md section 3, axis 3).
 *
 * `NSProcessInfo.thermalState` reports exactly four states (raw values 0-3).
 * They weld onto the platform-agnostic [ThermalStatus] ladder — which keeps
 * the six-value Android `PowerManager` shape — as follows:
 *
 * | `NSProcessInfoThermalState` | raw value | [ThermalStatus]           | Governor consequence                                       |
 * |-----------------------------|-----------|----------------------------|------------------------------------------------------------|
 * | `Nominal`                   | 0         | [ThermalStatus.NOMINAL]    | none — promotion gate open                                 |
 * | `Fair`                      | 1         | [ThermalStatus.LIGHT]      | none — below the demotion threshold                        |
 * | `Serious`                   | 2         | [ThermalStatus.MODERATE]   | instant synchronous demotion to Tier 3 (Legacy scrim)      |
 * | `Critical`                  | 3         | [ThermalStatus.CRITICAL]   | instant synchronous demotion to Tier 4 (Safe solid tint)   |
 *
 * The mapping is a pure table (constants + [fromRawValue]) so the desktop
 * JVM test suite pins it without an Apple host
 * (`desktopTest/.../governor/AppleThermalStateMappingTest.kt`); the iosMain
 * environment bridge consumes it through the live
 * `NSProcessInfo.processInfo.thermalState` constant comparisons and the
 * iosTest suite welds the two (same module, internal visibility).
 *
 * Unknown / out-of-range raw values fail **conservative** (toward CRITICAL):
 * the engine would rather throttle a display it does not understand than
 * burn battery on one it believes healthy — the same
 * conservative-by-contract posture [CapabilityProbe] applies to shader
 * compile validation.
 */
object AppleThermalStateMapping {

    /** `NSProcessInfoThermalStateNominal` — no thermal pressure (raw 0). */
    const val NS_PROCESS_INFO_THERMAL_STATE_NOMINAL = 0

    /** `NSProcessInfoThermalStateFair` — marginal thermal pressure (raw 1). */
    const val NS_PROCESS_INFO_THERMAL_STATE_FAIR = 1

    /** `NSProcessInfoThermalStateSerious` — serious pressure (raw 2): Tier 3 demotion. */
    const val NS_PROCESS_INFO_THERMAL_STATE_SERIOUS = 2

    /** `NSProcessInfoThermalStateCritical` — critical pressure (raw 3): Tier 4 demotion. */
    const val NS_PROCESS_INFO_THERMAL_STATE_CRITICAL = 3

    /**
     * Maps a raw `NSProcessInfoThermalState` value onto the multiplatform
     * [ThermalStatus] ladder. Values at or above
     * [NS_PROCESS_INFO_THERMAL_STATE_CRITICAL] map to
     * [ThermalStatus.CRITICAL]; values at or below
     * [NS_PROCESS_INFO_THERMAL_STATE_NOMINAL] to [ThermalStatus.NOMINAL].
     */
    fun fromRawValue(rawValue: Int): ThermalStatus = when {
        rawValue >= NS_PROCESS_INFO_THERMAL_STATE_CRITICAL -> ThermalStatus.CRITICAL
        rawValue == NS_PROCESS_INFO_THERMAL_STATE_SERIOUS -> ThermalStatus.MODERATE
        rawValue == NS_PROCESS_INFO_THERMAL_STATE_FAIR -> ThermalStatus.LIGHT
        else -> ThermalStatus.NOMINAL
    }
}
