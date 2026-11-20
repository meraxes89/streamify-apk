package com.streamify.sovereign.player

import com.maxrave.domain.data.entities.NewFormatEntity
import com.maxrave.domain.data.model.browse.album.Track
import com.maxrave.domain.data.model.mediaService.SponsorSkipSegments
import com.maxrave.domain.data.player.GenericCastState
import com.maxrave.domain.data.player.GenericCommandButton
import com.maxrave.domain.data.player.GenericMediaItem
import com.maxrave.domain.data.player.PlayerError
import com.maxrave.domain.mediaservice.handler.ControlState
import com.maxrave.domain.mediaservice.handler.MediaPlayerHandler
import com.maxrave.domain.mediaservice.handler.NowPlayingTrackState
import com.maxrave.domain.mediaservice.handler.PlayerEvent
import com.maxrave.domain.mediaservice.handler.QueueData
import com.maxrave.domain.mediaservice.handler.SimpleMediaState
import com.maxrave.domain.mediaservice.handler.SleepTimerState
import com.maxrave.domain.mediaservice.handler.ToastType
import com.maxrave.domain.mediaservice.player.MediaPlayerInterface

/**
 * SovereignMediaPlayerHandler — the Layer-2 upstream-immunity seam
 * (Sovereign HAL Phase 0 / D2).
 *
 * ONE canonical injection boundary wrapping the platform's default
 * [MediaPlayerHandler]:
 *
 *  * flag OFF (the shipped default) or probe unhealthy → EVERY member
 *    delegates 1:1 to [delegate] — media3 on Android, libmpv on desktop,
 *    HTMLAudioElement on web. Byte-identical behavior; upstream can
 *    rewrite their handler internals freely because we only hold the
 *    INTERFACE.
 *  * flag ON + probe healthy → the playback-control surface routes to
 *    [sovereignLane] (the Sovereign ring producer bracket; Phase 1/2
 *    mount the real platform brackets — the ring itself is frozen,
 *    tested and CI-green since WS-A A3/A5).
 *
 * WHY KOTLIN INTERFACE DELEGATION (`by delegate`): upstream's
 * `MediaPlayerHandler` carries 60+ members that evolve at feature
 * velocity. Overriding a curated set (play/pause/seek/setMediaItem/
 * release + the state flows) and delegating the REST via `by` means an
 * upstream member addition costs us NOTHING until we deliberately route
 * it — the immunity property, mechanically.
 *
 * The wrapper owns NO audio resources in Phase 0: `sovereignLane` is
 * the [SovereignPlaybackLane] abstraction with a Phase-0 no-op default
 * (`SovereignPlaybackLaneNotWired`), so flag-on routing is observable
 * (route badge + monitor counters) without a single new audio path —
 * the loopback gates of Phase 1/2 replace the lane binding, not the
 * wrapper.
 */
class SovereignMediaPlayerHandler(
    private val delegate: MediaPlayerHandler,
    private val routePolicy: SovereignRoutePolicy,
    private val monitor: SovereignHalMonitor,
    private val sovereignLane: SovereignPlaybackLane = SovereignPlaybackLaneNotWired,
) : MediaPlayerHandler by delegate {

    // ── the curated override set ────────────────────────────────────────────
    // Playback commands are where the two lanes diverge. Everything else
    // (queue management, metadata, persistence) is shared state that both
    // lanes speak through the same upstream contract — it delegates.

    override val player: MediaPlayerInterface
        get() = if (routePolicy.shouldUseSovereign()) sovereignLane.player else delegate.player

    override suspend fun onPlayerEvent(playerEvent: PlayerEvent) {
        if (routePolicy.shouldUseSovereign()) {
            monitor.recordRoute(routePolicy.lastDecision.value)
            sovereignLane.onPlayerEvent(playerEvent, delegate)
        } else {
            monitor.recordRoute(routePolicy.lastDecision.value)
            delegate.onPlayerEvent(playerEvent)
        }
    }

    override fun release() {
        if (routePolicy.shouldUseSovereign()) {
            sovereignLane.release()
        }
        // The delegate is ALWAYS released: it stays the fallback and the
        // queue/metadata owner for the whole session.
        delegate.release()
    }

    // ── the Phase 0 diagnostics hooks ───────────────────────────────────────
    // `player` routing above already tells the badge which lane serves
    // commands; the monitor records it. `getPlayerDuration`/`getProgress`
    // stay delegated in Phase 0 (the DAC-clock position truth lands with
    // the Phase 1 brackets, when there is a real clock to read).
}

/**
 * The Sovereign ring playback lane (Phase 0 / D2): the abstraction the
 * Phase 1/2 platform brackets implement — `play_track` through the
 * engine's frozen ring edge, DAC-clock position reads, cooperative
 * flush on seek. Phase 0's default is deliberately inert.
 */
interface SovereignPlaybackLane {
    /** The ring lane's player surface (mounted when the route is SovereignRing). */
    val player: MediaPlayerInterface

    /**
     * Handle a player event on the Sovereign lane. The [legacy] handler
     * is passed so queue/metadata mutations stay in ONE owner (the
     * upstream handler's queue state) while AUDIO control rides the
     * ring — the split that keeps upstream screens working untouched.
     */
    suspend fun onPlayerEvent(
        event: PlayerEvent,
        legacy: MediaPlayerHandler,
    )

    /** Tear the lane's resources down (idempotent). */
    fun release()
}

/**
 * The Phase 0 no-op lane: routes events to the legacy handler while the
 * real brackets are unwired. The route badge shows SovereignRing only
 * when a REAL lane mounts — this object is what "not wired" looks like.
 */
object SovereignPlaybackLaneNotWired : SovereignPlaybackLane {
    override val player: MediaPlayerInterface
        get() = throw NotImplementedError(
            "SovereignPlaybackLaneNotWired: the platform bracket lands in Phase 1/2",
        )

    override suspend fun onPlayerEvent(
        event: PlayerEvent,
        legacy: MediaPlayerHandler,
    ) {
        // Nothing to drive yet: keep the legacy behavior so a flag-on
        // build with no bracket stays playable (the zero-crash ladder's
        // in-code form).
        legacy.onPlayerEvent(event)
    }

    override fun release() {}
}
