package com.streamify.app.media.audio

import java.util.concurrent.atomic.AtomicLong
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * Phase 4a (MP-HAL05) — the pull pipeline end-to-end on hermetic JVM doubles.
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * [FakeRing] doubles the native SPSC f32 ring (whole-frame semantics,
 * short writes, cooperative flush, occupancy stats) and [FakeOutput]
 * doubles the strictly-configured AudioTrack (paced blocking writes,
 * hardware timestamp production). Together they exercise the REAL
 * [RustAudioPipeline] feeder thread, underrun zero-fill, DAC clock
 * sampling → publisher → engine recorder signal path, pause parking and
 * the MP-HAL07 clean-teardown contract (no leaked ring / output / thread).
 */
class RustAudioPipelineTest {

    // ── Hermetic doubles ───────────────────────────────────────────────────

    private class FakeRing(
        private val capacityFrames: Int,
        private val channels: Int = 2,
    ) : FloatSinkRing {

        private val lock = Any()
        private val storage = FloatArray(capacityFrames * channels)
        private var head = 0 // frames committed
        private var tail = 0 // frames consumed

        var released = false
            private set
        var flushCount = 0
            private set

        fun occupancyFrames(): Int = synchronized(lock) { head - tail }

        override fun writeSamples(src: FloatArray, offset: Int, sampleCount: Int): Int {
            synchronized(lock) {
                if (released) return 0
                val free = capacityFrames - (head - tail)
                val framesWanted = sampleCount / channels
                val frames = minOf(framesWanted, free)
                if (frames <= 0) return 0
                val dstBase = (head % capacityFrames) * channels
                val floats = frames * channels
                // Two-segment copy across the wrap.
                var copied = 0
                while (copied < floats) {
                    val n = minOf(floats - copied, storage.size - (dstBase + copied))
                    System.arraycopy(src, offset + copied, storage, dstBase + copied, n)
                    copied += n
                }
                head += frames
                return floats
            }
        }

        override fun readFramesF32(dst: FloatArray, maxFrames: Int): Int {
            synchronized(lock) {
                if (released) return 0
                val available = head - tail
                val frames = minOf(maxFrames, available)
                if (frames <= 0) return 0
                val srcBase = (tail % capacityFrames) * channels
                val floats = frames * channels
                var copied = 0
                while (copied < floats) {
                    val n = minOf(floats - copied, storage.size - (srcBase + copied))
                    System.arraycopy(storage, srcBase + copied, dst, copied, n)
                    copied += n
                }
                tail += frames
                return frames
            }
        }

        override fun requestFlush() {
            synchronized(lock) {
                flushCount++
                tail = head // consumer-cooperative: drop occupancy only
            }
        }

        override fun stats(): RustAudioSinkJni.SinkStatsSnapshot =
            RustAudioSinkJni.SinkStatsSnapshot(
                capacityFrames = capacityFrames,
                sampleRateIn = 48_000,
                sampleRateOut = 48_000,
                channelsIn = channels,
                channelsOut = channels,
                currentFullnessFrames = occupancyFrames(),
                framesWritten = head.toLong(),
                framesRead = tail.toLong(),
                underrunEvents = 0L,
                overrunEvents = 0L,
                overrunDroppedFrames = 0L,
                highWatermarkFrames = 0,
                lowWatermarkFrames = 0,
                fullnessPercent = 0f,
                estimatedLatencyMs = 0f,
                sequence = head,
            )

        override fun release() {
            released = true
        }
    }

