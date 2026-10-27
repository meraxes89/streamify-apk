package com.streamify.app.media.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import com.streamify.app.util.SLog
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicLong

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * Float-sink seams — the injectable edges of the Phase 4a pull pipeline
 * (MP-HAL05 / MP-HAL06, Beta subtree).
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * Everything the [RustAudioPipeline] touches that is either (a) a native
 * JNI edge or (b) an Android framework audio class sits behind one of the
 * seams in this file, so the whole pipeline — feeder thread, DAC clock
 * sampling, underrun handling, cooperative flush, teardown — is provable
 * in hermetic JVM unit tests with in-memory doubles
 * (`app/src/test/.../media/audio/RustAudioPipelineTest.kt`).
 *
 * SEAM MAP
 *   [FloatSinkRing]       the SPSC f32 ring (native edge via [NativeFloatSinkEdge])
 *   [PullAudioOutput]     the platform sink (AudioTrack f32 low-latency facade)
 *   [MonotonicClockSource] CLOCK_MONOTONIC reads (System.nanoTime in prod)
 *   [AudioTimestampSample] one hardware presentation timestamp triple
 */

/**
 * The single-producer single-consumer interleaved-f32 ring — app-side
 * mirror of the C++ `AudioSink` contract
 * (`native/include/audio_sink_ringbuffer.h`).
 *
 * PRODUCER (Media3 renderer thread, via [RustMedia3AudioSink]):
 * [writeSamples] is non-blocking, whole-frames-only and may return short
 * (ring backpressure — retry the remainder). CONSUMER (the pipeline's
 * pull feeder): [readFramesF32] returns 0 on starvation (one underrun
 * event inside the ring). [requestFlush] is the cooperative generational
 * flush — producer/control threads only, never the consumer thread.
 */
interface FloatSinkRing {
    /**
     * Writes up to `sampleCount` interleaved float samples starting at
     * `src[offset]`. Returns the input samples actually consumed (0 ==
     * ring full / closed).
     */
    fun writeSamples(src: FloatArray, offset: Int, sampleCount: Int): Int

    /**
     * Reads up to `maxFrames` interleaved f32 frames into `dst` (needs
     * `maxFrames * channels` floats of room). Returns frames read; 0 ==
     * starved.
     */
    fun readFramesF32(dst: FloatArray, maxFrames: Int): Int

    /** Cooperative flush (drop buffered audio); see the C++ header contract. */
    fun requestFlush()

    /** Live stats snapshot, when the edge exposes them; null when degraded. */
    fun stats(): RustAudioSinkJni.SinkStatsSnapshot? = null

    /** Releases the underlying native handle. Idempotent. */
    fun release()
}

/**
 * Production ring edge over the frozen JNI contract via the main-tree
 * seam ([RustAudioSinkJni] — the dogfood binding installs itself through
 * the AppGraph reflection bootstrap; absent binding = closed ring).
 * Every native call is funneled through the seam — a torn or absent
 * binding reports as a closed ring (reads: starvation, writes: 0)
 * instead of crashing the renderer or the feeder.
 *
 * @param handle the opaque registry handle returned by the seam's
 *        createSink (negative == creation failed -> the ring is born
 *        closed).
 */
class NativeFloatSinkEdge(
    private val handle: Long,
    private val channels: Int,
) : FloatSinkRing {

    val isOpen: Boolean get() = handle >= 0 && !released

    @Volatile
    private var released = false

    override fun writeSamples(src: FloatArray, offset: Int, sampleCount: Int): Int {
        if (!isOpen || sampleCount <= 0) return 0
        val jni = RustAudioSinkJni.binding ?: return 0
        return try {
            jni.writeFloat(handle, src, offset, sampleCount)
        } catch (e: UnsatisfiedLinkError) {
            SLog.w(TAG, "nativeWriteFloat edge torn; ring closed", e)
            released = true
            0
        }.coerceAtLeast(0)
    }

    override fun readFramesF32(dst: FloatArray, maxFrames: Int): Int {
        if (!isOpen || maxFrames <= 0) return 0
        val jni = RustAudioSinkJni.binding ?: return 0
        val capped = if (channels > 0) minOf(maxFrames, dst.size / channels) else 0
        if (capped <= 0) return 0
        return try {
            jni.readFramesF32(handle, dst, capped)
        } catch (e: UnsatisfiedLinkError) {
            SLog.w(TAG, "nativeReadFramesF32 edge torn; ring closed", e)
            released = true
            0
        }.coerceAtLeast(0)
    }

    override fun requestFlush() {
        if (!isOpen) return
        val jni = RustAudioSinkJni.binding ?: return
        try {
            jni.flush(handle)
        } catch (e: UnsatisfiedLinkError) {
            SLog.w(TAG, "nativeFlush edge torn", e)
        }
    }

    override fun stats(): RustAudioSinkJni.SinkStatsSnapshot? {
        if (!isOpen) return null
        val jni = RustAudioSinkJni.binding ?: return null
        return try {
            jni.getStats(handle, statsWire)
            RustAudioSinkJni.decodeStats(statsWire)
        } catch (e: UnsatisfiedLinkError) {
            null
        }
    }

    override fun release() {
        if (released) return
        released = true
        if (handle >= 0) {
            val jni = RustAudioSinkJni.binding
            if (jni != null) {
                try {
                    jni.destroy(handle)
                } catch (e: UnsatisfiedLinkError) {
                    SLog.w(TAG, "nativeDestroy edge torn", e)
                }
            }
        }
    }

    private val statsWire: ByteBuffer =
        ByteBuffer.allocateDirect(RustAudioSinkJni.STATS_WIRE_BYTES).order(ByteOrder.LITTLE_ENDIAN)

    private companion object {
        const val TAG = "NativeFloatSinkEdge"
    }
}

