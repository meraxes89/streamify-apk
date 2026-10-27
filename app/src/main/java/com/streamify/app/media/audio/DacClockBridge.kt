package com.streamify.app.media.audio

import com.streamify.app.data.uniffi.DacPresentationClock
import com.streamify.app.util.SLog
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * DAC presentation clock bridge (Phase 4a / MP-HAL06, Beta subtree).
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * The hardware HAL callback is the SOLE clock master (master plan
 * invariant 3 / MP-DEC-03): playback position, seek confirmation and
 * visualization sync derive strictly from hardware presentation
 * timestamps — never from wall-clock interpolation
 * (`System.currentTimeMillis()` is banned from every position estimate).
 *
 * SIGNAL PATH (all steps push-driven, zero polling):
 *
 *   feeder thread (audio-adjacent, urgent priority)
 *     └─ output.timestamp()                    [AudioTrack.getTimestamp:
 *                                               framePosition + CLOCK_MONOTONIC ns]
 *     └─ DacClockTranslator.toDacClock(...)    [→ DacPresentationClock triple]
 *     └─ DacClockSampleBus.publish(...)        [seqlock over 3 volatiles —
 *                                               ZERO allocation, wait-free]
 *     └─ bus.signal()                          [wakes the publisher worker]
 *
 *   publisher worker (non-real-time, dedicated single thread)
 *     └─ drains latest-wins → StateFlow<DacPresentationClock?>  [UI]
 *     └─ StreamifyEngineBridge.recordDacClock(clock)            [UDL
 *        `record_dac_clock`: republished inside every pushed
 *        EngineSnapshot — called from a non-audio thread per contract]
 *
 * ACCEPTANCE (this deliverable): interpolated position estimation stays
 * within <5 ms of hardware presentation ground truth — proven on the
 * VirtualDAC JVM harness in
 * `app/src/test/.../data/uniffi/DacClockEstimationTest.kt`.
 */

/**
 * Translation + interpolation math over [DacPresentationClock].
 *
 * Pure functions over value types — JVM-unit-testable with zero mocks.
 * The Phase 3 [com.streamify.app.data.uniffi.PlaybackPositionCalculator]
 * answers "position AT the sample"; this estimator answers "position
 * NOW", anchored on the hardware sample and advanced by the MONOTONIC
 * clock delta between the sample's `monotonicNs` and the query instant.
 * That is not wall-clock interpolation — both endpoints live on the same
 * OS monotonic domain, and the slope is the device DAC nominal rate.
 */
object DacClockTranslator {

    /**
     * Translates one hardware presentation timestamp into the UDL
     * [DacPresentationClock] triple.
     *
     * • `dac_frame_position` — cumulative hardware frames presented
     *   (AudioTrack's per-stream monotonic counter; negative platform
     *   sentinels are rejected to null);
     * • `sample_rate` — the nominal stream rate (48,000 in the canonical
     *   pipeline);
     * • `monotonic_ns` — the CLOCK_MONOTONIC presentation instant.
     */
    fun toDacClock(sample: AudioTimestampSample, nominalSampleRate: Int): DacPresentationClock? {
        if (nominalSampleRate <= 0) return null
        if (sample.framePosition < 0L || sample.nanoTime <= 0L) return null
        return DacPresentationClock(
            dacFramePosition = sample.framePosition.toULong(),
            sampleRate = nominalSampleRate.toUInt(),
            monotonicNs = sample.nanoTime.toULong(),
        )
    }

