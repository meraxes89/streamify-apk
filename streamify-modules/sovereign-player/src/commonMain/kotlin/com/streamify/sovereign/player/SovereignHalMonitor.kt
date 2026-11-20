package com.streamify.sovereign.player

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The observable telemetry surface of the Sovereign lane (Phase 0 / D2).
 *
 * Phase 0 exposes the STATE MODEL the dogfood diagnostics panel renders
 * (the KMP sibling of the legacy `RustAudioDogfoodPanel`'s fields —
 * route, pipeline liveness, frames pulled, underruns). Phase 1/2 feed
 * it from the live ring stats wire (the frozen 96-byte image through
 * `NativeAudioSink`/`DesktopHalAudioBridge`); until then the counters
 * stay at their Phase 0 zeros and the badge tells the truth.
 */
class SovereignHalMonitor {
    /** Live route verdict mirror (owned by the policy; mirrored for Compose). */
    private val _route = MutableStateFlow<SovereignRoute>(SovereignRoute.FlagOff)
    val route: StateFlow<SovereignRoute> = _route.asStateFlow()

    /** Frames pulled through the Sovereign ring (0 until a bracket lands). */
    private val _framesPulled = MutableStateFlow(0L)
    val framesPulled: StateFlow<Long> = _framesPulled.asStateFlow()

    /** Underrun events reported by the ring (0 until a bracket lands). */
    private val _underruns = MutableStateFlow(0L)
    val underruns: StateFlow<Long> = _underruns.asStateFlow()

    /** Legacy fallbacks observed this session (count + last reason). */
    private val _fallbacks = MutableStateFlow(0)
    private val _lastFallbackReason = MutableStateFlow<String?>(null)
    val fallbacks: StateFlow<Int> = _fallbacks.asStateFlow()
    val lastFallbackReason: StateFlow<String?> = _lastFallbackReason.asStateFlow()

    /** Record a route decision (called by the handler on every command). */
    fun recordRoute(route: SovereignRoute) {
        _route.value = route
        if (route is SovereignRoute.LegacyFallback) {
            _fallbacks.value += 1
            _lastFallbackReason.value = route.reason
        }
    }

    /** Ring telemetry ingestion (Phase 1/2 brackets call this from the stats wire). */
    fun recordRingTelemetry(framesPulled: Long, underruns: Long) {
        _framesPulled.value = framesPulled
        _underruns.value = underruns
    }
}
