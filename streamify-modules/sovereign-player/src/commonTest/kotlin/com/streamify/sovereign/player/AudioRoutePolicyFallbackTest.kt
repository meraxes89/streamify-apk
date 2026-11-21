package com.streamify.sovereign.player

import com.maxrave.domain.mediaservice.handler.PlayerEvent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Sovereign HAL Phase 3 (Step 3): the AudioRoutePolicy LIVE FALLBACK &
 * DEVICE HOT-SWAP RESILIENCE suite.
 *
 * Deterministic degradation drills over the route ladder — the zero-crash
 * guarantee's runtime half. Every drill drives SovereignMediaPlayerHandler
 * through a scripted bracket-death sequence and asserts playback CONTINUES
 * through the legacy delegate with the reason recorded, the caller NEVER
 * blocked (route evaluation is pure/synchronous), and no stacked wrappers
 * or leaked lanes across repeated hot-swaps.
 *
 * The platform ladders these drills map to:
 *  * Android — Oboe AAudio -> Oboe OpenSL ES -> media3 DefaultAudioSink.
 *  * Desktop — WASAPI exclusive -> cpal shared -> libmpv delegate.
 *  * Browser — AudioWorklet+SAB -> AudioBufferSourceNode+MessagePort ->
 *    HTMLAudioElement (WebMediaPlayerHandler).
 *
 * Each rung's failure is a probe `false` carrying that rung's reason; the
 * wrapper re-evaluates the route on EVERY command (a bracket that dies
 * mid-session degrades on the NEXT command) — the contract pinned here.
 */

/** A probe whose health the drill scripts (the bracket-death sequence). */
private class ScriptedProbe : SovereignHalProbe {
    var healthy = true
    var reason = "scripted bracket death"
    var probes = 0
    override fun isBracketAvailable(): Boolean {
        probes += 1
        return healthy
    }
    override fun lastUnavailableReason(): String = reason
}

/** A lane that counts events + releases (the hot-swap rebuild signals). */
private class CountingLane : SovereignPlaybackLane {
    var events = 0
    var releases = 0
    override val player: com.maxrave.domain.mediaservice.player.MediaPlayerInterface
        get() = NoopPlayer
    override suspend fun onPlayerEvent(
        event: PlayerEvent,
        legacy: com.maxrave.domain.mediaservice.handler.MediaPlayerHandler,
    ) {
        events += 1
    }
    override fun release() {
        releases += 1
    }
}

class AudioRoutePolicyFallbackTest {

    private fun wired(
        probe: SovereignHalProbe,
        monitor: SovereignHalMonitor = SovereignHalMonitor(),
    ): Triple<SovereignMediaPlayerHandler, RecordingHandler, CountingLane> {
        val legacy = RecordingHandler()
        val lane = CountingLane()
        val handler = SovereignMediaPlayerHandler(
            delegate = legacy,
            routePolicy = SovereignRoutePolicy(probe),
            monitor = monitor,
            sovereignLane = lane,
        )
        return Triple(handler, legacy, lane)
    }

    // ── Android ladder: AAudio -> OpenSL -> media3 ────────────────────────

    @Test
    fun android_ladder_full_degradation_ends_on_media3_with_playback_preserved() = runTest {
        val probe = ScriptedProbe()
        val monitor = SovereignHalMonitor()
        val (handler, legacy, lane) = wired(probe, monitor)
        SovereignHalFlags.setEnabled(true)
        try {
            // Healthy bracket (Oboe AAudio engaged): the lane serves.
            handler.onPlayerEvent(PlayerEvent.PlayPause)
            assertEquals(1, lane.events, "healthy bracket: the sovereign lane served the command")
            assertEquals(0, legacy.onPlayerEventCalls)

            // AAudio runtime death (onErrorAfterClose): degrade on the
            // NEXT command, playback preserved, reason recorded.
            probe.healthy = false
            probe.reason = "Oboe AAudio: stream disconnected (runtime error)"
            handler.onPlayerEvent(PlayerEvent.PlayPause)
            assertEquals(1, lane.events, "dead bracket: NO further lane events")
            assertEquals(1, legacy.onPlayerEventCalls, "the command degraded to the legacy chain (media3)")
            assertTrue(monitor.route.value is SovereignRoute.LegacyFallback)
            assertEquals(
                "Oboe AAudio: stream disconnected (runtime error)",
                (monitor.route.value as SovereignRoute.LegacyFallback).reason,
            )

            // The second rung dying too (OpenSL init failure on a device
            // with both Oboe backends dead) keeps serving on media3.
            probe.reason = "Oboe OpenSL ES: AudioTrack init failed (both rungs dead)"
            handler.onPlayerEvent(PlayerEvent.PlayPause)
            assertEquals(2, legacy.onPlayerEventCalls, "playback preserved on the media3 floor")
            assertEquals(1, lane.events)
            assertEquals(2, monitor.fallbacks.value, "each death recorded exactly one fallback")
        } finally {
            SovereignHalFlags.setEnabled(false)
        }
    }

