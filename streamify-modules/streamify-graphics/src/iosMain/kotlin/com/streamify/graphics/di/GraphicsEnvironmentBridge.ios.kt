package com.streamify.graphics.di

import com.streamify.graphics.governor.ThermalStatus
import com.streamify.graphics.governor.TierGovernor
import com.streamify.graphics.governor.mapThermalState
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSProcessInfo
import platform.Foundation.isLowPowerModeEnabled
import platform.Foundation.thermalState
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
 * `NSProcessInfo.processInfo.isLowPowerModeEnabled()` toggles feed
 * [TierGovernor.onLowPowerModeEnabled], which caps the promotion ceiling
 * at Tier 2, demotes a live Tier 1 immediately and pins the frame
 * budgets to the 60 Hz lane (the display link itself is capped at 60 Hz
 * in this mode).
 *
 * Binding note: cinterop imports ObjC properties with custom getter
 * names (@property (getter=isLowPowerModeEnabled) BOOL
 * lowPowerModeEnabled) — and external-getter readonly declarations such
 * as thermalState — as package extensions, NOT class members: both
 * extensions must be imported explicitly
 * (import platform.Foundation.isLowPowerModeEnabled /
 * import platform.Foundation.thermalState) before
 * NSProcessInfo.processInfo.isLowPowerModeEnabled() and
 * NSProcessInfo.processInfo.thermalState resolve. Verified against the
 * identical import pair in cpu-info's TemperatureProvider.ios.kt.
 *
 * ## Reentrancy guard
 *
 * attach/detach use a `kotlin.concurrent.atomics.AtomicInt` CAS guard —
 * NOT `synchronized`, which is JVM-only (Kotlin/Native has no monitor
 * intrinsic; the CI gate caught exactly that). Exactly one registration
 * per governor lifetime; the current state is synchronized BEFORE the
 * observers register, so the governor never renders a stale thermal or
 * power assumption for the first frames.
 *
 * Notification-name literals: the Foundation constants' runtime string
 * values are defined to equal their symbol names (the `NSNotificationName`
 * convention); the literals keep this bridge compilable against any
 * NSString interop shape and are pinned by the iosTest weld suite.
 */
@OptIn(ExperimentalAtomicApi::class)
actual object GraphicsEnvironmentBridge {

    /** Notification names — see the class KDoc on the literal choice. */
    private const val THERMAL_STATE_DID_CHANGE = "NSProcessInfoThermalStateDidChangeNotification"
    private const val POWER_STATE_DID_CHANGE = "NSProcessInfoPowerStateDidChangeNotification"

    /** Bridge lifecycle guard: 0 = detached, 1 = attached. */
    private const val STATE_DETACHED = 0
    private const val STATE_ATTACHED = 1

    /** CAS-guarded lifecycle state (see the class KDoc). */
    private val bridgeState = AtomicInt(STATE_DETACHED)

    /** Observer tokens from the block-based NSNotificationCenter API. */
    private var thermalObserver: NSObjectProtocol? = null
    private var powerObserver: NSObjectProtocol? = null

    actual fun attach(governor: TierGovernor) {
        // Idempotent single registration: concurrent double-attach resolves
        // with exactly one winner via CAS; the loser returns immediately.
        if (!bridgeState.compareAndSet(STATE_DETACHED, STATE_ATTACHED)) return

        // Sync the current state BEFORE registering, so the governor
        // never renders a stale thermal/power assumption for the first
        // frames (an already-warm or already-throttling device demotes
        // right away instead of after the first event).
        runCatching {
            governor.onThermalStatusChanged(currentThermalStatus())
            governor.onLowPowerModeEnabled(
                NSProcessInfo.processInfo.isLowPowerModeEnabled(),
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
                    NSProcessInfo.processInfo.isLowPowerModeEnabled(),
                )
            }
        }.getOrNull()
    }

    actual fun detach() {
        // Only the attach owner detaches; double-detach is a no-op.
        if (!bridgeState.compareAndSet(STATE_ATTACHED, STATE_DETACHED)) return
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
