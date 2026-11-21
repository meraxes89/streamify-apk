package com.streamify.app.media.audio

import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.PlaybackParameters
import androidx.media3.exoplayer.audio.AudioSink
import com.streamify.app.util.SLog
import java.nio.ByteBuffer

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * RustMedia3AudioSink — Media3 `AudioSink` adapter onto the native f32 SPSC
 * ring (Phase 4a / MP-HAL05 + MP-HAL07, Beta subtree).
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * THE CUTOVER SEAM. Media3's renderer chain calls
 * `DefaultRenderersFactory.buildAudioSink()` when it builds the audio
 * renderer — [com.streamify.app.media.playback.PlaybackService] routes
 * that call through [AudioRoutePolicy]: with `USE_RUST_AUDIO` on (and the
 * native edges present) it returns THIS adapter; otherwise the untouched
 * `DefaultAudioSink` pipeline (graceful fallback, MP-HAL07).
 *
 * SIGNAL PATH (USE_RUST_AUDIO = true):
 *
 *   MediaCodec decode → handleBuffer(ByteBuffer)
 *     → [PcmIngestResampler]   PCM16/f32, mono/stereo, any rate →
 *                              canonical 48 kHz f32 stereo
 *     → FloatSinkRing.writeSamples   (native SPSC ring, producer edge)
 *     → RustAudioPipeline feeder     (urgent-priority pull thread)
 *     → AudioTrack ENCODING_PCM_FLOAT, PERFORMANCE_MODE_LOW_LATENCY
 *     → DAC  →  AudioTrack.getTimestamp()
 *     → DacClockSampleBus/DacClockPublisher → record_dac_clock (engine)
 *
 * POSITION (MP-HAL06): [getCurrentPositionUs] derives from the hardware
 * DAC presentation clock — the estimator interpolates between timestamp
 * samples on the monotonic domain, anchored at each segment's first
 * presented frame. No wall clock ever enters the math.
 *
 * BACKPRESSURE: [handleBuffer] returns false while the ring cannot take
 * the converted block; Media3 re-offers the SAME buffer and the adapter
 * resumes from its persisted staging cursor (idempotent re-extraction,
 * zero re-conversion).
 */