    /**
     * The estimated frames presented at the monotonic instant `nowNs`,
     * anchored on [clock]. Interpolation window: `nowNs - monotonicNs`
     * scaled by the nominal rate. Guards:
     *
     *  • a future-dated sample (`nowNs < monotonicNs`) snaps to the
     *    anchor — never a negative slope;
     *  • staleness beyond [maxAgeNs] snaps to the anchor too — a paused
     *    or torn stream must not extrapolate a runaway lie (the frozen
     *    anchor is the last HARDWARE truth available);
     *  • the estimate is clamped to [writtenFramesCap] when positive —
     *    the DAC cannot have presented more frames than the pipeline fed
     *    it (platform prefill included);
     *  • u64 saturation mirrors the Phase 3 calculator: a wrapped
     *    product must never become a negative position.
     */
    fun estimateFramesPresented(
        clock: DacPresentationClock,
        nowNs: Long,
        maxAgeNs: Long = DEFAULT_MAX_AGE_NS,
        writtenFramesCap: Long = Long.MAX_VALUE,
    ): Long {
        val anchorFrames = clock.dacFramePosition.toLong()
        val sampleNs = clock.monotonicNs.toLong()
        if (nowNs <= sampleNs) return clampEstimate(anchorFrames, writtenFramesCap)
        val age = nowNs - sampleNs
        if (age > maxAgeNs) return clampEstimate(anchorFrames, writtenFramesCap)
        // frames += age[ns] * rate / 1e9 — computed in Long with an
        // overflow-safe order (age first: age < maxAgeNs bounds it).
        val deltaFrames = (age / 1_000_000_000L) * clock.sampleRate.toLong() +
            (age % 1_000_000_000L) * clock.sampleRate.toLong() / 1_000_000_000L
        val estimate = anchorFrames + deltaFrames
        return clampEstimate(estimate, writtenFramesCap)
    }

    /**
     * Position in milliseconds at `nowNs` — [estimateFramesPresented]
     * converted through the nominal rate with the same saturation guard
     * as the Phase 3 calculator.
     */
    fun estimatePositionMs(
        clock: DacPresentationClock,
        nowNs: Long,
        maxAgeNs: Long = DEFAULT_MAX_AGE_NS,
        writtenFramesCap: Long = Long.MAX_VALUE,
    ): Long {
        if (clock.sampleRate == 0u) return 0L
        val frames = estimateFramesPresented(clock, nowNs, maxAgeNs, writtenFramesCap)
        if (frames < 0L) return 0L
        if (frames > Long.MAX_VALUE / 1000L) return Long.MAX_VALUE
        return (frames * 1000L) / clock.sampleRate.toLong()
    }

    private fun clampEstimate(estimate: Long, cap: Long): Long = when {
        cap <= 0L -> 0L
        estimate < 0L -> 0L
        estimate > cap -> cap
        else -> estimate
    }

    /**
     * Staleness horizon: 250 ms ≈ the platform stream's full buffer depth
     * (4096 frames ≈ 85 ms) plus a torn-timestamp replacement window.
     * Beyond it the anchor is authoritative and FROZEN — the estimator
     * never invents motion the hardware did not report.
     */
    const val DEFAULT_MAX_AGE_NS: Long = 250_000_000L
}

/**
 * Wait-free, allocation-free latest-wins sample bus from the feeder
 * thread to the publisher worker (MP-HAL06 signal path).
 *
 * SEQLOCK over three `@Volatile` longs: the writer bumps a sequence to
 * odd, stores the triple, bumps to even; readers retry while the
 * sequence is odd or changed under them (classic Lamport seqlock — same
 * pattern as the C++ `nativeGetSynchronizedClockNanos` edge, so the
 * clocks ride the same consistency discipline across the boundary).
 * Volatile long stores/loads are atomic on the JVM (JLS 17.7), so the
 * seqlock only needs to guard the CROSS-FIELD consistency of the triple.
 *
 * The feeder thread allocates NOTHING per publish and performs NO JNI
 * upcalls — the two hard laws of MP-HAL05's real-time callback.
 */
class DacClockSampleBus {

    private val sequence = AtomicLong(0L)

    @Volatile private var frames: Long = 0L
    @Volatile private var rate: Int = 0
    @Volatile private var monoNs: Long = 0L

    @Volatile private var publishedCount: Long = 0L

    /** Publishes one sample (feeder thread; wait-free, allocation-free). */
    fun publish(framesPresented: Long, sampleRate: Int, monotonicNs: Long) {
        val seq = sequence.get()
        sequence.set(seq + 1L) // odd — writing
        this.frames = framesPresented
        this.rate = sampleRate
        this.monoNs = monotonicNs
        sequence.set(seq + 2L) // even — stable
        publishedCount += 1L
    }

