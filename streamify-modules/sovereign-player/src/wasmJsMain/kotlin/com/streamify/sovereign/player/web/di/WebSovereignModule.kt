package com.streamify.sovereign.player.web.di

import com.maxrave.domain.mediaservice.player.MediaPlayerInterface
import com.streamify.sovereign.player.SovereignHalProbe
import com.streamify.sovereign.player.SovereignPlaybackLane
import com.streamify.sovereign.player.web.WebSovereignHalProbe
import com.streamify.sovereign.player.web.WebSovereignLane
import org.koin.core.context.loadKoinModules
import org.koin.dsl.module

/**
 * Sovereign HAL Phase 2 (Step 4): the wasmJs platform module. Loads
 * AFTER webAppModule bound the legacy HTMLAudioElement handler; the
 * wrapper install captures it as the delegate (flag-off = 1:1
 * delegation — HTMLAudioElement stays the automatic fallback).
 */
fun loadWebSovereignModule(legacyPlayer: () -> MediaPlayerInterface) {
    loadKoinModules(
        module {
            single<SovereignHalProbe> { WebSovereignHalProbe() }
            single<SovereignPlaybackLane> { WebSovereignLane(legacyPlayer()) }
        },
    )
}
