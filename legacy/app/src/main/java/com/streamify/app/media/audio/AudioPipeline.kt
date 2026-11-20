package com.streamify.app.media.audio

import com.streamify.app.util.SLog
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * RustAudioPipeline — the app-side AAudio pull adapter over the native
 * f32 SPSC ring (Phase 4a / MP-HAL05, Beta subtree).
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * ARCHITECTURE (the "pull" the directive mandates):
 *
 *   Rust sovereign engine / Media3 sink adapter        [producer edge]
 *     └─ FloatSinkRing.writeSamples(f32)               native SPSC ring
 *            │                                          (48 kHz / 2ch / f32)
 *            ▼
 *   FEEDER THREAD (THREAD_PRIORITY_URGENT_AUDIO)       [consumer edge]
 *     └─ ring.readFramesF32(chunk)   ─── one JNI DOWNcall per burst
 *     └─ output.writeBlocking(chunk) ─── parks INSIDE the platform
 *                                        mixer's room-wait: the DAC's
 *                                        consumption IS the wake source
 *     └─ starved → zero-fill the remainder (digital
 *        silence keeps the DAC clock advancing) + underrun tally
 *     └─ every CLOCK_SAMPLE_WRITE_INTERVAL bursts:
 *          output.timestamp() → DacClockSampleBus.publish (seqlock,
 *          zero alloc) → DacClockPublisher.signal()
 *
 * The platform mixer draining its buffer room is the ONLY wake source —
 * no timers, no tickers, no while(true)-with-delay polling of engine
 * state (zero-polling law MP-DEC-04 applies to the data plane too).
 *
 * REAL-TIME LAWS honored on the feeder thread:
 *  • ZERO JVM allocation in steady state — one preallocated chunk
 *    FloatArray, one reused AudioTimestamp container, volatiles only;
 *  • ZERO JNI upcalls — every crossing is a Java→native DOWNcall
 *    (ring read, framework write). Native code never calls back into
 *    the JVM from the audio path. When Alpha's C++ AAudio/Oboe edge
 *    lands, the SAME ring is ingested by the native callback directly
 *    and this feeder simply stops being spawned (MP-HAL07 route policy
 *    arbitrates) — the ring contract is the stable seam.
 *
 * MP-HAL07 CLEAN SWITCHING: [start]/[stop] are idempotent and own every
 * resource they create — feeder thread (joined, bounded), publisher
 * worker (stopped, joined), output stream (released), native ring handle
 * (destroyed in a finally). A route flip can never leak a native stream
 * or a thread handle.
 */
