package com.streamify.sovereign.player

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The route decision engine (Sovereign HAL Phase 0 / D2) — the port of
 * the legacy `AudioRoutePolicy` zero-crash ladder to the KMP tree:
 *
 *   flag off  →  [SovereignRoute.FlagOff]           (delegate everything)
 *   flag on   →  probe healthy  →  [SovereignRoute.SovereignRing]
 *   flag on   →  probe unhealthy → [SovereignRoute.LegacyFallback] (reason recorded)
 *
 * The ladder is PURE over its inputs (flag + probe) so its behavior is
 * unit-testable without a platform, and `decide()` is re-evaluated on
 * every command (a bracket that dies mid-session degrades on the NEXT
 * command — the zero-crash guarantee's runtime half).
 */
class SovereignRoutePolicy(
    private val probe: SovereignHalProbe,
) {
    private val _lastDecision = MutableStateFlow<SovereignRoute>(SovereignRoute.FlagOff)

    /** The live route verdict (the diagnostics badge + telemetry read this). */
    val lastDecision: StateFlow<SovereignRoute> = _lastDecision.asStateFlow()

    /** Evaluate the ladder NOW and record the decision. */
    fun decide(): SovereignRoute {
        val decision = when {
            !SovereignHalFlags.enabled.value -> SovereignRoute.FlagOff
            probe.isBracketAvailable() -> SovereignRoute.SovereignRing
            else -> SovereignRoute.LegacyFallback(probe.lastUnavailableReason())
        }
        _lastDecision.value = decision
        return decision
    }

    /** True when the next command should drive the Sovereign ring lane. */
    fun shouldUseSovereign(): Boolean = decide() == SovereignRoute.SovereignRing
}
