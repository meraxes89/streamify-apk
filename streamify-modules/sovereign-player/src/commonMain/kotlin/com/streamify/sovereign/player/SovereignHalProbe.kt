package com.streamify.sovereign.player

/**
 * The platform hardware-probe surface (Sovereign HAL Phase 0 / D2).
 *
 * Phase 0 ships the PROBE CONTRACT and the ladder; Phase 1/2 wire the
 * real brackets:
 *
 *  * Android — `RustAudioSinkJni.probeEdges()` (the 7 frozen symbols'
 *    runCatching probe, already main-tree via C1) + the AAudio route.
 *  * Desktop — `DesktopHalAudioBridge.initHal()` (JNA soft-probe, the
 *    C5 platform slice) per OS facade (WASAPI / PipeWire / CoreAudio).
 *  * Browser — AudioWorklet + SharedArrayBuffer/MessagePort capability
 *    probe (Phase 2's dual-mode bracket).
 *
 * The probe is the FIRST rung of the zero-crash ladder: any `false`
 * degrades to [SovereignRoute.LegacyFallback] with the reason — the
 * user's playback continues through the legacy chain, byte-identical to
 * the shipped default.
 */
interface SovereignHalProbe {
    /** True when this platform's Sovereign bracket is present and healthy. */
    fun isBracketAvailable(): Boolean

    /** Human-readable reason for the last unhealthy probe (telemetry). */
    fun lastUnavailableReason(): String
}

/**
 * The no-op probe every platform actual starts from: the bracket is
 * "not yet wired" → the ladder's answer is always LegacyFallback. The
 * Phase 1/2 platform PRs replace this binding with the real probe; the
 * handler's routing logic (and its unit tests) stay untouched.
 */
object SovereignHalProbeNotWired : SovereignHalProbe {
    override fun isBracketAvailable(): Boolean = false

    override fun lastUnavailableReason(): String =
        "Sovereign HAL Phase 0: no platform bracket wired yet (expected until Phase 1/2)"
}