class RustAudioPipeline(
    private val ringFactory: () -> FloatSinkRing,
    private val outputFactory: () -> PullAudioOutput,
    private val clockRecorder: (com.streamify.app.data.uniffi.DacPresentationClock) -> Unit = {},
    /** Feeder burst size in frames (~21 ms @ 48 kHz). */
    private val burstFrames: Int = DEFAULT_BURST_FRAMES,
    /** Timestamp sampling cadence: one sample every N feeder bursts. */
    private val clockSampleInterval: Int = CLOCK_SAMPLE_WRITE_INTERVAL,
) {

    // ── Lifecycle state ────────────────────────────────────────────────────

    @Volatile private var started = false
    @Volatile private var playing = false
    @Volatile private var feederRun = false

    private var feeder: Thread? = null

    /** The live ring, once started (producer edge hands this to writers). */
    @Volatile var ring: FloatSinkRing? = null
        private set

    private val bus = DacClockSampleBus()

    /**
     * One publisher worker per pipeline instance — started/stopped with
     * it. Its recorder hook rides the non-real-time worker thread: the
     * engine record call AND the telemetry StateFlow refresh (never the
     * feeder — the zero-allocation law).
     */
    private val publisher = DacClockPublisher(bus) { clockSample ->
        clockRecorder(clockSample)
        _telemetry.value = snapshotTelemetry()
    }

    private val generation = PipelineGeneration.next()

    // ── Telemetry surface (push-driven, dogfood visibility) ────────────────

    /** One pipeline health snapshot — pushed off the audio thread, never polled. */
    data class PipelineTelemetry(
        val generation: Long,
        val framesPulled: Long,
        /** Feeder-side starved bursts (ring returned 0 or short). */
        val feederUnderruns: Long,
        /** Feeder bursts fed with full data. */
        val fullBursts: Long,
        val dacClockSamples: Long,
        val playing: Boolean,
        val tornOutput: Boolean,
    )

    private val framesPulled = AtomicLong(0L)
    private val feederUnderruns = AtomicLong(0L)
    private val fullBursts = AtomicLong(0L)

    private val _telemetry = MutableStateFlow(
        PipelineTelemetry(generation, 0L, 0L, 0L, 0L, playing = false, tornOutput = false)
    )

    /** Latest pipeline telemetry (conflated; zero-polling — push-only). */
    val telemetry: StateFlow<PipelineTelemetry> = _telemetry.asStateFlow()

    @Volatile private var tornOutput = false

    /**
     * Latest hardware DAC clock (reactive; null until the first sample).
     */
    val dacClock: StateFlow<com.streamify.app.data.uniffi.DacPresentationClock?>
        get() = publisher.clock

    // ── Lifecycle ──────────────────────────────────────────────────────────

    /**
     * Brings the pipeline up: creates the ring + output, starts the DAC
     * clock publisher, then spawns the urgent-priority feeder. Idempotent
     * — a second call while live is a logged no-op (MP-HAL07 switching
     * safety).
     */
    @Synchronized
    fun start(): Boolean {
        if (started) {
            SLog.w(TAG, "gen#$generation start ignored: already running")
            return true
        }
        val newRing = try {
            ringFactory()
        } catch (e: Throwable) {
            SLog.e(TAG, "gen#$generation ring factory failed", e)
            return false
        }
        val newOutput = try {
            outputFactory()
        } catch (e: Throwable) {
            SLog.e(TAG, "gen#$generation output factory failed", e)
            newRing.release()
            return false
        }
        publisher.start()

        ring = newRing
        output = newOutput
        tornOutput = false
        framesPulled.set(0L)
        feederUnderruns.set(0L)
        fullBursts.set(0L)

        feederRun = true
        playing = true
        feeder = Thread({ feederLoop(newRing, newOutput) }, "streamify-audio-feeder-$generation").apply {
            isDaemon = true
            start()
        }
        started = true
        newOutput.play()
        SLog.i(TAG, "gen#$generation started: burst=${burstFrames}f clockEvery=${clockSampleInterval}x")
        return true
    }

    private var output: PullAudioOutput? = null

    /** Resumes playout (idempotent). */
    @Synchronized
    fun play() {
        if (!started) return
        playing = true
        output?.play()
        wakeFeeder()
    }

    /**
     * Pauses playout: the platform stream parks (its room-wait releases
     * the feeder into the parked gate below), buffered ring content is
     * RETAINED — resume continues where the DAC stopped.
     */
    @Synchronized
    fun pause() {
        if (!started) return
        playing = false
        output?.pause()
        wakeFeeder()
    }

    /**
     * Cooperative flush of the ring (drop buffered audio — seek /
     * discontinuity). Producer/control-thread semantics per the C++
     * contract: the feeder (consumer) applies the cursor reset at the
     * top of its next read. NEVER called from the feeder itself.
     */
    fun flush() {
        ring?.requestFlush()
        wakeFeeder()
    }

    /**
     * Tears the pipeline down completely: feeder parked + joined
     * (bounded), publisher stopped + joined, platform stream released,
     * native ring handle destroyed — in a finally, so no path leaks a
     * native stream or thread (MP-HAL07). Idempotent.
     */
    @Synchronized
    fun stop() {
        if (!started) return
        started = false
        playing = false
        feederRun = false
        wakeFeeder()
        feeder?.let { f ->
            try {
                f.join(FEEDER_JOIN_TIMEOUT_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        feeder = null
        try {
            publisher.stop()
        } catch (e: Throwable) {
            SLog.w(TAG, "gen#$generation publisher stop threw", e)
        }
        try {
            output?.release()
        } catch (e: Throwable) {
            SLog.w(TAG, "gen#$generation output release threw", e)
        } finally {
            output = null
        }
        try {
            ring?.release()
        } catch (e: Throwable) {
            SLog.w(TAG, "gen#$generation ring release threw", e)
        } finally {
            ring = null
        }
        SLog.i(
            TAG,
            "gen#$generation stopped: pulled=${framesPulled.get()}f " +
                "underruns=${feederUnderruns.get()} clockSamples=${bus.publishedSamples()}"
        )
    }

    // ── The pull feeder ────────────────────────────────────────────────────

    /**
     * The consumer edge of the SPSC ring — the closest app-side analog of
     * Alpha's `AAudioStream_dataCallback`. Steady-state per burst:
     * ONE ring read (JNI downcall), ONE platform write (blocks in the
     * mixer's room-wait = the pull), zero allocations, zero upcalls.
     */
    private fun feederLoop(ring: FloatSinkRing, output: PullAudioOutput) {
        try {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
        } catch (_: Throwable) {
            // Priority is best-effort on locked-down devices.
        }

        // Preallocated ONCE for the lifetime of the feeder — the chunk
        // is reused for every burst (zero-allocation law).
        val chunk = FloatArray(burstFrames * AudioTrackPullOutput.CANONICAL_CHANNELS)
        var burstsSinceClockSample = clockSampleInterval // force an immediate first sample

        while (feederRun) {
            val frames = ring.readFramesF32(chunk, burstFrames)

            if (frames <= 0 && !playing) {
                // Paused AND starved: park until a producer write /
                // resume / stop wakes us. (While paused with ring data,
                // the drain below continues — the platform stream's own
                // room-wait backpressure parks us inside writeBlocking
                // once its buffer is full, so the producer can never
                // wedge on a full ring.) This is a condition wait, NOT
                // a polling loop — the only wake sources are push
                // events on this pipeline.
                parkFeeder()
                continue
            }

            if (frames < burstFrames) {
                // Starved (0 or short) while playing: zero-fill the
                // remainder so the DAC clock keeps advancing and the
                // stream stays warm — the standard HAL-callback
                // starvation behavior.
                java.util.Arrays.fill(
                    chunk, frames.coerceAtLeast(0) * AudioTrackPullOutput.CANONICAL_CHANNELS,
                    chunk.size, 0f
                )
                if (frames <= 0) feederUnderruns.incrementAndGet() else fullBursts.incrementAndGet()
            } else {
                fullBursts.incrementAndGet()
            }

            val wrote = output.writeBlocking(chunk, 0, burstFrames)
            if (wrote < 0) {
                // Hard platform error: the feeder RETIRES (a torn stream
                // can never be un-torn from the pull side; telemetry keeps
                // the tornOutput marker and the route policy / user
                // action owns recovery through a fresh pipeline). Exiting
                // beats parking here — a parked feeder on a dead stream
                // would hot-loop between gate and write.
                tornOutput = true
                SLog.e(TAG, "gen#$generation feeder: output hard error $wrote — retiring")
                break
            }
            framesPulled.addAndGet(burstFrames.toLong())

            // Hardware presentation sampling (MP-HAL06): reuse-guarded,
            // seqlock-published, signalled — the publisher worker does
            // every expensive thing (StateFlow, engine FFI).
            if (feederRun && --burstsSinceClockSample <= 0) {
                burstsSinceClockSample = clockSampleInterval
                output.timestamp()?.let { sample ->
                    DacClockTranslator.toDacClock(
                        sample,
                        AudioTrackPullOutput.CANONICAL_SAMPLE_RATE_HZ,
                    )?.let { clockSample ->
                        bus.publish(
                            clockSample.dacFramePosition.toLong(),
                            clockSample.sampleRate.toInt(),
                            clockSample.monotonicNs.toLong(),
                        )
                        publisher.signal()
                    }
                }
            }
        }
    }

    /**
     * Feeder park latch — notified by resume/flush/stop/producer signals.
     * (ReentrantLock + Condition rather than Object.wait/notifyAll: the
     * latter are not first-class on Kotlin's Any and the lock/condition
     * pair keeps the park/wake protocol explicit.)
     */
    private val feederGate = java.util.concurrent.locks.ReentrantLock()
    private val feederGateCondition = feederGate.newCondition()

    private fun parkFeeder() {
        feederGate.lock()
        try {
            if (!feederRun || playing) return
            try {
                feederGateCondition.await(FEEDER_PARK_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        } finally {
            feederGate.unlock()
        }
    }

    private fun wakeFeeder() {
        feederGate.lock()
        try {
            feederGateCondition.signalAll()
        } finally {
            feederGate.unlock()
        }
    }

    /**
     * Producer-edge hook: called by [RustMedia3AudioSink] after ring
     * writes so a parked (paused + starved) feeder wakes, drains the new
     * data and re-parks — push semantics end-to-end, no polling.
     */
    fun onProducerActivity() {
        wakeFeeder()
    }

    /** Snapshot of the live telemetry counters (thread-safe). */
    fun snapshotTelemetry(): PipelineTelemetry = PipelineTelemetry(
        generation = generation,
        framesPulled = framesPulled.get(),
        feederUnderruns = feederUnderruns.get(),
        fullBursts = fullBursts.get(),
        dacClockSamples = bus.publishedSamples(),
        playing = playing,
        tornOutput = tornOutput,
    )

    companion object {
        const val TAG = "RustAudioPipeline"

        /**
         * Feeder burst: 1024 frames ≈ 21.3 ms @ 48 kHz — small enough for
         * the low-latency regime, large enough to amortize the JNI
         * downcall to a rounding error.
         */
        const val DEFAULT_BURST_FRAMES = 1024

        /**
         * DAC clock sampling cadence: every 2nd burst ≈ 43 ms ≈ 23 Hz —
         * inside the 10–50 Hz window the hardware-latency profiler
         * recommends for AudioTimestamp feeds.
         */
        const val CLOCK_SAMPLE_WRITE_INTERVAL = 2

        private const val FEEDER_JOIN_TIMEOUT_MS = 1_000L

        /**
         * Park safety cap: with push signals this never fires in steady
         * state; it exists so a lost wake-up degrades to a cheap re-check
         * rather than an eternal sleep. NOT engine-state polling.
         */
        private const val FEEDER_PARK_TIMEOUT_MS = 2_000L
    }
}
