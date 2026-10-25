package com.streamify.app.ui.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * HAPTIC FEEDBACK MANAGER TESTS (pure JVM)
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * Verifies the Phase 5 tactile engine's contract: pattern waveforms (with
 * the exact 80ms heartbeat spacing and the rising Jam-upvote envelope),
 * scrub micro-tick bucketing (every 5s of scrubbed audio), 10% volume
 * steps, and — critically — trigger throttling so a 120Hz gesture stream
 * can never queue a vibrator effect storm.
 */
class HapticFeedbackManagerTest {

    // ── Test seams ─────────────────────────────────────────────────────────

    private class RecordingActuator : HapticActuator {
        override var hasVibrator: Boolean = true
        val fired = mutableListOf<HapticWaveform>()
        override fun vibrate(waveform: HapticWaveform) {
            fired += waveform
        }
    }

    private class ManualClock(var time: Long = 0L) : HapticClock {
        override fun now(): Long = time
    }

    private fun manager(
        actuator: HapticActuator = RecordingActuator(),
        clock: ManualClock = ManualClock()
    ): HapticFeedbackManager = HapticFeedbackManager(actuator, clock)

    // ── Waveform shape contracts ───────────────────────────────────────────

    @Test
    fun `play pause click uses the predefined click effect`() {
        val m = manager()
        val wf = m.buildPlayPauseClick()
        assertNotNull(wf.predefinedEffect)
        assertEquals(HapticFeedbackManager.EFFECT_CLICK, wf.predefinedEffect)
    }

    @Test
    fun `like heartbeat is a single waveform with exactly 80ms gap`() {
        val wf = manager().buildLikeHeartbeat()
        // Platform-native: [on=15, off=80, on=25]
        assertTrue(wf.timings.contentEquals(longArrayOf(15L, 80L, 25L)))
        assertTrue(wf.amplitudes!!.contentEquals(intArrayOf(255, 0, 190)))
        assertNull(wf.predefinedEffect)
    }

    @Test
    fun `jam upvote rising envelope ascends in amplitude and shrinks in duration`() {
        val wf = manager().buildJamUpvoteRising()
        val amps = wf.amplitudes!!
        // ON segments sit at even indices
        val onAmps = amps.filterIndexed { i, _ -> i % 2 == 0 }
        val onDurs = wf.timings.filterIndexed { i, _ -> i % 2 == 0 }
        assertEquals(4, onAmps.size)
        assertEquals(onAmps.size, onAmps.distinct().size)              // strictly distinct
        assertEquals(onAmps, onAmps.sorted())                          // ascending
        assertEquals(onDurs, onDurs.sortedDescending())                // shrinking pulses
        assertEquals(60, onAmps.first())
        assertEquals(240, onAmps.last())
        // OFF segments are silent.
        assertTrue(amps.filterIndexed { i, _ -> i % 2 == 1 }.all { it == 0 })
    }

    @Test
    fun `queue drop thud is a short low amplitude pulse`() {
        val wf = manager().buildQueueDrop()
        assertTrue(wf.timings.contentEquals(longArrayOf(28L)))
        assertTrue(wf.amplitudes!!.contentEquals(intArrayOf(110)))
    }

    @Test
    fun `waveform builder maps pulses to platform-native on-off arrays`() {
        val wf = HapticWaveform.waveform(
            gaps = longArrayOf(10L, 20L),
            durations = longArrayOf(5L, 6L, 7L),
            amplitudes = intArrayOf(100, 110, 120)
        )
        assertTrue(wf.timings.contentEquals(longArrayOf(5L, 10L, 6L, 20L, 7L)))
        assertTrue(wf.amplitudes!!.contentEquals(intArrayOf(100, 0, 110, 0, 120)))
    }

    @Test
    fun `waveform builder rejects misaligned primitives`() {
        val threw = try {
            HapticWaveform.waveform(
                gaps = longArrayOf(10L),
                durations = longArrayOf(5L),
                amplitudes = intArrayOf(100)
            )
            false
        } catch (e: IllegalArgumentException) {
            true
        }
        assertTrue(threw)
    }

    // ── Scrub micro-ticks: every 5 seconds of scrubbed audio ───────────────

