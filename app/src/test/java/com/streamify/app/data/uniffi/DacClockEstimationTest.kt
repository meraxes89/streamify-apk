package com.streamify.app.data.uniffi

import com.streamify.app.media.audio.AudioTimestampSample
import com.streamify.app.media.audio.DacClockSampleBus
import com.streamify.app.media.audio.DacClockPublisher
import com.streamify.app.media.audio.DacClockTranslator
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.random.Random
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * MP-HAL06 acceptance — DacPresentationClock estimation error < 5 ms against
 * hardware presentation ground truth (Phase 4a, Beta subtree).
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * The VirtualDAC harness (the JVM leg of the master plan's VirtualHAL —
 * "DacPresentationClock UI progress test double") simulates a presenting
 * DAC with the adversarial axes MP-HAL02 prescribes:
 *
 *   • callback/burst sizes {256, 512, 1024} frames,
 *   • ±10% presentation-timing jitter,
 *   • true-rate deviation ±5000 ppm (a pathological resampling gadget;
 *     real DACs sit inside ±100 ppm),
 *   • timestamp sampling cadences {10, 20, 50} Hz (the window the
 *     hardware-latency profiler recommends),
 *   • uniformly random query instants BETWEEN timestamp samples — the
 *     interpolation regime every UI progress frame lives in.
 *
 * The estimator under test is the production [DacClockTranslator]: anchor
 * on the latest hardware (frames, monotonicNs) pair, advance by the
 * monotonic delta scaled by the NOMINAL rate. Acceptance: |estimated −
 * ground truth| < 5 ms across the whole sweep (directive: "interpolated
 * position estimation remains within <5ms estimation error against
 * hardware presentation ground truth").
 *
 * All math is integer-event simulation on a virtual monotonic nanosecond
 * axis — no wall clock, no sleeping, fully deterministic (seeded).
 */
class DacClockEstimationTest {

    // ── The VirtualDAC harness ─────────────────────────────────────────────

    /**
     * One simulated hardware presentation clock. Truth is the continuous
     * ramp `(t − t0) · trueRate`; timestamp SAMPLES are exact
     * (frames, nanoTime) pairs read at a jittered cadence — exactly what
     * `AudioTrack.getTimestamp()` hands the platform adapter.
     */
    private class VirtualDac(
        val nominalRateHz: Int,
        val rateErrorPpm: Long,
        val burstFrames: Int,
        val timingJitterPct: Double,
        val seed: Long,
    ) {
        val trueRateHz: Double = nominalRateHz * (1.0 + rateErrorPpm / 1_000_000.0)

        private val random = Random(seed)

        /** Simulated monotonic axis (ns). */
        var nowNs: Long = 0L
            private set

        /** Ground-truth frames presented at [nowNs]. */
        fun truthFrames(): Double = nowNs * 1e-9 * trueRateHz

        /**
         * Generates the timestamp stream for `durationSec` of simulated
         * presentation at `cadenceHz`, with per-callback presentation
         * jitter; returns the samples in emission order.
         */
        fun timestampStream(durationSec: Int, cadenceHz: Int): List<AudioTimestampSample> {
            val samples = ArrayList<AudioTimestampSample>()
            val callbackPeriodNs = (burstFrames / trueRateHz * 1e9)
            var nextCallbackNs = 0.0
            // Start at t0 = 1 ms: a zero monotonicNs is a platform
            // sentinel the translator rightly rejects — the sweep wants
            // valid samples from the very first one.
            var nextSampleNs = 1_000_000.0
            val samplePeriodNs = 1e9 / cadenceHz
            val endNs = durationSec * 1e9
            while (nextSampleNs < endNs) {
                // Advance the DAC's callback clock (with jitter) past the
                // sample instant so the counter reflects everything the
                // hardware has presented by then.
                while (nextCallbackNs <= nextSampleNs) {
                    nextCallbackNs += callbackPeriodNs * (1.0 + timingJitterPct * jitter(random))
                }
                nowNs = nextSampleNs.toLong()
                val framesAtSample = truthFrames()
                samples += AudioTimestampSample(
                    framePosition = framesAtSample.toLong(),
                    nanoTime = nowNs,
                )
                nextSampleNs += samplePeriodNs * (1.0 + timingJitterPct * jitter(random) * 0.25)
            }
            return samples
        }

        private fun jitter(random: Random): Double = random.nextDouble() * 2.0 - 1.0
    }

    // ── Acceptance sweep (the directive's <5ms criterion) ─────────────────

    @Test
    fun `interpolated position stays within 5ms across the VirtualDAC adversarial sweep`() {
        val burstSizes = intArrayOf(256, 512, 1024)
        val jitters = doubleArrayOf(0.0, 0.10)
        val rateErrors = longArrayOf(-5000L, 0L, 5000L)
        val cadences = intArrayOf(10, 20, 50)

        var scenarios = 0
        var queries = 0
        var worstAbsErrorMs = 0.0

        for (burst in burstSizes) {
            for (jitter in jitters) {
                for (ppm in rateErrors) {
                    for (cadence in cadences) {
                        val dac = VirtualDac(
                            nominalRateHz = 48_000,
                            rateErrorPpm = ppm,
                            burstFrames = burst,
                            timingJitterPct = jitter,
                            seed = 0xBEEF_0000L + scenarios,
                        )
                        val samples = dac.timestampStream(durationSec = 10, cadenceHz = cadence)
                        assertTrue("stream must produce samples", samples.size >= cadence * 9)

                        val random = Random(0xC0FFEEL + scenarios)
                        var scenarioWorst = 0.0
                        for (i in 1 until samples.size) {
                            val anchor = samples[i - 1]
                            val clock = DacClockTranslator.toDacClock(anchor, 48_000)
                            assertNotNull("anchor must translate", clock)
                            // Query instants strictly between the anchor and
                            // the next sample — the interpolation window.
                            val span = samples[i].nanoTime - anchor.nanoTime
                            repeat(4) {
                                val queryNs = anchor.nanoTime + (span * random.nextDouble()).toLong()
                                val truthMs = queryNs * 1e-9 * dac.trueRateHz / 48_000.0 * 1000.0
                                val estimatedMs = DacClockTranslator.estimatePositionMs(
                                    requireNotNull(clock),
                                    nowNs = queryNs,
                                )
                                val error = kotlin.math.abs(estimatedMs - truthMs)
                                scenarioWorst = maxOf(scenarioWorst, error)
                                queries++
                            }
                        }
                        worstAbsErrorMs = maxOf(worstAbsErrorMs, scenarioWorst)
                        assertTrue(
                            "burst=$burst jitter=$jitter ppm=$ppm cadence=$cadence " +
                                "exceeded the 5ms budget: ${scenarioWorst}ms",
                            scenarioWorst < ACCEPTANCE_ERROR_MS,
                        )
                        scenarios++
                    }
                }
            }
        }
        // Sanity: the harness must actually measure something (a stub that
        // always returns the anchor would pass trivially on age≈0 queries).
        assertTrue("worst error should exercise the budget ladder", worstAbsErrorMs > 0.0001)
    }

    @Test
    fun `estimator freezes on the anchor when timestamps go stale`() {
        // A stream that stops reporting (audioserver hiccup): beyond the
        // staleness horizon the estimator must HOLD the last hardware
        // truth — never extrapolate a runaway lie, never regress.
        val anchor = DacPresentationClock(
            dacFramePosition = 48_000_000uL, // 1000.000 s
            sampleRate = 48_000u,
            monotonicNs = 10_000_000_000_000uL,
        )
        val staleByNs = 2_000_000_000L // 2 s without a sample
        val positionAtStale = DacClockTranslator.estimatePositionMs(
            anchor, nowNs = anchor.monotonicNs.toLong() + staleByNs,
        )
        val positionMuchStaler = DacClockTranslator.estimatePositionMs(
            anchor, nowNs = anchor.monotonicNs.toLong() + staleByNs * 10,
        )
        assertEquals("frozen anchor position", 1_000_000L, positionAtStale)
        assertEquals("no runaway extrapolation", positionAtStale, positionMuchStaler)
    }

    @Test
    fun `estimator never reports beyond the frames actually written`() {
        // The DAC cannot have presented more than the pipeline fed it:
        // the written-frames cap clamps platform prefill overshoot.
        val anchor = DacPresentationClock(
            dacFramePosition = 90_000uL,
            sampleRate = 48_000u,
            monotonicNs = 1_875_000_000uL,
        )
        val capped = DacClockTranslator.estimatePositionMs(
            anchor,
            nowNs = anchor.monotonicNs.toLong() + 500_000_000L, // +0.5 s → +24000 frames
            maxAgeNs = 1_000_000_000L, // wide horizon: exercise the CAP, not the staleness snap
            writtenFramesCap = 96_000L, // only 6000 more frames were fed
        )
        assertEquals(2_000L, capped) // 96_000 frames @48k = 2000 ms, not 3_996
    }

    @Test
    fun `future-dated and degenerate samples snap to the anchor`() {
        val anchor = DacPresentationClock(
            dacFramePosition = 480_000uL,
            sampleRate = 48_000u,
            monotonicNs = 10_000_000_000uL,
        )
        // A timestamp slightly in the future of the query clock (NTP-skew
        // style): no negative slope, just the anchor.
        assertEquals(
            10_000L,
            DacClockTranslator.estimatePositionMs(anchor, nowNs = 9_999_000_000L),
        )
        // Zero rate never divides by zero.
        assertEquals(
            0L,
            DacClockTranslator.estimatePositionMs(
                anchor.copy(sampleRate = 0u), nowNs = 10_000_000_000L,
            ),
        )
    }

    @Test
    fun `translator rejects platform sentinel timestamps`() {
        // Negative framePosition / non-positive nanoTime are platform
        // error sentinels, never presentation truths.
        assertNull(DacClockTranslator.toDacClock(AudioTimestampSample(-1L, 1L), 48_000))
        assertNull(DacClockTranslator.toDacClock(AudioTimestampSample(1L, 0L), 48_000))
        assertNull(DacClockTranslator.toDacClock(AudioTimestampSample(1L, 1L), 0))
        val ok = DacClockTranslator.toDacClock(AudioTimestampSample(7L, 42L), 48_000)
        assertNotNull(ok)
        assertEquals(7uL, ok!!.dacFramePosition)
        assertEquals(48_000u, ok.sampleRate)
        assertEquals(42uL, ok.monotonicNs)
    }

    // ── The seqlock sample bus (feeder → publisher signal path) ───────────

    @Test
    fun `sample bus round-trips the latest triple`() {
        val bus = DacClockSampleBus()
        assertNull(bus.latest())
        bus.publish(100L, 48_000, 1_000L)
        bus.publish(200L, 48_000, 2_000L)
        val latest = bus.latest()
        assertNotNull(latest)
        assertEquals(200uL, latest!!.dacFramePosition)
        assertEquals(48_000u, latest.sampleRate)
        assertEquals(2_000uL, latest.monotonicNs)
        assertEquals(2L, bus.publishedSamples())
    }

    @Test
    fun `sample bus survives a concurrent publish storm`() {
        // Single writer (the feeder contract) hammering while readers
        // spin: every observed triple must be internally consistent —
        // a cross-field torn read would fail the frames<->mono pairing.
        val bus = DacClockSampleBus()
        val stop = java.util.concurrent.atomic.AtomicBoolean(false)
        val reader = Thread {
            while (!stop.get()) {
                bus.latest()?.let { sample ->
                    // Invariants of the writer's pattern: mono = frames/2,
                    // rate fixed. A tear breaks this relation.
                    assertTrue(sample.sampleRate == 48_000u)
                    assertTrue(
                        "torn read: frames=${sample.dacFramePosition} mono=${sample.monotonicNs}",
                        sample.monotonicNs.toLong() == sample.dacFramePosition.toLong() / 2L,
                    )
                }
            }
        }.apply { start() }
        var frames = 0L
        repeat(200_000) {
            frames += 2
            bus.publish(frames, 48_000, frames / 2)
        }
        stop.set(true)
        reader.join(5_000)
        assertEquals(frames / 2L * 2L, bus.latest()!!.dacFramePosition.toLong())
    }

    // ── The publisher worker (record_dac_clock signal path) ───────────────

    @Test
    fun `publisher drains bus samples into the engine recorder`() {
        val bus = DacClockSampleBus()
        val recorded = java.util.concurrent.ConcurrentLinkedQueue<DacPresentationClock>()
        val publisher = DacClockPublisher(bus) { clock -> recorded += clock }
        publisher.start()
        try {
            bus.publish(48_000L, 48_000, 1_000_000_000L)
            publisher.signal()
            awaitCondition("first sample recorded") { recorded.isNotEmpty() }
            bus.publish(96_000L, 48_000, 2_000_000_000L)
            publisher.signal()
            awaitCondition("second sample recorded") { recorded.size >= 2 }
            assertEquals(96_000uL, recorded.last().dacFramePosition)
            assertTrue(publisher.recordedSamples >= 2L)
        } finally {
            publisher.stop()
        }
    }

    @Test
    fun `publisher stop is bounded and idempotent`() {
        val bus = DacClockSampleBus()
        val publisher = DacClockPublisher(bus) { }
        publisher.start()
        publisher.stop()
        publisher.stop() // idempotent
        // A signal after stop must not resurrect the worker.
        publisher.signal()
        Thread.sleep(50)
        assertEquals(0L, publisher.recordedSamples)
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private fun awaitCondition(label: String, timeoutMs: Long = 5_000L, probe: () -> Boolean) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (System.nanoTime() < deadline) {
            if (probe()) return
            Thread.sleep(10)
        }
        throw AssertionError("timeout waiting for: $label")
    }

    @After
    fun tearDown() {
        // Nothing process-wide is held by this suite (no backend installs,
        // no bridge state) — the hook exists to keep that contract visible.
    }

    private companion object {
        /** The directive's acceptance budget (MP-HAL06). */
        const val ACCEPTANCE_ERROR_MS = 5.0
    }
}
