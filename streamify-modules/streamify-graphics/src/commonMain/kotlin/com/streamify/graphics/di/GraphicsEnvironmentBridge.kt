package com.streamify.graphics.di

import com.streamify.graphics.governor.TierGovernor

/**
 * Platform environment bridge — the expect/actual seam that wires the
 * native thermal / power notification sources of a host platform into a
 * live [TierGovernor] (docs/systems/S24-sovereign-graphics.md, Track A).
 *
 * The bridge is attached exactly once per process from the Koin wiring
 * (`di/GraphicsModule.kt`) when the governor singleton is created, and is
 * idempotent: a second [attach] detaches the previous observers first.
 *
 * Actuals:
 *  * **iosMain** — the Apple thermal & Low Power Mode bridge:
 *    `NSProcessInfoThermalStateDidChangeNotification` /
 *    `NSProcessInfoPowerStateDidChangeNotification` observed through
 *    `NSNotificationCenter.defaultCenter`, dispatching **synchronously**
 *    (queue = null — the observer runs on the posting thread) into
 *    [TierGovernor.onThermalStatusChanged] /
 *    [TierGovernor.onLowPowerModeEnabled]. Thermal states map through
 *    [com.streamify.graphics.governor.AppleThermalStateMapping]
 *    (`serious` -> MODERATE -> instant Tier 3 demotion, `critical` ->
 *    CRITICAL -> Tier 4).
 *  * **androidMain / desktopMain / wasmJsMain** — no-op. Android's
 *    PowerManager thermal listener stays with the app-layer
 *    `ThermalGovernorManager` lane (the module keeps zero
 *    android.os.PowerManager references so API 27 processes never
 *    class-link through it); the desktop JVM lane has no
 *    NSProcessInfo-equivalent thermal API.
 */
expect object GraphicsEnvironmentBridge {

    /**
     * Registers the platform's thermal / power observers against
     * [governor] and synchronizes the current state once, before any
     * event can arrive. Safe to call from any thread; never throws.
     */
    fun attach(governor: TierGovernor)

    /** Removes the observers registered by [attach]. Never throws. */
    fun detach()
}
