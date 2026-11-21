package com.streamify.sovereign.player.android.di

import com.maxrave.domain.mediaservice.player.MediaPlayerInterface
import com.streamify.sovereign.player.SovereignHalProbe
import com.streamify.sovereign.player.SovereignPlaybackLane
import com.streamify.sovereign.player.android.AndroidSovereignHalProbe
import com.streamify.sovereign.player.android.AndroidSovereignLane
import org.koin.dsl.module
import org.koin.core.context.loadKoinModules

/**
 * Sovereign HAL Phase 2 (Step 1): the ANDROID platform module.
 *
 * Loads AFTER `mediaHandlerModule` bound the legacy media3 handler (the
 * app entry's `SimpMusicApplication.onCreate` ordering — the same
 * discipline `loadDesktopSovereignModule` holds on the JVM). Binds the
 * Oboe probe + lane actuals; `installSovereignMediaPlayerHandler()`
 * then captures the current binding as the delegate and rebinds the
 * interface to the wrapper (flag-off = 1:1 delegation, the shipped
 * default; media3 stays intact as the automatic fallback).
 */
fun loadAndroidSovereignModule(legacyPlayer: () -> MediaPlayerInterface) {
    loadKoinModules(
        module {
            single<SovereignHalProbe> { AndroidSovereignHalProbe() }
            single<SovereignPlaybackLane> {
                AndroidSovereignLane(legacyPlayer())
            }
        },
    )
}
