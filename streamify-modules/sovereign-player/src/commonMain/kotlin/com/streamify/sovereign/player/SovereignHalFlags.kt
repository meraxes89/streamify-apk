package com.streamify.sovereign.player

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The Sovereign HAL master switch (Sovereign HAL Phase 0 / D2).
 *
 * DEFAULT OFF. The flag flips per platform only after that platform's
 * OS-loopback verification (the `_ci-sovereign-hal.yml` Gate 4 matrix)
 * and the on-device soak pass — exactly the discipline MP-HAL07 set for
 * the C1 dogfood lane ("release builds structurally never install the
 * binding").
 *
 * The runtime value is a [StateFlow] so the developer diagnostics panel
 * can toggle it live and the route decision re-evaluates immediately —
 * no restart, no rebuild. A process-wide default constant carries the
 * shipped value.
 */
object SovereignHalFlags {
    /**
     * The shipped default since Sovereign HAL Phase 3 (Step 4): the
     * Sovereign ring lane is ON — the 5-platform loopback matrix
     * (Gate 4.1-4.5) plus the real-track Fastify E2E parity gates
     * (Gate 4.6) are green in CI. Every platform's legacy engine
     * (media3 / libmpv / HTMLAudioElement) stays wired as the live
     * AudioRoutePolicy fallback (the zero-deletion policy); an
     * unhealthy probe degrades per-command, never a crash. The
     * developer panel keeps this runtime switch for field diagnosis.
     */
    const val DEFAULT_ENABLED: Boolean = true

    private val _enabled = MutableStateFlow(DEFAULT_ENABLED)

    /** Whether the Sovereign ring lane is currently requested. */
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    /** Toggle the Sovereign lane at runtime (developer panel / CI harness). */
    fun setEnabled(value: Boolean) {
        _enabled.value = value
    }
}

/**
 * The one-line route verdict the diagnostics badge renders. Mirrors the
 * zero-crash ladder's decision record (`AudioRoutePolicy.lastDecision`
 * in the legacy tree) so Phase 1's full port keeps vocabulary.
 */
sealed class SovereignRoute {
    /** The flag is off — the delegate serves everything (the shipped default). */
    data object FlagOff : SovereignRoute()

    /** Flag on, hardware probes healthy: commands drive the Sovereign ring lane. */
    data object SovereignRing : SovereignRoute()

    /**
     * Flag on but a probe failed — automatic delegation to the legacy
     * handler (media3 / libmpv / HTMLAudioElement), with the reason
     * recorded for telemetry. NEVER a crash: worst case is the status
     * quo (the MP-HAL07 zero-crash ladder).
     */
    data class LegacyFallback(val reason: String) : SovereignRoute()
}
