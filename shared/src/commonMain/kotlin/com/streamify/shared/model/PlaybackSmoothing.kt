package com.streamify.shared.model

/**
 * Phase 5 (MP-UI03 / Beta): presentation-layer playhead smoothing over the
 * hardware DAC presentation clock.
 *
 * WHY THIS EXISTS: the engine pushes coalesced snapshots on change
 * (MP-DEC-04) whose `positionMs` derives STRICTLY from the hardware DAC
 * frame counter (MP-DEC-03 — the hardware HAL callback is the sole clock
 * master; no wall-clock interpolation ever enters the derivation). Those
 * hardware-derived samples can still carry small presentation jitter
 * (ring-buffer batching, underrun recovery, HAL re-anchors after route
 * changes), which a raw renderer would show as a stuttering or briefly
 * backwards playhead.
 *
 * DOCTRINE-COMPILANT SMOOTHING: an exponential moving average was evaluated
 * and REJECTED — an EMA's steady-state lag would make the rendered position
 * trail the hardware truth (an audiophile client must not misrepresent
 * where the DAC actually is). This smoother instead passes every credible
 * hardware position through EXACTLY and only suppresses non-credible
 * transitions:
 *
 *  * BOOTSTRAP — the first sample snaps (no filter to warm up);
 *  * FORWARD — every forward delta up to [seekSnapThresholdMs] advances the
 *    playhead verbatim (the hardware value IS the position);
 *  * BACKWARD WHILE PLAYING — deltas within [jitterToleranceMs] are held
 *    (jitter suppression: a playing DAC counter never legitimately goes
 *    backwards); larger backward deltas snap (a legitimate seek-back);
 *  * BACKWARD WHILE NOT PLAYING — accepted verbatim (a paused DAC counter
 *    re-anchoring after a route change is bookkeeping, not motion);
 *  * SEEK SNAP — any delta beyond [seekSnapThresholdMs] snaps in both
 *    directions (user seek / track change bypass the filter entirely);
 *  * NULL — a snapshot without a clock sample changes nothing (the host
 *    keeps rendering the last credible position; absence is never
 *    fabricated into a value).
 *
 * Pure Kotlin over value types — JVM-unit-testable on both compile seams
 * (desktop KGP jvm and Android AGP) with zero mocks.
 */
class DacPositionSmoother(
    /** Backward deltas at or below this magnitude are jitter while playing. */
    private val jitterToleranceMs: Long = DEFAULT_JITTER_TOLERANCE_MS,
    /** Deltas beyond this magnitude are seeks / track changes — snap. */
    private val seekSnapThresholdMs: Long = DEFAULT_SEEK_SNAP_THRESHOLD_MS,
) {

    /** The currently rendered position; null before the first credible sample. */
    var rendered: Long? = null
        private set

    /**
     * Offers one DAC-derived position and returns the position the UI should
     * render (see class doc for the full transition table). `null` offers
     * leave [rendered] untouched and echo it back.
     */
    fun offer(positionMs: Long?, isPlaying: Boolean): Long? {
        if (positionMs == null) return rendered
        val current = rendered
        if (current == null) {
            rendered = positionMs
            return rendered
        }

        val delta = positionMs - current
        rendered = when {
            // Seek / track change magnitude: bypass the filter — the
            // playhead teleports, it does not glide.
            delta > seekSnapThresholdMs || delta < -seekSnapThresholdMs -> positionMs

            // Forward motion (and exact repeats): the hardware value IS the
            // position — pass through verbatim.
            delta >= 0L -> positionMs

            // Small backward step while playing: DAC jitter — hold the last
            // credible position until the counter catches back up.
            isPlaying && -delta <= jitterToleranceMs -> current

            // Backward while paused/stopped, or backward beyond tolerance
            // while playing (covered by the snap arm above): bookkeeping
            // re-anchor — accept verbatim.
            else -> positionMs
        }
        return rendered
    }

    /**
     * Drops all filter state; the next [offer] snaps unconditionally. Hosts
     * call this on engine re-initialization or explicit session teardown.
     */
    fun reset() {
        rendered = null
    }

    companion object {
        /** Ring/HAL jitter beyond ~400 ms is a re-anchor, not jitter. */
        const val DEFAULT_JITTER_TOLERANCE_MS: Long = 400L

        /** Playhead jumps beyond ~2 s are seeks, not playback motion. */
        const val DEFAULT_SEEK_SNAP_THRESHOLD_MS: Long = 2_000L
    }
}