/**
 * One hardware presentation timestamp sample — the AudioTrack/
 * AAudio `AudioTimestamp` triple (MP-HAL06).
 *
 * @param framePosition cumulative frames PRESENTED to the DAC by this
 *        output stream (monotonic per stream — the u64
 *        `DacPresentationClock.dac_frame_position` source).
 * @param nanoTime the OS monotonic instant of the presentation,
 *        CLOCK_MONOTONIC — the `AudioTimestamp.nanoTime` source. NEVER
 *        `System.currentTimeMillis()` (wall clock is banned from every
 *        position estimate, master plan invariant 3).
 */
data class AudioTimestampSample(
    val framePosition: Long,
    val nanoTime: Long,
)

/**
 * The platform pull sink — the AudioTrack facade over the same
 * AudioFlinger/AAudio mixer that the C++ `AAudioStream_dataCallback`
 * path rides (MP-HAL05). Implementations MUST be safe to call
 * `writeBlocking` from one dedicated feeder thread.
 */
interface PullAudioOutput {
    /** Starts (or resumes) playout of previously written audio. */
    fun play()

    /** Pauses playout; written-but-unplayed audio is retained. */
    fun pause()

    /**
     * Writes `frameCount` interleaved f32 frames BLOCKING — the platform
     * mixer's pull backpressure point. Returns frames actually accepted
     * (== frameCount on the happy path) or a NEGATIVE sentinel on a hard
     * output error (feeder treats it as a torn output).
     */
    fun writeBlocking(src: FloatArray, offsetFrames: Int, frameCount: Int): Int

    /** True when the output still holds un-played frames. */
    fun hasPendingData(): Boolean

    /**
     * Reads the latest hardware presentation timestamp; null while the
     * stream has not presented any frames yet (or the platform refuses
     * the query — both are normal startup states, not errors).
     */
    fun timestamp(): AudioTimestampSample?

    /** Immediate teardown of the platform stream. Idempotent. */
    fun release()
}

/**
 * Production [PullAudioOutput] — a STRICTLY-configured
 * `AudioTrack`: 48,000 Hz, 2 channels, `ENCODING_PCM_FLOAT`,
 * `PERFORMANCE_MODE_LOW_LATENCY` (the Java facade of
 * `AAUDIO_PERFORMANCE_MODE_LOW_LATENCY`; sharing mode is
 * `AAUDIO_SHARING_MODE_SHARED` by construction — the Java AudioTrack API
 * has no exclusive mode, which stays reserved for Alpha's C++ Oboe edge).
 *
 * The blocking [writeBlocking] IS the pull semantics: the feeder thread
 * parks inside the platform mixer's room-wait and wakes exactly when the
 * DAC consumed a burst — no timers, no polling, the wake cadence is
 * hardware-driven (zero-polling law, MP-DEC-04, applied to the data
 * plane).
 */
