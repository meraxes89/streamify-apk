package com.streamify.app.data.uniffi

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * PlaybackPositionCalculatorTest (Phase 3, MP-C01/MP-DEC-03 — directive §D) —
 * pure-math proof of the interpolation-free position derivation over
 * [DacPresentationClock]:
 *
 *  • exact frame→millisecond conversion against the canonical 48kHz DSP
 *    rate and real-world device rates;
 *  • zero/absent sample rate never divides by zero and never fabricates
 *    progress;
 *  • u64-extreme frame counters saturate at [Long.MAX_VALUE] instead of
 *    wrapping into a negative lie;
 *  • seekbar fractions clamp into `[0, 1]` and stay 0 for unknown
 *    durations.
 *
 * Pure functions over value types: zero mocks, zero framework, zero
 * flakiness — the jvm-unit-suite shard's favorite kind of test.
 */
class PlaybackPositionCalculatorTest {

    private fun clock(frames: ULong, rate: UInt, monotonicNs: ULong = 0uL) =
        DacPresentationClock(
            dacFramePosition = frames,
            sampleRate = rate,
            monotonicNs = monotonicNs,
        )

    @Test
    fun `canonical 48kHz clock converts frames to milliseconds exactly`() {
        // One second of audio at the canonical internal DSP rate.
        assertEquals(1_000L, positionMs(clock(frames = 48_000uL, rate = 48_000u)))
        // Half a second.
        assertEquals(500L, positionMs(clock(frames = 24_000uL, rate = 48_000u)))
        // Ten minutes.
        assertEquals(600_000L, positionMs(clock(frames = 28_800_000uL, rate = 48_000u)))
        // Integer division floors sub-millisecond frames to 0.
        assertEquals(0L, positionMs(clock(frames = 1uL, rate = 48_000u)))
    }

    @Test
    fun `real-world device rates convert exactly`() {
        // 44.1kHz legacy streams, 96kHz and 192kHz audiophile rates — the
        // divisor is the DEVICE DAC rate, not the canonical internal one.
        assertEquals(1_000L, positionMs(clock(frames = 44_100uL, rate = 44_100u)))
        assertEquals(1_000L, positionMs(clock(frames = 96_000uL, rate = 96_000u)))
        assertEquals(1_000L, positionMs(clock(frames = 192_000uL, rate = 192_000u)))
        // Cross-rate: 96k frames replayed on a 48k device = 2s.
        assertEquals(2_000L, positionMs(clock(frames = 96_000uL, rate = 48_000u)))
    }

    @Test
    fun `zero sample rate yields zero position - no division by zero`() {
        assertEquals(0L, positionMs(clock(frames = 480_000uL, rate = 0u)))
    }

    @Test
    fun `u64-extreme frame counters saturate instead of wrapping negative`() {
        assertEquals(Long.MAX_VALUE, positionMs(clock(frames = ULong.MAX_VALUE, rate = 1u)))
        // Past the pre-product guard threshold (~1.8e16 frames).
        assertEquals(
            Long.MAX_VALUE,
            positionMs(clock(frames = ULong.MAX_VALUE / 1000uL + 1uL, rate = 48_000u))
        )
    }

    @Test
    fun `fraction clamps into 0-1 and stays 0 for unknown duration`() {
        // 24_000 frames @ 48kHz = 500ms — halfway through a 1s track.
        val midTrack = clock(frames = 24_000uL, rate = 48_000u)

        assertEquals(0.5f, fraction(midTrack, durationMs = 1_000L), 0.0001f)
        // Unknown duration: no fabricated progress.
        assertEquals(0f, fraction(midTrack, durationMs = 0L), 0.0001f)
        // Clock momentarily past the nominal duration (final drain frames
        // of a 1s track): the seekbar never overshoots.
        val pastEnd = clock(frames = 50_000uL, rate = 48_000u)
        assertEquals(1f, fraction(pastEnd, durationMs = 1_000L), 0.0001f)
    }

    private fun positionMs(clock: DacPresentationClock): Long =
        PlaybackPositionCalculator.calculatePlaybackPositionMs(clock)

    private fun fraction(clock: DacPresentationClock, durationMs: Long): Float =
        PlaybackPositionCalculator.positionFraction(clock, durationMs)
}
