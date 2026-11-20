package com.streamify.sovereign.player.desktop.di

import com.maxrave.domain.mediaservice.player.MediaPlayerInterface
import com.streamify.sovereign.player.SovereignHalProbe
import com.streamify.sovereign.player.SovereignPlaybackLane
import com.streamify.sovereign.player.desktop.DesktopSovereignHalProbe
import com.streamify.sovereign.player.desktop.DesktopSovereignLane
import org.koin.core.context.loadKoinModules
import org.koin.core.qualifier.StringQualifier
import org.koin.dsl.module

/**
 * Sovereign HAL Phase 1 (D4): the DESKTOP actuals of the Sovereign
 * Koin bindings.
 *
 * Phase 0 shipped `sovereignPlayerModule` with `SovereignHalProbeNotWired`
 * / `SovereignPlaybackLaneNotWired`. This loader OVERLAYS the desktop
 * actuals — probe, lane — on top (Koin's last-registration-wins), so the
 * platform wiring stays additive: `installSovereignMediaPlayerHandler`
 * captures the delegate exactly as before; only the two Sovereign
 * bindings gain real bodies on JVM.
 *
 * `USE_SOVEREIGN_HAL` stays developer-flag-gated
 * ([com.streamify.sovereign.player.SovereignHalFlags]); libmpv remains
 * the automatic fallback inside [com.streamify.sovereign.player.SovereignRoutePolicy]
 * whenever the probe is unhealthy (the zero-deletion policy, until the
 * Phase 3 loopback matrix completes).
 *
 * Load AFTER `sovereignPlayerModule` (the same entrypoints that already
 * call it: `DesktopApp`'s Koin init — via `loadDesktopPlayerModule()`'s
 * caller — right after `loadMediaService()`).
 */
fun loadDesktopSovereignModule(legacyPlayer: () -> MediaPlayerInterface) {
    loadKoinModules(
        module {
            single<SovereignHalProbe> { DesktopSovereignHalProbe() }
            single<SovereignPlaybackLane> {
                DesktopSovereignLane(legacyPlayer())
            }
        },
    )
}

/** The qualifier string mirroring the common module's legacy handler tag. */
val LegacyMediaPlayerHandlerQualifier = StringQualifier("LegacyMediaPlayerHandler")
