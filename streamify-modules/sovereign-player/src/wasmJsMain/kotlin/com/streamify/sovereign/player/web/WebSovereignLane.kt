package com.streamify.sovereign.player.web

import com.maxrave.domain.mediaservice.handler.MediaPlayerHandler
import com.maxrave.domain.mediaservice.handler.PlayerEvent
import com.maxrave.domain.mediaservice.player.MediaPlayerInterface
import com.streamify.sovereign.player.SovereignHalProbe
import com.streamify.sovereign.player.SovereignPlaybackLane
import kotlinx.browser.window

/**
 * Sovereign HAL Phase 2 (Step 4): the BROWSER probe actual.
 *
 * The browser bracket is healthy when AudioContext exists (WebAudio)
 * and at least one transport lane is reachable: AudioWorklet +
 * SharedArrayBuffer (crossOriginIsolated — localhost/HTTPS with the
 * server's COOP/COEP headers) or the AudioBufferSourceNode +
 * MessagePort scheduler (the plain-LAN-HTTP shape, isSecureContext
 * === false). getOutputTimestamp — the DAC clock source — works in
 * BOTH contexts (NOT secure-context-gated).
 *
 * Browsers without WebAudio/WASM (restricted WebViews, iOS screen-lock
 * suspension) degrade to the LegacyFallback arm — the HTMLAudioElement
 * delegate (WebMediaPlayerHandler, the zero-deletion policy).
 */
class WebSovereignHalProbe : SovereignHalProbe {
    private var lastReason: String = "not probed yet"

    override fun isBracketAvailable(): Boolean {
        val hasAudioContext = js("typeof AudioContext !== 'undefined' || typeof webkitAudioContext !== 'undefined'")
        if (hasAudioContext != true) {
            lastReason = "WebAudio unavailable (restricted WebView) — HTMLAudioElement fallback"
            return false
        }
        val isolated: Boolean = window.asDynamic().crossOriginIsolated ?: false
        val secure: Boolean = window.asDynamic().isSecureContext ?: false
        if (!isolated && !secure) {
            // Plain LAN HTTP: lane 2 (AudioBufferSourceNode) still applies.
            lastReason = "plain-HTTP context: lane 2 (AudioBufferSourceNode + pooled MessagePort)"
            return true
        }
        return true
    }

    override fun lastUnavailableReason(): String = lastReason
}

/**
 * Sovereign HAL Phase 2 (Step 4): the BROWSER playback-lane actual.
 *
 * The wasm32 producer (streamify-web) + the dual-transport output
 * bracket run JS-side (the RT pull path is wasm-free by design); this
 * lane is the telemetry + route surface: the active transport label
 * (AudioWorklet SAB / LAN AudioBuffer / HTMLAudio fallback) for the
 * SettingScreen badge, with queue/metadata staying on the legacy
 * HTMLAudioElement handler until the engine mount lands.
 */
class WebSovereignLane(
    private val legacyPlayer: MediaPlayerInterface,
) : SovereignPlaybackLane {

    /** The transport label the SettingScreen badge renders. */
    fun transportLabel(): String {
        val isolated: Boolean = window.asDynamic().crossOriginIsolated ?: false
        val hasWorklet: Boolean = js("typeof AudioWorkletNode !== 'undefined'")
        return when {
            isolated && hasWorklet -> "AudioWorklet SAB"
            hasWorklet -> "LAN AudioBuffer"
            else -> "HTMLAudio Fallback"
        }
    }

    override val player: MediaPlayerInterface
        get() = legacyPlayer

    override suspend fun onPlayerEvent(
        event: PlayerEvent,
        legacy: MediaPlayerHandler,
    ) {
        legacy.onPlayerEvent(event)
    }

    override fun release() {
        // The JS bracket tears itself down with the AudioContext owner.
    }
}