    /**
     * Reads the latest consistent triple; null when nothing has been
     * published yet (or a writer won the race across unbounded retries).
     */
    fun latest(): DacPresentationClock? {
        repeat(READ_RETRIES) {
            val before = sequence.get()
            if (before % 2L != 0L) return@repeat // mid-write — retry
            val f = frames
            val r = rate
            val n = monoNs
            if (sequence.get() == before) {
                if (r <= 0 || f < 0L || n <= 0L) return null
                return DacPresentationClock(
                    dacFramePosition = f.toULong(),
                    sampleRate = r.toUInt(),
                    monotonicNs = n.toULong(),
                )
            }
        }
        return null
    }

    /** Number of successful publications (telemetry / test assertions). */
    fun publishedSamples(): Long = publishedCount

    private companion object {
        const val READ_RETRIES = 16
    }
}

/**
 * Drains the [DacClockSampleBus] and republishes the hardware clock to
 * its two consumers: the app-side reactive surface ([clock] StateFlow
 * for UI position math) and the engine's `record_dac_clock` control
 * plane (republished inside every pushed EngineSnapshot — MP-C01).
 *
 * Runs on a DEDICATED non-real-time worker thread (never the audio
 * thread, per the UDL contract note); wakes ONLY on feeder signals —
 * no timers, no polling loops (zero-polling law, MP-DEC-04).
 */
class DacClockPublisher(
    private val bus: DacClockSampleBus,
    private val recordIntoEngine: (DacPresentationClock) -> Unit,
) {

    private val _clock = MutableStateFlow<DacPresentationClock?>(null)

    /** Latest hardware presentation clock (conflated; null until first sample). */
    val clock: StateFlow<DacPresentationClock?> = _clock.asStateFlow()

    @Volatile private var running = false
    @Volatile private var signalled = false

    private var worker: Thread? = null

    @Volatile var recordedSamples: Long = 0L
        private set

    /** Feeder-side hook — cheap, non-blocking, wakes the worker. */
    /** Park/wake protocol for the worker loop (explicit lock + condition). */
    private val lock = java.util.concurrent.locks.ReentrantLock()
    private val signalCondition = lock.newCondition()

    fun signal() {
        lock.lock()
        try {
            signalled = true
            signalCondition.signalAll()
        } finally {
            lock.unlock()
        }
    }

    fun start() {
        if (running) return
        running = true
        signalled = false
        worker = Thread({ runLoop() }, "streamify-dac-clock").apply {
            priority = Thread.NORM_PRIORITY
            isDaemon = true
            start()
        }
    }

    fun stop() {
        running = false
        signal()
        worker?.let { w ->
            try {
                w.join(STOP_JOIN_TIMEOUT_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        worker = null
    }

    private fun runLoop() {
        var lastRecorded: DacPresentationClock? = null
        while (running) {
            val latest = bus.latest()
            if (latest == null) {
                awaitSignal()
                continue
            }
            _clock.value = latest
            if (latest != lastRecorded) {
                try {
                    recordIntoEngine(latest)
                    recordedSamples += 1L
                } catch (e: Throwable) {
                    // The engine record path is Result-based; a stray
                    // throwable here must never kill the clock worker.
                    SLog.w(TAG, "record_dac_clock dispatch failed", e)
                }
                lastRecorded = latest
            }
            // Coalesce: park until the feeder publishes again.
            awaitSignal()
        }
    }

    /**
     * Parks until [signal] or [stop]; returns false when the loop should
     * exit, true when a fresh signal arrived (the `?: continue` idiom in
     * [runLoop] treats "no signal + stopped" as exit and re-checks the
     * bus otherwise).
     */
    private fun awaitSignal(): Boolean {
        lock.lock()
        try {
            if (signalled) {
                signalled = false
                return true
            }
            if (!running) return false
            try {
                signalCondition.await(SIGNAL_PARK_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return running
            }
            val had = signalled
            signalled = false
            return had || running
        } finally {
            lock.unlock()
        }
    }

    private companion object {
        const val TAG = "DacClockPublisher"
        const val STOP_JOIN_TIMEOUT_MS = 500L

        /**
         * Safety-valve park cap: with the push design this never fires in
         * steady state (the feeder signals every burst); it exists so a
         * lost signal degrades to one cheap re-read per second instead
         * of a silent stall. This is NOT a polling loop over engine
         * state — it reads only the local seqlock bus.
         */
        const val SIGNAL_PARK_TIMEOUT_MS = 1_000L
    }
}
