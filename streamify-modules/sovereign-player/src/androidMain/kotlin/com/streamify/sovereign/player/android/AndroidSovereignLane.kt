package com.streamify.sovereign.player.android

import com.maxrave.domain.mediaservice.handler.MediaPlayerHandler
import com.maxrave.domain.mediaservice.handler.PlayerEvent
import com.maxrave.domain.mediaservice.player.MediaPlayerInterface
import com.streamify.sovereign.player.SovereignHalProbe
import com.streamify.sovereign.player.SovereignPlaybackLane

/**
 * Sovereign HAL Phase 2 (Step 1): the ANDROID probe actual.
 *
 * The Android bracket is healthy when the native umbrella library
 * loads AND its Oboe edge answers (`sovereign_oboe_api_route` responds
 * through the JNI binding — the bracket's C ABI is present). The
 * frozen ring itself lives in the Rust cdylib the producer installs;
 * the probe mirrors DesktopSovereignHalProbe's shape (library + edge
 * health), with the Oboe bracket's own exports as the edge.
 *
 * Any failure degrades to the route ladder's LegacyFallback arm —
 * playback continues through media3, byte-identical to the shipped
 * default (the zero-deletion policy).
 */
class AndroidSovereignHalProbe : SovereignHalProbe {
    private var lastReason: String = "not probed yet"

    override fun isBracketAvailable(): Boolean {
        val binding = SovereignOboeJni.binding
        if (binding == null) {
            lastReason = "native umbrella (libstreamify_native_core.so) not loadable"
            return false
        }
        // The bracket's own liveness edge: api_route answers 0 while no
        // stream is open, but the CALL succeeding proves the exports.
        val route = runCatching { binding.apiRoute() }.getOrDefault(-1)
        if (route < 0) {
            lastReason = "oboe bracket edge unresolved (api_route threw)"
            return false
        }
        return true
    }

    override fun lastUnavailableReason(): String = lastReason
}

/**
 * Sovereign HAL Phase 2 (Step 1): the ANDROID playback-lane actual.
 *
 * Phase 2's lane is the TELEMETRY + route-decision surface over the
 * Oboe bracket — the same staging DesktopSovereignLane holds on the
 * JVM: audio control hands to the Rust producer + C++ Oboe bracket
 * (in-process through the frozen ring), while queue/metadata state
 * stays with the legacy media3 handler (the Phase 0 split). The lane
 * surfaces the bracket's live counters (callbacks / underrun frames /
 * ring occupancy / the DAC clock sample / the Oboe backend route) for
 * the SettingScreen badge and the dogfood telemetry window.
 */
class AndroidSovereignLane(
    private val legacyPlayer: MediaPlayerInterface,
) : SovereignPlaybackLane {

    /** The bracket's live telemetry snapshot (null while detached). */
    data class Telemetry(
        val apiRoute: Int,
        val callbacks: Long,
        val underrunFrames: Long,
        val ringOccupancy: Long,
        val dacClock: SovereignOboeJni.DacClockSample?,
    )

    fun telemetry(): Telemetry? {
        val b = SovereignOboeJni.binding ?: return null
        return runCatching {
            Telemetry(
                apiRoute = b.apiRoute(),
                callbacks = b.callbacks(),
                underrunFrames = b.underrunFrames(),
                ringOccupancy = b.ringOccupancy(),
                dacClock = b.dacClock(),
            )
        }.getOrNull()
    }

    /** The route label the SettingScreen badge renders. */
    fun routeLabel(): String = when (telemetry()?.apiRoute) {
        1 -> "Oboe AAudio"
        2 -> "Oboe OpenSL ES"
        else -> "Legacy Media3"
    }

    override val player: MediaPlayerInterface
        get() = legacyPlayer

    override suspend fun onPlayerEvent(
        event: PlayerEvent,
        legacy: MediaPlayerHandler,
    ) {
        // Phase 2's staging: the bracket's producer mount (Rust engine
        // sessions driven from Kotlin) rides behind the same event
        // surface the desktop lane uses; every queue/metadata mutation
        // forwards to the legacy owner until that mount lands.
        legacy.onPlayerEvent(event)
    }

    override fun release() {
        val b = SovereignOboeJni.binding ?: return
        runCatching { b.close() }
    }
}