    private class FakeOutput(
        /** Simulated mixer consumption pacing (ms per accepted burst). */
        private val pacingMs: Long = 2,
    ) : PullAudioOutput {

        private val lock = Any()
        private val chunks = ArrayList<FloatArray>()
        val acceptedFrames = AtomicLong(0L)

        @Volatile var playing = false
        @Volatile var released = false

        /** Scriptable hard failure (teardown path verification). */
        @Volatile var failWrites = false

        fun totalFrames(): Long = acceptedFrames.get()

        /** Concatenated accepted audio (test-side sink assertion). */
        fun drained(): FloatArray = synchronized(lock) {
            val size = chunks.sumOf { it.size }
            val out = FloatArray(size)
            var at = 0
            for (c in chunks) {
                System.arraycopy(c, 0, out, at, c.size)
                at += c.size
            }
            out
        }

        override fun play() {
            playing = true
        }

        override fun pause() {
            playing = false
        }

        override fun writeBlocking(src: FloatArray, offsetFrames: Int, frameCount: Int): Int {
            if (failWrites) return -1
            // Realistic paused-track semantics: a paused platform stream
            // stops draining — writeBlocking parks inside the "platform"
            // until play()/release() (exactly where a real AudioTrack
            // parks the feeder).
            while (!playing && !released) {
                try {
                    Thread.sleep(2)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return -1
                }
            }
            if (released) return -1
            try {
                Thread.sleep(pacingMs)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            val base = offsetFrames * 2
            val copy = src.copyOfRange(base, base + frameCount * 2)
            synchronized(lock) { chunks += copy }
            acceptedFrames.addAndGet(frameCount.toLong())
            return frameCount
        }

        override fun hasPendingData(): Boolean = false

        override fun timestamp(): AudioTimestampSample? {
            val frames = acceptedFrames.get()
            return if (frames <= 0L) null else AudioTimestampSample(frames, System.nanoTime())
        }

        override fun release() {
            released = true
        }
    }

    // ── The pipeline contract ──────────────────────────────────────────────

    @Test
    fun `produced audio is pulled through the ring to the output verbatim`() {
        val ring = FakeRing(capacityFrames = 8_192)
        val output = FakeOutput()
        var ringCreations = 0
        val pipeline = RustAudioPipeline(
            ringFactory = { ringCreations++; ring },
            outputFactory = { output },
            burstFrames = 64,
            clockSampleInterval = 1,
        )
        val frames = 256
        val ramp = FloatArray(frames * 2) { it / 1000f }
        var written = 0
        while (written < ramp.size) {
            val accepted = ring.writeSamples(ramp, written, ramp.size - written)
            if (accepted > 0) written += accepted
        }
        assertTrue(pipeline.start())
        try {
            pipeline.onProducerActivity()
            await("output drained the ramp") { output.totalFrames() >= frames }
            val drained = output.drained()
            // The ramp must appear as a contiguous prefix (silence bursts
            // may trail it while the feeder keeps the stream warm).
            for (i in ramp.indices) {
                assertEquals("sample $i drifted", ramp[i], drained[i], 0f)
            }
        } finally {
            pipeline.stop()
        }
        // MP-HAL07 clean teardown: every owned resource released, exactly
        // one ring creation, no double-start side effects.
        assertTrue(ring.released)
        assertTrue(output.released)
        assertEquals(1, ringCreations)
    }

    @Test
    fun `starved feeder zero-fills and counts underruns while playing`() {
        val ring = FakeRing(capacityFrames = 1_024)
        val output = FakeOutput()
        val pipeline = RustAudioPipeline(
            ringFactory = { ring },
            outputFactory = { output },
            burstFrames = 64,
            clockSampleInterval = 4,
        )
        assertTrue(pipeline.start())
        try {
            await("underruns tallied") { pipeline.snapshotTelemetry().feederUnderruns >= 2L }
            // Nothing was ever produced: every accepted burst is digital
            // silence (the DAC-clock-advancing starvation behavior).
            val drained = output.drained()
            assertTrue(drained.isNotEmpty())
            assertTrue(drained.all { it == 0f })
            assertTrue(pipeline.snapshotTelemetry().fullBursts == 0L)
        } finally {
            pipeline.stop()
        }
    }

    @Test
    fun `hardware timestamps flow to the engine recorder and the reactive clock`() {
        val ring = FakeRing(capacityFrames = 4_096)
        val output = FakeOutput()
        val recorded = java.util.concurrent.ConcurrentLinkedQueue<
            com.streamify.app.data.uniffi.DacPresentationClock>()
        val pipeline = RustAudioPipeline(
            ringFactory = { ring },
            outputFactory = { output },
            clockRecorder = { clock -> recorded += clock },
            burstFrames = 64,
            clockSampleInterval = 1,
        )
        assertTrue(pipeline.start())
        try {
            val frames = 512
            val tone = FloatArray(frames * 2) {
                (sin(2.0 * Math.PI * 440.0 * (it / 2) / 48_000.0) * 0.5f).toFloat()
            }
            var written = 0
            while (written < tone.size) {
                val accepted = ring.writeSamples(tone, written, tone.size - written)
                if (accepted > 0) written += accepted
                pipeline.onProducerActivity()
            }
            await("recorder received hardware samples") { recorded.isNotEmpty() }
            await("reactive dac clock published") { pipeline.dacClock.value != null }

            val last = recorded.last()
            assertEquals(48_000u, last.sampleRate)
            // The reporter frames equal what the output accepted at
            // sample time (the AudioTimestamp contract).
            assertTrue(last.dacFramePosition.toLong() in 1..output.totalFrames())
            val telemetry = pipeline.snapshotTelemetry()
            assertTrue(telemetry.dacClockSamples >= 1L)
            assertTrue(telemetry.framesPulled > 0L)
        } finally {
            pipeline.stop()
        }
    }

    @Test
    fun `pause parks the feeder and play resumes`() {
        val ring = FakeRing(capacityFrames = 8_192)
        val output = FakeOutput()
        val pipeline = RustAudioPipeline(
            ringFactory = { ring },
            outputFactory = { output },
            burstFrames = 64,
            clockSampleInterval = 8,
        )
        assertTrue(pipeline.start())
        try {
            val frames = 128
            val tone = FloatArray(frames * 2) { 0.25f }
            var written = 0
            while (written < tone.size) {
                val accepted = ring.writeSamples(tone, written, tone.size - written)
                if (accepted > 0) written += accepted
                pipeline.onProducerActivity()
            }
            await("initial drain") { output.totalFrames() >= frames }

            pipeline.pause()
            // Absorb the one in-flight paced write that entered the fake
            // platform before the pause landed (~2 ms pacing), THEN
            // capture the parked baseline.
            Thread.sleep(20)
            val atPause = output.totalFrames()
            stableWhile("feeder parked while paused") { output.totalFrames() == atPause }

            pipeline.play()
            val more = FloatArray(128 * 2) { -0.25f }
            var w2 = 0
            while (w2 < more.size) {
                val accepted = ring.writeSamples(more, w2, more.size - w2)
                if (accepted > 0) w2 += accepted
                pipeline.onProducerActivity()
            }
            await("resume drains new data") { output.totalFrames() >= frames + 128 }
        } finally {
            pipeline.stop()
        }
    }

    @Test
    fun `flush drops buffered ring data cooperatively`() {
        val ring = FakeRing(capacityFrames = 8_192)
        val output = FakeOutput()
        val pipeline = RustAudioPipeline(
            ringFactory = { ring },
            outputFactory = { output },
            burstFrames = 64,
            clockSampleInterval = 8,
        )
        assertTrue(pipeline.start())
        try {
            pipeline.pause()
            // Settle: the feeder finishes its in-flight paced write and
            // parks at the gate on the (still empty) ring.
            Thread.sleep(60)

            val frames = 256
            val tone = FloatArray(frames * 2) { 0.75f }
            var written = 0
            while (written < tone.size) {
                val accepted = ring.writeSamples(tone, written, tone.size - written)
                if (accepted > 0) written += accepted
            }
            assertTrue(ring.occupancyFrames() > 0)

            pipeline.flush() // control-thread flush: drop the buffered audio
            await("ring emptied") { ring.occupancyFrames() == 0 }
            assertEquals(1, ring.flushCount)

            pipeline.play()
            Thread.sleep(120) // let the resumed feeder run on the emptied ring
            val drained = output.drained()
            // The flushed audio never reached the air (only pre-pause
            // silence / post-flush silence may appear).
            assertTrue(drained.all { it != 0.75f })
        } finally {
            pipeline.stop()
        }
    }

    @Test
    fun `start is idempotent and stop is safe to repeat`() {
        val ring = FakeRing(capacityFrames = 1_024)
        var creations = 0
        val pipeline = RustAudioPipeline(
            ringFactory = { creations++; ring },
            outputFactory = { FakeOutput() },
            burstFrames = 64,
        )
        assertTrue(pipeline.start())
        assertTrue(pipeline.start()) // no-op success, single creation
        assertEquals(1, creations)
        pipeline.stop()
        pipeline.stop() // idempotent
        assertTrue(ring.released)
    }

    @Test
    fun `a failing ring factory degrades to start failure without leaks`() {
        var outputCreated = false
        val pipeline = RustAudioPipeline(
            ringFactory = { throw IllegalStateException("edge torn") },
            outputFactory = { outputCreated = true; FakeOutput() },
        )
        assertFalse(pipeline.start())
        // The output factory never ran (ordering: ring first) — and even
        // when it had, stop() would have released it.
        assertFalse(outputCreated)
        pipeline.stop() // safe no-op
    }

    @Test
    fun `torn output parks the feeder and marks telemetry`() {
        val ring = FakeRing(capacityFrames = 1_024)
        val output = FakeOutput()
        val pipeline = RustAudioPipeline(
            ringFactory = { ring },
            outputFactory = { output },
            burstFrames = 64,
            clockSampleInterval = 8,
        )
        assertTrue(pipeline.start())
        try {
            await("feeder alive") { pipeline.snapshotTelemetry().framesPulled > 0L }
            output.failWrites = true
            await("torn output observed") { pipeline.snapshotTelemetry().tornOutput }
        } finally {
            pipeline.stop()
        }
        assertTrue(output.released)
        assertTrue(ring.released)
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private fun await(label: String, timeoutMs: Long = 5_000, probe: () -> Boolean) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (System.nanoTime() < deadline) {
            if (probe()) return
            Thread.sleep(5)
        }
        throw AssertionError("timeout waiting for: $label")
    }

    /** Asserts the predicate holds across a ~150 ms window (park proof). */
    private fun stableWhile(label: String, timeoutMs: Long = 5_000, probe: () -> Boolean) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (!probe()) {
            if (System.nanoTime() > deadline) {
                throw AssertionError("timeout waiting for stability: $label")
            }
            Thread.sleep(5)
        }
        // Once stable, hold for 150 ms to catch a slow leak of writes.
        val holdUntil = System.nanoTime() + 150_000_000
        while (System.nanoTime() < holdUntil) {
            assertTrue("stability lost: $label", probe())
            Thread.sleep(10)
        }
    }
}
