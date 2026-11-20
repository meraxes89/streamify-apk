package com.streamify.sovereign.player.di

import com.maxrave.domain.mediaservice.handler.MediaPlayerHandler
import com.streamify.sovereign.player.SovereignHalMonitor
import com.streamify.sovereign.player.SovereignHalProbe
import com.streamify.sovereign.player.SovereignHalProbeNotWired
import com.streamify.sovereign.player.SovereignMediaPlayerHandler
import com.streamify.sovereign.player.SovereignPlaybackLane
import com.streamify.sovereign.player.SovereignPlaybackLaneNotWired
import com.streamify.sovereign.player.SovereignRoutePolicy
import org.koin.core.context.GlobalContext
import org.koin.core.context.loadKoinModules
import org.koin.core.module.Module
import org.koin.core.qualifier.Qualifier
import org.koin.core.qualifier.qualifier
import org.koin.dsl.module

/** Koin qualifier for the wrapped legacy handler (the delegate under the wrapper). */
val LegacyMediaPlayerHandler: Qualifier = qualifier("LegacyMediaPlayerHandler")

/**
 * The Sovereign HAL Koin registration (Phase 0 / D2).
 *
 * `sovereignPlayerModule` registers the WRAPPER and its dependencies —
 * it deliberately does NOT bind `MediaPlayerHandler` itself. Binding
 * happens in [installSovereignMediaPlayerHandler], which must run AFTER
 * the platform's default handler module (media3's `mediaHandlerModule`
 * on Android, `loadDesktopPlayerModule()` on JVM, `webAppModule` on
 * wasmJs) so the delegate exists to capture. Call it from the same DI
 * entrypoints that already load the streamify module suite
 * (`SimpMusicApplication.onCreate`, `DesktopApp`, `initWebKoin`).
 *
 * The install is FLAG-OFF by default: the wrapper delegates 1:1 until
 * `SovereignHalFlags.setEnabled(true)` (developer panel / soak build).
 */
val sovereignPlayerModule: Module = module {
    single<SovereignHalProbe> { SovereignHalProbeNotWired }
    single<SovereignHalMonitor> { SovereignHalMonitor() }
    single<SovereignRoutePolicy> { SovereignRoutePolicy(get()) }
    single<SovereignPlaybackLane> { SovereignPlaybackLaneNotWired }
    single<SovereignMediaPlayerHandler> {
        SovereignMediaPlayerHandler(
            delegate = get(qualifier = LegacyMediaPlayerHandler),
            routePolicy = get(),
            monitor = get(),
            sovereignLane = get(),
        )
    }
}

/**
 * Wrap the platform's registered [MediaPlayerHandler] with the Sovereign
 * wrapper and rebind the interface to it.
 *
 * Mechanics: the CURRENT binding (the platform default — media3 /
 * libmpv / HTMLAudioElement) is resolved and re-declared under
 * [LegacyMediaPlayerHandler]; the interface then re-binds to the
 * wrapper. Idempotent: a second call re-wraps only the ORIGINAL
 * delegate (never a wrapper), so re-installing cannot stack wrappers.
 *
 * This is the ONLY function outside core that touches the
 * `MediaPlayerHandler` Koin binding — the single canonical injection
 * boundary the Phase 0 plan freezes.
 */
fun installSovereignMediaPlayerHandler() {
    val koin = GlobalContext.get()
    loadKoinModules(sovereignPlayerModule)

    val current: MediaPlayerHandler = koin.get()
    // Idempotency: if a wrapper is already bound, its delegate is the
    // original — keep it (unwrapping is unnecessary; redeclare is a
    // no-op then).
    val original = if (current is SovereignMediaPlayerHandler) current else null
    if (original != null) {
        return // already installed
    }

    // 1. Preserve the platform default under the legacy qualifier.
    loadKoinModules(
        module(createdAtStart = false) {
            single(qualifier = LegacyMediaPlayerHandler) { current }
        },
    )
    // 2. Rebind the interface to the wrapper (declared AFTER, wins).
    loadKoinModules(
        module(createdAtStart = false) {
            single<MediaPlayerHandler> { get<SovereignMediaPlayerHandler>() }
        },
    )
}