class AudioTrackPullOutput(
    private val sampleRate: Int = CANONICAL_SAMPLE_RATE_HZ,
    private val channels: Int = CANONICAL_CHANNELS,
    /** Ring-buffer capacity of the platform stream, in frames. */
    private val bufferFrames: Int = DEFAULT_TRACK_BUFFER_FRAMES,
) : PullAudioOutput {

    private var track: AudioTrack? = null

    /** Reused timestamp container — zero allocation per sample. */
    private val reusableTimestamp = android.media.AudioTimestamp()

    @Volatile
    private var lastHardError: Int = 0

    /** Snapshot of the last hard write error code (telemetry/diagnostics). */
    fun lastHardErrorCode(): Int = lastHardError

    private fun ensureTrack(): AudioTrack? {
        track?.let { return it }
        val frameBytes = channels * java.lang.Float.BYTES
        val minBytes = try {
            AudioTrack.getMinBufferSize(
                sampleRate,
                if (channels == 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_FLOAT,
            )
        } catch (_: Throwable) {
            AudioTrack.ERROR_BAD_VALUE
        }
        // Room for at least the platform minimum, then our burst window.
        val capacityBytes = maxOf(
            bufferFrames * frameBytes,
            if (minBytes > 0) minBytes else DEFAULT_TRACK_BUFFER_FRAMES * frameBytes,
        )
        return try {
            val built = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(
                            if (channels == 2) AudioFormat.CHANNEL_OUT_STEREO
                            else AudioFormat.CHANNEL_OUT_MONO
                        )
                        .build()
                )
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(capacityBytes)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .build()
            if (built.state != AudioTrack.STATE_INITIALIZED) {
                SLog.e(TAG, "AudioTrack failed to initialize: state=${built.state}")
                built.release()
                return null
            }
            track = built
            built
        } catch (e: Throwable) {
            SLog.e(TAG, "AudioTrack construction failed", e)
            null
        }
    }

    override fun play() {
        try {
            ensureTrack()?.play()
        } catch (e: Throwable) {
            SLog.e(TAG, "AudioTrack.play failed", e)
        }
    }

    override fun pause() {
        try {
            track?.pause()
        } catch (e: Throwable) {
            SLog.e(TAG, "AudioTrack.pause failed", e)
        }
    }

    override fun writeBlocking(src: FloatArray, offsetFrames: Int, frameCount: Int): Int {
        val t = ensureTrack() ?: return WRITE_ERROR_TORN
        var writtenFloats = 0
        val totalFloats = frameCount * channels
        val base = offsetFrames * channels
        while (writtenFloats < totalFloats) {
            val accepted = try {
                t.write(
                    src,
                    base + writtenFloats,
                    totalFloats - writtenFloats,
                    AudioTrack.WRITE_BLOCKING,
                )
            } catch (e: Throwable) {
                SLog.e(TAG, "AudioTrack.write threw", e)
                return WRITE_ERROR_TORN
            }
            if (accepted < 0) {
                lastHardError = accepted
                SLog.e(TAG, "AudioTrack.write hard error $accepted")
                return accepted
            }
            writtenFloats += accepted
        }
        framesAccepted.addAndGet(frameCount.toLong())
        return frameCount
    }

    /** Cumulative frames this output accepted (feeder-side write cursor). */
    private val framesAccepted = AtomicLong(0L)

    override fun hasPendingData(): Boolean {
        // Hardware-grounded pending measure: frames we handed to the
        // platform minus frames the DAC has actually presented. A null
        // timestamp (stream too fresh) conservatively reports pending
        // whenever anything was written — never a premature "ended".
        val accepted = framesAccepted.get()
        if (accepted <= 0L) return false
        val presented = timestamp()?.framePosition ?: return true
        return accepted - presented > PENDING_GRACE_FRAMES
    }

    override fun timestamp(): AudioTimestampSample? {
        val t = track ?: return null
        return try {
            if (t.getTimestamp(reusableTimestamp)) {
                AudioTimestampSample(
                    framePosition = reusableTimestamp.framePosition,
                    nanoTime = reusableTimestamp.nanoTime,
                )
            } else {
                null
            }
        } catch (_: Throwable) {
            null
        }
    }

    override fun release() {
        try {
            track?.release()
        } catch (_: Throwable) {
            // Teardown must never throw.
        }
        track = null
    }

    companion object {
        const val TAG = "AudioTrackPullOutput"

        /** Canonical pipeline rate (MP-DEC-05): 48 kHz f32 stereo. */
        const val CANONICAL_SAMPLE_RATE_HZ = 48_000

        /** Canonical channel count: stereo interleaved. */
        const val CANONICAL_CHANNELS = 2

        /**
         * Platform stream room: 4096 frames ≈ 85 ms @ 48 kHz — four
         * feeder bursts of 1024 frames, deep enough to absorb a scheduler
         * hiccup on the feeder thread without an audible gap, shallow
         * enough to keep end-to-end latency in the low-latency regime.
         */
        const val DEFAULT_TRACK_BUFFER_FRAMES = 4096

        /** Sentinel for a torn/unavailable output (frames unit). */
        const val WRITE_ERROR_TORN = -1

        /**
         * Hardware pending tolerance: DAC presentation counters land in
         * burst quanta, so "presented == accepted" never matches exactly —
         * anything within one burst (1024 frames ≈ 21 ms) counts as
         * drained for end-of-stream accounting.
         */
        const val PENDING_GRACE_FRAMES = 1024L
    }
}

/**
 * CLOCK_MONOTONIC read seam. Production: [SystemMonotonicClock]
 * (`System.nanoTime` — monotonic, never the wall clock). Tests inject
 * scripted clocks to drive the VirtualDAC acceptance harness (MP-HAL06).
 */
fun interface MonotonicClockSource {
    fun nowNs(): Long
}

/** Production [MonotonicClockSource] — `System.nanoTime()`. */
object SystemMonotonicClock : MonotonicClockSource {
    override fun nowNs(): Long = System.nanoTime()
}

/**
 * Monotically-increasing handle issuer for pipeline lifecycle auditing
 * (dogfood telemetry: which pipeline generation produced a log line).
 */
internal object PipelineGeneration {
    private val counter = AtomicLong(0L)
    fun next(): Long = counter.incrementAndGet()
}