class RustMedia3AudioSink(
    private val pipeline: RustAudioPipeline,
    private val clock: MonotonicClockSource = SystemMonotonicClock,
) : AudioSink {

    // ── Configuration state ────────────────────────────────────────────────

    private var listener: AudioSink.Listener? = null

    @Volatile private var configured = false
    private var inputSampleRate = 0
    private var inputChannels = 0
    private var inputIsPcm16 = true

    @Volatile private var resampler: PcmIngestResampler? = null

    /** Last configured input Format (exception payloads need non-null). */
    @Volatile private var currentFormat: Format = Format.Builder().build()

    /** Current playback speed — folded into the resample ratio. */
    @Volatile private var speed = 1f

    /** Live playback parameters (thread-safe; speed is the honored axis). */
    private val playbackParametersField =
        java.util.concurrent.atomic.AtomicReference(PlaybackParameters(1f))

    /** Media3 audio attributes mirror (volume routing done by the track). */
    private var audioAttributes: AudioAttributes? = null

    private var volume = 1f
    private var skipSilence = false

    // ── Position anchoring (MP-HAL06) ──────────────────────────────────────

    /** Media pts (µs) of the current segment's first ingested buffer. */
    private var segmentStartMediaUs = 0L

    /** Output frames fed BEFORE the current segment (DAC anchor). */
    private var framesFedAtSegmentStart = 0L

    /** Cumulative canonical output frames pushed into the ring. */
    private val framesFedTotal = java.util.concurrent.atomic.AtomicLong(0L)

    /** Set by flush/discontinuity: the next buffer re-anchors the segment. */
    @Volatile private var pendingAnchor = true

    /** Highest media pts (µs) offered so far (end-of-stream clamp). */
    private var lastQueuedMediaUs = 0L

    /** Converted-but-not-yet-ring-written canonical f32 frames. */
    private var staging = FloatArray(0)

    /** Float index of the next unwritten staging sample. */
    private var stagingCursor = 0

    private val stagingLock = Any()

    // ── AudioSink: format negotiation ─────────────────────────────────────

    override fun supportsFormat(format: Format): Boolean =
        getFormatSupport(format) != AudioSink.SINK_FORMAT_UNSUPPORTED

    override fun getFormatSupport(format: Format): Int {
        val pcmEncoding = format.pcmEncoding
        val isPcm = pcmEncoding == C.ENCODING_PCM_16BIT || pcmEncoding == C.ENCODING_PCM_FLOAT
        if (!isPcm) return AudioSink.SINK_FORMAT_UNSUPPORTED
        if (format.channelCount !in 1..2) return AudioSink.SINK_FORMAT_UNSUPPORTED
        if (format.sampleRate <= 0) return AudioSink.SINK_FORMAT_UNSUPPORTED
        return AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY
    }

    // ── AudioSink: lifecycle ───────────────────────────────────────────────

    override fun setListener(listener: AudioSink.Listener) {
        this.listener = listener
    }

    @Throws(AudioSink.ConfigurationException::class)
    override fun configure(
        inputFormat: Format,
        specifiedBufferSize: Int,
        outputChannels: IntArray?,
    ) {
        val pcmEncoding = inputFormat.pcmEncoding
        if (pcmEncoding != C.ENCODING_PCM_16BIT && pcmEncoding != C.ENCODING_PCM_FLOAT) {
            throw AudioSink.ConfigurationException(
                "RustMedia3AudioSink supports PCM 16-bit/float only, got 0x" +
                    Integer.toHexString(pcmEncoding),
                inputFormat,
            )
        }
        currentFormat = inputFormat
        val rateChanged = inputSampleRate != inputFormat.sampleRate ||
            inputChannels != inputFormat.channelCount ||
            inputIsPcm16 != (pcmEncoding == C.ENCODING_PCM_16BIT)
        inputSampleRate = inputFormat.sampleRate
        inputChannels = inputFormat.channelCount
        inputIsPcm16 = pcmEncoding == C.ENCODING_PCM_16BIT
        if (rateChanged || resampler == null) {
            resampler = PcmIngestResampler(
                inputSampleRate = inputSampleRate,
                inputChannels = inputChannels,
                inputIsPcm16 = inputIsPcm16,
                speed = speed,
            )
            SLog.i(
                TAG,
                "configured: in=${inputSampleRate}Hz/${inputChannels}ch/" +
                    (if (inputIsPcm16) "pcm16" else "f32") +
                    " -> 48kHz/2ch/f32 (passthrough=${resampler?.isPassthrough == true})"
            )
        }
        configured = true
        // A fresh configuration implies a fresh segment (Media3 flushes
        // around reconfigures, but be self-sufficient).
        pendingAnchor = true
    }

    override fun play() {
        pipeline.play()
    }

    override fun pause() {
        pipeline.pause()
    }

    override fun handleDiscontinuity() {
        // Seek / stream jump: the resampler history is stale and the
        // position anchor must recapture on the next buffer.
        synchronized(stagingLock) {
            resampler?.reset()
            pendingAnchor = true
        }
    }

    // ── AudioSink: the producer hot path ──────────────────────────────────

    @Throws(AudioSink.InitializationException::class, AudioSink.WriteException::class)
    override fun handleBuffer(
        buffer: ByteBuffer,
        presentationTimeUs: Long,
        encodedAccessUnitCount: Int,
    ): Boolean {
        if (!configured) {
            throw AudioSink.InitializationException(
                /* audioTrackState = */ 0,
                /* sampleRate = */ inputSampleRate,
                /* channelConfig = */ inputChannels,
                /* bufferSize = */ 0,
                /* format = */ currentFormat,
                /* isRecoverable = */ false,
                /* error = */ IllegalStateException("handleBuffer before configure"),
            )
        }
        val ring = pipeline.ring
            ?: throw AudioSink.InitializationException(
                0, inputSampleRate, inputChannels, 0, currentFormat, false,
                IllegalStateException("rust audio pipeline not started"),
            )

        // Stage 1 (skip when a retry resumes from the staging cursor):
        // extract + convert the remaining input bytes into canonical f32.
        if (stagingCursor >= staging.size) {
            if (!buffer.hasRemaining()) {
                buffer.position(buffer.limit())
                return true
            }
            val conv = resampler
                ?: throw AudioSink.InitializationException(
                    0, inputSampleRate, inputChannels, 0, currentFormat, false,
                    IllegalStateException("no resampler configured"),
                )
            if (pendingAnchor) {
                segmentStartMediaUs = presentationTimeUs
                framesFedAtSegmentStart = framesFedTotal.get()
                pendingAnchor = false
            }
            if (presentationTimeUs > lastQueuedMediaUs) lastQueuedMediaUs = presentationTimeUs

            synchronized(stagingLock) {
                stagingCursor = 0
                var outArr = EmptyStaging
                var outLen = 0
                conv.ingestByteBuffer(buffer, buffer.position(), buffer.limit()) { arr, off, len ->
                    // The converter emits CHUNKED output and reuses its
                    // own buffers — append every chunk into the
                    // adapter-owned staging (ownership must not alias).
                    if (outLen + len > outArr.size) {
                        var size = maxOf(outArr.size, INITIAL_STAGING_FLOATS)
                        while (size < outLen + len) size *= 2
                        outArr = outArr.copyOf(size)
                    }
                    System.arraycopy(arr, off, outArr, outLen, len)
                    outLen += len
                }
                staging = if (outLen == outArr.size) outArr else outArr.copyOf(outLen)
            }
            if (staging.isEmpty()) {
                // Converter emitted nothing (e.g., tail guard): consume
                // the input to avoid an infinite retry loop.
                buffer.position(buffer.limit())
                return true
            }
        }

        // Stage 2: drain the staging into the ring with backpressure.
        var cursor = stagingCursor
        val channels = AudioTrackPullOutput.CANONICAL_CHANNELS
        var acceptedTotal = 0
        while (cursor < staging.size) {
            val accepted = ring.writeSamples(staging, cursor, staging.size - cursor)
            if (accepted <= 0) break
            cursor += accepted
            acceptedTotal += accepted
        }
        // Defensive: a trailing partial frame (converter contract says it
        // cannot happen) is dropped rather than wedging the cursor.
        val remainder = staging.size - cursor
        if (remainder > 0 && remainder < channels) {
            cursor = staging.size
        }
        stagingCursor = cursor
        val framesAccepted = acceptedTotal / channels
        if (framesAccepted > 0) {
            framesFedTotal.addAndGet(framesAccepted.toLong())
            pipeline.onProducerActivity()
        }

        return if (cursor >= staging.size) {
            stagingCursor = 0
            staging = EmptyStaging
            buffer.position(buffer.limit())
            true
        } else {
            false // ring full — Media3 re-offers this exact buffer
        }
    }

    override fun playToEndOfStream() {
        // Flush the converter's held tail frame into the ring (EOS: the
        // kernel's right-neighbor clamp degenerates exactly one kernel —
        // see PcmIngestResampler.flushTail). Ring-full rejection simply
        // drops the sub-frame tail; the feeder drains the rest.
        val ring = pipeline.ring ?: return
        val conv = resampler ?: return
        synchronized(stagingLock) {
            conv.flushTail { arr, off, len ->
                var cursor = off
                while (cursor < off + len) {
                    val accepted = ring.writeSamples(arr, cursor, off + len - cursor)
                    if (accepted <= 0) break
                    cursor += accepted
                }
                if (cursor > off) {
                    framesFedTotal.addAndGet(((cursor - off) / AudioTrackPullOutput.CANONICAL_CHANNELS).toLong())
                }
            }
        }
        pipeline.onProducerActivity()
        // Nothing else to force: the feeder drains the ring; isEnded()
        // reports the true drain state to the renderer.
    }

    override fun isEnded(): Boolean {
        val ring = pipeline.ring ?: return true
        val stats = ring.stats()
        val ringOccupancy = stats?.currentFullnessFrames ?: 0
        if (stagingCursor < staging.size) return false
        if (ringOccupancy > 0) return false
        return !pipelineStillAudible()
    }

    override fun hasPendingData(): Boolean {
        if (stagingCursor < staging.size) return true
        val ring = pipeline.ring ?: return false
        val stats = ring.stats() ?: return false
        return stats.currentFullnessFrames > 0
    }

    /** True when the platform stream still owes the listener audible frames. */
    private fun pipelineStillAudible(): Boolean {
        val fed = framesFedTotal.get()
        if (fed <= 0L) return false
        val clockSample = pipeline.dacClock.value ?: return true
        val presented = DacClockTranslator.estimateFramesPresented(
            clockSample,
            clock.nowNs(),
            writtenFramesCap = fed,
        )
        return fed - presented > AudioTrackPullOutput.PENDING_GRACE_FRAMES
    }

    // ── AudioSink: position (MP-HAL06 — hardware clock driven) ────────────

    override fun getCurrentPositionUs(sourceEnded: Boolean): Long {
        val fed = framesFedTotal.get()
        val clockSample = pipeline.dacClock.value
            ?: return if (sourceEnded) lastQueuedMediaUs else AudioSink.CURRENT_POSITION_NOT_SET
        val presented = DacClockTranslator.estimateFramesPresented(
            clockSample,
            clock.nowNs(),
            writtenFramesCap = fed,
        )
        // Segment anchor: clamp at the segment start until the DAC has
        // actually presented the segment's first frames.
        val anchorFrames = framesFedAtSegmentStart
        if (presented <= anchorFrames) {
            return segmentStartMediaUs.coerceAtLeast(0L)
        }
        val deltaFrames = presented - anchorFrames
        // Media-time advance: real-frame delta converted at the canonical
        // rate, divided by the speed the resampler folded in.
        val effectiveSpeed = if (speed > 0f && !speed.isNaN()) speed else 1f
        val deltaUs = (deltaFrames.toDouble() * 1_000_000.0 /
            (AudioTrackPullOutput.CANONICAL_SAMPLE_RATE_HZ.toDouble() * effectiveSpeed)).toLong()
        val position = segmentStartMediaUs + deltaUs
        if (sourceEnded && lastQueuedMediaUs > 0 && position > lastQueuedMediaUs) {
            return lastQueuedMediaUs
        }
        return position.coerceAtLeast(0L)
    }

    // ── AudioSink: parameters / attributes / no-op surfaces ───────────────

    override fun setPlaybackParameters(playbackParameters: PlaybackParameters) {
        val newSpeed = playbackParameters.speed
        if (newSpeed > 0f && !newSpeed.isNaN() && newSpeed != speed) {
            speed = newSpeed
            // Rebuild the converter at the same input config with the new
            // ratio (history reset — a discontinuity in the kernel beats
            // a pitched history carry-over).
            resampler = if (configured) {
                PcmIngestResampler(
                    inputSampleRate = inputSampleRate,
                    inputChannels = inputChannels,
                    inputIsPcm16 = inputIsPcm16,
                    speed = speed,
                )
            } else {
                null
            }
        }
        playbackParametersField.set(playbackParameters)
    }

    override fun getPlaybackParameters(): PlaybackParameters = playbackParametersField.get()

    override fun setSkipSilenceEnabled(enabled: Boolean) {
        skipSilence = enabled
    }

    override fun getSkipSilenceEnabled(): Boolean = skipSilence

    override fun setAudioAttributes(audioAttributes: AudioAttributes) {
        this.audioAttributes = audioAttributes
    }

    override fun getAudioAttributes(): AudioAttributes? = audioAttributes

    override fun setAudioSessionId(audioSessionId: Int) {
        // The dogfood track owns its session; no routing today.
    }

    override fun setAuxEffectInfo(auxEffectInfo: androidx.media3.common.AuxEffectInfo) {
        // No aux chain on the dogfood path.
    }

    override fun enableTunnelingV21() {
        // Tunneling is a DefaultAudioSink/offload feature; the pull path
        // runs untunneled by design.
    }

    override fun disableTunneling() {
        // Symmetric no-op with enableTunnelingV21().
    }

    override fun setVolume(volume: Float) {
        this.volume = volume.coerceIn(0f, 1f)
        // The engine's set_volume (control plane) and the platform
        // stream's output gain are independent surfaces; the dogfood
        // track stays unity and Media3's volume rides the ring data.
    }

    override fun flush() {
        // Drop everything not yet presented: staging, ring, converter
        // history, position anchor. Called from a control thread — the
        // cooperative ring flush is exactly the supported shape.
        synchronized(stagingLock) {
            stagingCursor = 0
            staging = EmptyStaging
            resampler?.reset()
            pendingAnchor = true
        }
        pipeline.flush()
    }

    override fun reset() {
        flush()
    }

    override fun release() {
        // The pipeline's lifecycle is owned by the route controller
        // (PlaybackService); the sink only sheds its own staging state.
        synchronized(stagingLock) {
            stagingCursor = 0
            staging = EmptyStaging
            resampler = null
            configured = false
        }
    }

    private companion object {
        const val TAG = "RustMedia3AudioSink"
        const val INITIAL_STAGING_FLOATS = 8192
        val EmptyStaging = FloatArray(0)
    }
}
