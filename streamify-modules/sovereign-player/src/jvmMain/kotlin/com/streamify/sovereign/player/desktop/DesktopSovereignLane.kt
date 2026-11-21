package com.streamify.sovereign.player.desktop

import com.streamify.sovereign.player.SovereignHalProbe
import com.streamify.sovereign.player.SovereignPlaybackLane
import com.maxrave.domain.mediaservice.handler.MediaPlayerHandler
import com.maxrave.domain.mediaservice.player.MediaPlayerInterface
import com.maxrave.domain.mediaservice.handler.PlayerEvent

/**
 * Sovereign HAL Phase 1 (D4): the DESKTOP probe actual.
 *
 * The desktop bracket is healthy when the Rust engine library loads AND
 * the frozen ring edge answers (`streamify_ring_channels` returns 1–2):
 * the cpal output engine (streamify-hal) attaches the ring in-process
 * on the Rust side; the JNA edge is our read-only view of it.
 *
 * Any failure degrades to [com.streamify.sovereign.player.SovereignRoutePolicy]'s
 * LegacyFallback arm — the user's playback continues through libmpv,
 * byte-identical to the shipped default (the zero-deletion policy:
 * libmpv stays the automatic fallback until all 5 loopback gates pass
 * in Phase 3).
 */
class DesktopSovereignHalProbe : SovereignHalProbe {
    private var lastReason: String = "not probed yet"

    override fun isBracketAvailable(): Boolean {
        val native = SOVEREIGN_NATIVE
        if (native == null) {
            lastReason = "Rust engine library (streamify_core_rs) not on java.library.path"
            return false
        }
        val channels = runCatching { native.streamify_ring_channels() }.getOrDefault(0)
        if (channels !in 1..2) {
            lastReason = "ring edge detached (no sink attached; channels=$channels)"
            return false
        }
        return true
    }

    override fun lastUnavailableReason(): String = lastReason
}

/**
 * Sovereign HAL Phase 1 (D4): the DESKTOP playback-lane actual.
 *
 * Phase 1's lane is the TELEMETRY + route-decision surface: the cpal
 * engine's producer side (fetch → Opus/AAC decode → resample → LUFS →
 * ring write) is Rust-in-process; this lane surfaces its live stats to
 * the handler/SettingScreen and rides the legacy surface for queue
 * state (the Phase 0 split: queue/metadata stay in ONE owner — the
 * upstream handler — while AUDIO control rides the ring).
 *
 * The lane's `player` deliberately mirrors the LEGACY player surface
 * (`SovereignPlaybackLaneNotWired.player` shape): flag-on routing hands
 * AUDIO control to the Rust engine while every queue mutation stays on
 * the legacy path. Phase 2 swaps `player` for the engine-backed surface.
 */
class DesktopSovereignLane(
    private val legacyPlayer: MediaPlayerInterface,
) : SovereignPlaybackLane {

    /** The latest stats-wire snapshot (null while detached). */
    fun stats(): SovereignStatsWire? = readSovereignStatsWire()

    override val player: MediaPlayerInterface
        get() = legacyPlayer

    override suspend fun onPlayerEvent(
        event: PlayerEvent,
        legacy: MediaPlayerHandler,
    ) {
        // Phase 1: no event translation yet — the lane is passive
        // (telemetry + probe). Phase 2's engine-backed surface hooks
        // play/pause/seek here.
    }

    override fun release() {
        // Nothing owned by the JVM lane: the Rust engine's lifetime is
        // the process's (the DI singleton scope).
    }
}
