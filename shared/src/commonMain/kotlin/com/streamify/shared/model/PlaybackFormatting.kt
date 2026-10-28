package com.streamify.shared.model

import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Phase 5 (MP-UI03 / Beta): pure playback formatting + derivation math.
 *
 * Total functions over value types — JVM-unit-testable with zero mocks on
 * BOTH compile seams (desktop KGP jvm and Android AGP), and consumed by the
 * shared components so no platform re-implements them.
 */

/**
 * Formats a millisecond position as a playback timestamp:
 * `m:ss` under one hour, `h:mm:ss` from one hour up. Negative and null-ish
 * inputs clamp to zero — a position renderer must never show garbage for a
 * missing clock sample.
 */
fun formatPlaybackTime(positionMs: Long): String {
    val totalSeconds = (positionMs.coerceAtLeast(0L) + 500L) / 1000L
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}

/**
 * Track progress fraction in [0.0, 1.0]; null when either endpoint is
 * unknown (no DAC clock sample yet, or no track duration) — callers render
 * an indeterminate indicator instead of a fabricated value.
 */
fun progressFraction(positionMs: Long?, durationMs: Long?): Float? {
    if (positionMs == null || durationMs == null || durationMs <= 0L) return null
    return (positionMs.coerceIn(0L, durationMs).toDouble() / durationMs.toDouble())
        .toFloat()
        .coerceIn(0f, 1f)
}

/**
 * Derives a playback position in milliseconds from one hardware DAC
 * presentation clock sample (MP-DEC-03: the hardware HAL callback is the
 * SOLE clock master — the position is the DAC's own frame counter divided
 * by the DAC's own nominal rate; no wall clock participates).
 *
 * Returns null when the sample's rate is outside the supported DAC range
 * (the engine validates the same range on `record_dac_clock`).
 */
fun positionMsFromDacClock(clock: DacClockSample): Long? {
    if (clock.sampleRateHz !in SUPPORTED_DAC_RATE_HZ) return null
    if (clock.framePosition < 0L) return null
    // frame_count / (frames per second) -> seconds; scale to ms in double
    // precision to keep sub-ms DAC accuracy, then round to whole ms.
    return (clock.framePosition.toDouble() / clock.sampleRateHz * 1000.0)
        .roundToLong()
}

/**
 * DAC drift between two successive hardware clock samples, in parts per
 * million (positive = the device DAC presented MORE frames than its
 * nominal rate predicts for the observed monotonic interval).
 *
 * Pure math over value triples — the same estimation the dogfood telemetry
 * surfaces (MP-CUT01). Returns null when the pair is not a usable
 * measurement (non-monotonic order, zero elapsed interval, unsupported
 * rates, or frame positions going backwards).
 */
fun dacDriftPpm(older: DacClockSample, newer: DacClockSample): Float? {
    if (older.sampleRateHz !in SUPPORTED_DAC_RATE_HZ) return null
    if (newer.sampleRateHz !in SUPPORTED_DAC_RATE_HZ) return null
    if (older.sampleRateHz != newer.sampleRateHz) return null
    val elapsedNs = newer.monotonicNs - older.monotonicNs
    if (elapsedNs <= 0L) return null
    val presentedFrames = newer.framePosition - older.framePosition
    if (presentedFrames < 0L) return null

    val expectedFrames =
        elapsedNs.toDouble() * older.sampleRateHz / 1_000_000_000.0
    if (abs(expectedFrames) < 1.0) return null
    val driftPpm = (presentedFrames - expectedFrames) / expectedFrames * 1_000_000.0
    return driftPpm.toFloat()
}

/** DAC nominal rates the engine accepts for `record_dac_clock`. */
private val SUPPORTED_DAC_RATE_HZ = 8_000..768_000
