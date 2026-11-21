package com.streamify.app.cast

import com.streamify.app.connect.ConnectDevice
import com.streamify.app.connect.DeviceOrigin

/**
 * Lifecycle of the single Google Cast route this phone participates in.
 * Mirrors the framework CastState without importing gms symbols so the
 * classification matrix stays JVM-testable.
 */
enum class CastRoutePhase {
    /** No receivers on the network. */
    NO_DEVICES,
    /** Receivers visible, none joined. */
    IDLE,
    /** Joining a receiver. */
    CONNECTING,
    /** Cast route live — the receiver renders our session. */
    CONNECTED,
    /** Migrating between receivers. */
    TRANSFERRING
}

/**
 * StreamifyMediaRouteProvider (Gap #53) — classification of the Cast route
 * into the Connect device vocabulary.
 *
 * Pure functions only: the Android-facing [CastMediaManager] feeds
 * framework callbacks + friendly names in, and publishes the resulting
 * [ConnectDevice] rows into ConnectRuntime's cast route provider.
 */
object StreamifyMediaRouteProvider {

    /**
     * Route row for the picker. Returns null while no receiver is known —
     * an invisible Cast row beats a dead one.
     */
    fun castDeviceFor(phase: CastRoutePhase, friendlyName: String?): ConnectDevice? {
        if (phase == CastRoutePhase.NO_DEVICES) return null
        val name = friendlyName?.takeIf { it.isNotBlank() } ?: "Nearby Cast device"
        return ConnectDevice(
            id = routeIdFor(name),
            name = name,
            kind = com.streamify.app.connect.ConnectRouteKind.CAST_RECEIVER,
            origin = DeviceOrigin.CAST,
            supportsHandoff = true
        )
    }

    /**
     * Stable route id for a receiver: same TV must keep the same id across
     * discovery refreshes so the picker row never flickers into duplicates.
     */
    fun routeIdFor(friendlyName: String): String = "cast-${friendlyName.trim().lowercase()}"

    /**
     * Route-aware volume decision (Gap #16): Cast receivers accept a shared
     * remote stream-gain slider; Bluetooth/A2DP_LE/car routes never do (the
     * OS hardware stack owns master gain there). Delegates to the single
     * Connect policy matrix so cast and connect can never disagree.
     */
    fun volumeAclFor(phase: CastRoutePhase) =
        com.streamify.app.connect.ConnectVolumePolicy.forRoute(
            com.streamify.app.connect.ConnectRouteKind.CAST_RECEIVER
        )

    /**
     * Route-death rule: a live receiver session dying without migrating.
     * TRANSFERRING is a hop, not a death; anything before CONNECTED was
     * never alive. The coordinator turns this into local-playback fallback.
     */
    fun isRouteDeath(previous: CastRoutePhase, next: CastRoutePhase): Boolean =
        previous == CastRoutePhase.CONNECTED &&
            next != CastRoutePhase.CONNECTED &&
            next != CastRoutePhase.TRANSFERRING
}