    @Test
    fun `scrub ticks fire once per crossed 5 second bucket`() {
        val actuator = RecordingActuator()
        val clock = ManualClock()
        val m = manager(actuator, clock)
        // Advance the clock past the 45ms SCRUB_TICK throttle between calls
        // so these assertions exercise bucketing, not throttling.
        val feed = { pos: Long ->
            clock.time += 100L
            m.onScrubPositionChanged(pos)
        }
        // 0s (first call = arming tick), 4s (same bucket), 5s (crossed!),
        // 9s (same), 10s (crossed!), 14s (same), 20s (crossed once, skipping 15s)
        feed(0L)
        feed(4_000L)
        feed(5_000L)
        feed(9_000L)
        feed(10_000L)
        feed(14_000L)
        feed(20_000L)
        // 1 arming + 3 crossings (5s, 10s, 20s)
        assertEquals(4, actuator.fired.size)
        actuator.fired.forEach {
            assertEquals(HapticFeedbackManager.EFFECT_TICK, it.predefinedEffect)
        }
    }

    @Test
    fun `scrub buckets reset between gestures`() {
        val actuator = RecordingActuator()
        val clock = ManualClock()
        val m = manager(actuator, clock)
        m.onScrubPositionChanged(3_000L)
        m.resetBuckets()
        clock.time += 100L
        m.onScrubPositionChanged(3_500L)   // same audio position, new gesture: re-arms
        assertEquals(2, actuator.fired.size)
    }

    // ── Volume steps: 10 percent increments ────────────────────────────────

    @Test
    fun `volume ticks fire on each crossed 10 percent increment`() {
        val actuator = RecordingActuator()
        val clock = ManualClock()
        val m = manager(actuator, clock)
        val feed = { v: Float ->
            clock.time += 100L
            m.onVolumeFractionChanged(v)
        }
        feed(0.05f)   // bucket 0 (arming)
        feed(0.08f)   // bucket 0
        feed(0.12f)   // bucket 1 -> tick
        feed(0.18f)   // bucket 1
        feed(0.95f)   // bucket 9 -> tick
        assertEquals(3, actuator.fired.size)
    }

    @Test
    fun `volume fractions are clamped before bucketing`() {
        val actuator = RecordingActuator()
        val clock = ManualClock()
        val m = manager(actuator, clock)
        m.onVolumeFractionChanged(-0.5f)   // clamps to 0
        clock.time += 100L
        m.onVolumeFractionChanged(1.7f)    // clamps to 1
        assertEquals(2, actuator.fired.size)  // two distinct buckets
    }

    // ── Trigger throttling ─────────────────────────────────────────────────

    @Test
    fun `rapid duplicate triggers are throttled to one per window`() {
        val actuator = RecordingActuator()
        val clock = ManualClock()
        val m = manager(actuator, clock)
        m.playPauseClick()               // t=0
        clock.time = 50L
        m.playPauseClick()               // inside 120ms window -> dropped
        clock.time = 100L
        m.playPauseClick()               // still inside -> dropped
        assertEquals(1, actuator.fired.size)
        clock.time = 130L
        m.playPauseClick()               // window elapsed -> fired
        assertEquals(2, actuator.fired.size)
    }

    @Test
    fun `throttle windows are per pattern class`() {
        val actuator = RecordingActuator()
        val clock = ManualClock()
        val m = manager(actuator, clock)
        m.playPauseClick()   // PLAY_PAUSE_CLICK @0
        clock.time = 10L
        m.queueGrab()        // QUEUE_GRAB @10 — independent window
        clock.time = 20L
        m.likeHeartbeat()    // LIKE_HEARTBEAT @20 — independent window
        assertEquals(3, actuator.fired.size)
    }

    @Test
    fun `disabled manager never fires`() {
        val actuator = RecordingActuator()
        val m = manager(actuator)
        m.isEnabled = false
        m.playPauseClick()
        m.likeHeartbeat()
        m.onScrubPositionChanged(60_000L)
        m.jamUpvoteRising()
        assertEquals(0, actuator.fired.size)
    }

    @Test
    fun `missing vibrator hardware never fires`() {
        val actuator = RecordingActuator().apply { hasVibrator = false }
        val m = manager(actuator)
        m.playPauseClick()
        assertEquals(0, actuator.fired.size)
    }

    @Test
    fun `actuator throwing is absorbed and does not crash the ui thread`() {
        val actuator = object : HapticActuator {
            override var hasVibrator: Boolean = true
            override fun vibrate(waveform: HapticWaveform) = throw IllegalStateException("OEM HAL gone")
        }
        val m = manager(actuator)
        m.playPauseClick()   // must not propagate
    }

    // ── Scrub bucket constant is the design spec ───────────────────────────

    @Test
    fun `scrub bucket is five seconds`() {
        assertEquals(5_000L, HapticFeedbackManager.SCRUB_BUCKET_MS)
        assertEquals(5_000L, manager().scrubBucketMs)
    }
}
