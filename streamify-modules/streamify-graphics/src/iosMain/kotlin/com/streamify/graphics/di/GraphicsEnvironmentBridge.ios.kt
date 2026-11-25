package com.streamify.graphics.di

import com.streamify.graphics.governor.ThermalStatus
import com.streamify.graphics.governor.TierGovernor
import com.streamify.graphics.governor.mapThermalState
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSProcessInfo
import platform.darwin.NSObjectProtocol

/**
 * iosMain actual of [GraphicsEnvironmentBridge] — the native Apple
 * thermal & Low Power Mode bridge of S24 Track A
 * (docs/systems/S24-sovereign-graphics.md).
 *
 * ## Thermal state (`NSProcessInfoThermalStateDidChangeNotification`)
 *
 * Observed through `NSNotificationCenter.defaultCenter` with
 * `queue = null` — the observer block runs **synchronously on the posting
 * thread**, which is exactly the "dispatch synchronous events" contract:
 * [TierGovernor.onThermalStatusChanged] is safe from any thread and
 * demotes synchronously. States map through [mapThermalState]
 * (`serious` -> MODERATE -> instant Tier 3, `critical` -> CRITICAL ->
 * instant Tier 4).
 *
 * ## Low Power Mode (`NSProcessInfoPowerStateDidChangeNotification`)
 *
 * `NSProcessInfo.processInfo.isLowPowerModeEnabled` toggles feed
 * [TierGovernor.onLowPowerModeEnabled], which caps the promotion ceiling
 * at Tier 2, demotes a live Tier 1 immediately and pins the frame
 * budgets to the 60 Hz lane (the display link itself is capped at 60 Hz
 * in this mode).
 *
 * Notification-name literals: the Foundation constants' runtime string
 * values are defined to equal their symbol names (the `NSNotificationName`
 * convention); the literals keep this bridge compilable against any
 * NSString interop shape and are pinned by the iosTest weld suite.
 */
actual object GraphicsEnvironmentBridge {

    /** Notification names — see the class KDoc on the literal choice. */
    private const val THERMAL_STATE_DID_CHANGE = "NSProcessInfoThermalStateDidChangeNotification"
    private const val POWER_STATE_DID_CHANGE = "NSProcessInfoPowerStateDidChangeNotification"

    /** Observer tokens; null while detached. Guarded by the bridge lock. */
    private var thermalObserver: NSObjectProtocol? = null
    private var powerObserver: NSObjectProtocol? = null

    /** Guards attach/detach against concurrent Koin resolution threads. */
    private val bridgeLock = Any()

    actual fun attach(governor: TierGovernor) {
        synchronized(bridgeLock) {
            detachLocked()

            // Sync the current state BEFORE registering, so the governor
            // never renders a stale thermal/power assumption for the first
            // frames (an already-warm or already-throttling device
            // demotes right away instead of after the first event).
            runCatching {
                governor.onThermalStatusChanged(currentThermalStatus())
                governor.onLowPowerModeEnabled(
                    NSProcessInfo.processInfo.isLowPowerModeEnabled,
                )
            }

            val center = NSNotificationCenter.defaultCenter

            // queue = null: synchronous delivery on the posting thread —
            // the governor's any-thread demotion contract absorbs it.
            thermalObserver = runCatching {
                center.addObserverForName(
                    THERMAL_STATE_DID_CHANGE,
                    null,
                    null,
                ) { _ ->
                    governor.onThermalStatusChanged(currentThermalStatus())
                }
            }.getOrNull()

            powerObserver = runCatching {
                center.addObserverForName(
                    POWER_STATE_DID_CHANGE,
                    null,
                    null,
                ) { _ ->
                    governor.onLowPowerModeEnabled(
                        NSProcessInfo.processInfo.isLowPowerModeEnabled,
                    )
                }
            }.getOrNull()
        }
    }

    actual fun detach() {
        synchronized(bridgeLock) {
            detachLocked()
        }
    }

    private fun detachLocked() {
        val center = NSNotificationCenter.defaultCenter
        thermalObserver?.let { token ->
            runCatching { center.removeObserver(token) }
        }
        thermalObserver = null
        powerObserver?.let { token ->
            runCatching { center.removeObserver(token) }
        }
        powerObserver = null
    }

    /**
     * The live thermal status, mapped from `NSProcessInfo.processInfo
     * .thermalState` through the [mapThermalState] weld. Exposed internal
     * for the iosTest smoke suite; never throws (defaults to NOMINAL on
     * a failed read — an unreadable sensor cannot justify throttling).
     */
    internal fun currentThermalStatus(): ThermalStatus = runCatching {
        mapThermalState(NSProcessInfo.processInfo.thermalState)
    }.getOrDefault(ThermalStatus.NOMINAL)
}