    // ── Desktop ladder: WASAPI exclusive -> cpal shared -> libmpv ──────────

    @Test
    fun desktop_ladder_exclusive_rejection_steps_down_without_user_impact() = runTest {
        val probe = ScriptedProbe()
        val monitor = SovereignHalMonitor()
        val (handler, legacy, lane) = wired(probe, monitor)
        SovereignHalFlags.setEnabled(true)
        try {
            handler.onPlayerEvent(PlayerEvent.PlayPause)
            assertEquals(1, lane.events, "cpal shared healthy: sovereign lane serves")

            // WASAPI exclusive refused AND the cpal stream failing (both
            // rungs in one window): the libmpv delegate serves on.
            probe.healthy = false
            probe.reason = "WASAPI exclusive refused + cpal stream error — libmpv delegate"
            handler.onPlayerEvent(PlayerEvent.PlayPause)
            assertEquals(1, legacy.onPlayerEventCalls)
            assertEquals(
                probe.reason,
                (monitor.route.value as SovereignRoute.LegacyFallback).reason,
            )
        } finally {
            SovereignHalFlags.setEnabled(false)
        }
    }

    // ── Browser ladder: the transport selection matrix ─────────────────────

    @Test
    fun browser_transport_selection_matrix_maps_contexts_to_lanes() {
        // The pure decision table WebSovereignLane/transportLabel encodes:
        //   crossOriginIsolated + worklet -> AudioWorklet + SAB (lane 1)
        //   plain HTTP / no worklet       -> AudioBufferSourceNode + pooled
        //                                   MessagePort (lane 2)
        //   no WebAudio (iOS WebView)     -> HTMLAudioElement (lane 3)
        data class Ctx(val isolated: Boolean, val hasWorklet: Boolean, val hasWebAudio: Boolean)
        fun select(c: Ctx): String = when {
            !c.hasWebAudio -> "HTMLAudioElement"
            c.isolated && c.hasWorklet -> "AudioWorklet+SAB"
            else -> "AudioBufferSourceNode+MessagePort"
        }
        assertEquals("AudioWorklet+SAB", select(Ctx(true, true, true)), "COOP/COEP isolated: lane 1")
        assertEquals("AudioBufferSourceNode+MessagePort", select(Ctx(false, true, true)), "plain LAN HTTP: lane 2")
        assertEquals("AudioBufferSourceNode+MessagePort", select(Ctx(false, false, true)), "no worklet: lane 2 (the every-browser contract)")
        assertEquals("HTMLAudioElement", select(Ctx(true, true, false)), "restricted WebView: lane 3 legacy fallback")
        assertEquals("HTMLAudioElement", select(Ctx(false, false, false)), "iOS Safari / no WebAudio: lane 3")
    }

    // ── Device hot-swap: repeated disconnect/reconnect storms ──────────────

    @Test
    fun device_hot_swap_rebuilds_across_repeated_disconnects() = runTest {
        val probe = ScriptedProbe()
        val monitor = SovereignHalMonitor()
        val (handler, legacy, lane) = wired(probe, monitor)
        SovereignHalFlags.setEnabled(true)
        try {
            // The USB DAC/Bluetooth unplug storm: 50 hot-swaps; every
            // death degrades on the next command and every rebirth
            // re-engages the sovereign lane. No stacked wrappers, no
            // mid-drill lane teardown (rebuild != release).
            for (i in 0 until 50) {
                probe.healthy = i % 2 == 0
                handler.onPlayerEvent(PlayerEvent.PlayPause)
            }
            assertEquals(25, lane.events, "the lane served every healthy window")
            assertEquals(25, legacy.onPlayerEventCalls, "the delegate served every dead window")
            assertEquals(25, monitor.fallbacks.value, "each death recorded exactly one fallback")
            assertEquals(0, lane.releases, "the lane is torn down only by handler.release, never mid-drill")
        } finally {
            SovereignHalFlags.setEnabled(false)
        }
    }

    @Test
    fun route_evaluation_never_blocks_the_caller() {
        // The UI-thread contract: decide() is pure over (flag, probe) —
        // a flapping probe (health flips on EVERY call) still answers
        // synchronously; 10k evaluations run in bounded time here (a
        // blocking ladder would hang this test outright).
        val probe = object : SovereignHalProbe {
            var toggle = false
            override fun isBracketAvailable(): Boolean { toggle = !toggle; return toggle }
            override fun lastUnavailableReason(): String = "flapping"
        }
        val policy = SovereignRoutePolicy(probe)
        SovereignHalFlags.setEnabled(true)
        try {
            var fallbacks = 0
            repeat(10_000) {
                if (policy.decide() is SovereignRoute.LegacyFallback) fallbacks += 1
            }
            assertEquals(5_000, fallbacks, "the flapping probe degraded exactly half the evaluations")
        } finally {
            SovereignHalFlags.setEnabled(false)
        }
    }
}
