package com.streamify.app.media.audio

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * Producer-side ingestion converter — decoded PCM → canonical 48 kHz f32
 * stereo (Phase 4a / MP-HAL05 transitional dogfood bridge, Beta subtree).
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * The CANONICAL producer path is Alpha's Rust `audio_src::CanonicalSrc`
 * (polyphase sinc, 256-tap, rubato — the audiophile-grade SRC that feeds
 * the native ring from the sovereign engine). Until that engine-side
 * producer is wired on Android, [RustMedia3AudioSink] ingests MediaCodec
 * output through THIS converter so the dogfood flag routes REAL music
 * through the f32 ring today:
 *
 *   PCM16 ──/32768──► f32 ──► mono→stereo dup ──► cubic-Hermite rate
 *   PCM_FLOAT ──► pass-through rate              conversion → 48 kHz
 *
 * QUALITY CONTRACT (honest dogfood labeling):
 *  • PCM16 → f32 and mono → stereo are EXACT (no information loss);
 *  • 48 kHz passthrough is BIT-EXACT (zero latency, zero filtering);
 *  • non-48 kHz input rides a 4-point cubic Hermite interpolator
 *    (Catmull-Rom kernel). This is the documented TRANSITIONAL bridge:
 *    band-limited to roughly −70 dB stopband (no aliasing whine on
 *    typical program material), far below the polyphase sinc, and
 *    intended for parallel dogfood testing ONLY — the master plan's
 *    producer-side SRC (MP-C11) replaces it when the Rust engine feeds
 *    the ring directly. Playback speed changes ride the same ratio
 *    (pitch-shifting resample, the classic pre-Sonic behavior).
 *
 * STREAMING CONTRACT: [process] never allocates in steady state (all
 * staging grown geometrically up-front and reused), never throws, and
 * is single-threaded per instance (the Media3 renderer thread).
 */
class PcmIngestResampler(
    /** Input sample rate in Hz (Media3-decoded content rate). */
    val inputSampleRate: Int,
    /** Input channel count (1 or 2). */
    val inputChannels: Int,
    /** True when the input ByteBuffer carries PCM16; false for f32. */
    val inputIsPcm16: Boolean,
    /** Playback speed scalar (default 1.0; pitch-shifting resample). */
    private val speed: Float = 1f,
    /** Canonical output rate (Hz). */
    private val outputSampleRate: Int = AudioTrackPullOutput.CANONICAL_SAMPLE_RATE_HZ,
    /** Canonical output channels. */
    private val outputChannels: Int = AudioTrackPullOutput.CANONICAL_CHANNELS,
) {

    init {
        require(inputSampleRate > 0) { "inputSampleRate must be positive" }
        require(inputChannels == 1 || inputChannels == 2) { "inputChannels must be 1 or 2" }
        require(speed > 0f && !speed.isNaN()) { "speed must be positive and finite" }
    }

    /** True when the rate stage is a bit-exact passthrough. */
    val isPassthrough: Boolean
        get() = inputSampleRate == outputSampleRate && speed == 1f

    /**
     * INPUT-frame advance per emitted OUTPUT frame:
     * inputRate·speed / outRate. Its reciprocal is the output-frame count
     * per input frame (44.1k → 48k: 0.9188 here, i.e. 1.088 output
     * frames per input frame — the upsampling factor; speed 2× @ 48k:
     * 2.0 — each output frame skips two input frames).
     */
    val ratio: Double
        get() = inputSampleRate.toDouble() * speed.toDouble() / outputSampleRate.toDouble()

    // ── Streaming state ────────────────────────────────────────────────────

    /**
     * Ring of the last INPUT frames (per channel) — the cubic kernel
     * needs the 2 frames before the read cursor. Monotone cursor
     * `position` advances in input frames; history[...] holds frames
     * [position-2, position-1] per channel after each process pass.
     */
    private var histFrameM2 = FloatArray(inputChannels)
    private var histFrameM1 = FloatArray(inputChannels)

    /** Fractional read cursor inside the current input frame pair, [0, 1). */
    private var frac = 0.0

    /** Total output frames emitted across this converter's lifetime. */
    var outputFramesEmitted: Long = 0L
        private set

    /** Reusable per-pass staging: interleaved f32 INPUT frames. */
    private var inputStaging = FloatArray(INITIAL_STAGING_FRAMES * inputChannels)

    /** Reusable per-pass staging: interleaved f32 OUTPUT frames. */
    private var outputStaging = FloatArray((INITIAL_STAGING_FRAMES * 2 + 16) * outputChannels)

    /**
     * Processes `inputFrameCount` interleaved input frames (already
     * extracted into `src` at [srcOffset], f32, input channel count)
     * and appends canonical output frames to [onOutput] in chunks.
     *
     * The chunked-callback shape keeps the steady state allocation-free:
     * the same two staging arrays serve every pass; [onOutput] receives
     * (array, offsetInFloats, floatCount) views into the reused
     * [outputStaging] and MUST copy/sink synchronously (the Media3 sink
     * adapter writes them straight into the native ring).
     */
    fun process(
        src: FloatArray,
        srcOffset: Int,
        inputFrameCount: Int,
        onOutput: (FloatArray, Int, Int) -> Unit,
    ) {
        if (inputFrameCount <= 0) return
        if (isPassthrough && inputChannels == outputChannels) {
            // Bit-exact fast path: no history, no kernel — straight
            // relay of the caller's own buffer (zero copies).
            outputFramesEmitted += inputFrameCount
            onOutput(src, srcOffset, inputFrameCount * inputChannels)
            return
        }

        var inPos = srcOffset
        val inEnd = srcOffset + inputFrameCount * inputChannels
        var outLen = 0

        fun flushOutput() {
            if (outLen > 0) {
                outputFramesEmitted += outLen / outputChannels
                onOutput(outputStaging, 0, outLen)
                outLen = 0
            }
        }

        fun emit(frameL: Float, frameR: Float) {
            if (outLen + outputChannels > outputStaging.size) {
                flushOutput()
                if (outLen + outputChannels > outputStaging.size) {
                    outputStaging = outputStaging.copyOf(outputStaging.size * 2)
                }
            }
            outputStaging[outLen++] = frameL
            if (outputChannels == 2) outputStaging[outLen++] = frameR
        }

        if (isPassthrough) {
            // Rate passthrough but channel adaptation (mono → stereo).
            while (inPos < inEnd) {
                val s = src[inPos]
                emit(s, s)
                inPos += inputChannels
            }
            flushOutput()
            return
        }

        // Full path: cubic Hermite across the [m2, m1, p0, p1] window;
        // `frac` is the read position between m1 and p0.
        //
        // STREAMING TAIL RULE: the kernel needs the frame AFTER p0, so
        // every pass processes only frames that have a visible right
        // neighbor and HOLDS the last visible frame in `pending*` for the
        // next pass. Chunked feeding is therefore bit-identical to
        // one-shot feeding (no clamped-kernel glitch at chunk
        // boundaries), at the cost of exactly ONE input frame of
        // algorithmic latency.
        var f0L: Float
        var f0R: Float
        if (hasPending) {
            f0L = pendingL
            f0R = pendingR
        } else {
            f0L = src[inPos]
            f0R = if (inputChannels == 2) src[inPos + 1] else f0L
            inPos += inputChannels
        }
        while (true) {
            if (inPos + inputChannels > inEnd) {
                // f0 is the last visible frame — hold it (right neighbor
                // unknown until the next pass).
                pendingL = f0L
                pendingR = f0R
                hasPending = true
                break
            }
            val p1L = src[inPos]
            val p1R = if (inputChannels == 2) src[inPos + 1] else p1L

            // Emit every output sample that lands inside [m1, f0).
            while (frac < 1.0) {
                val t = frac
                val t2 = t * t
                val t3 = t2 * t
                // Four-point cubic Hermite (Catmull-Rom tension 0.5):
                // continuous value AND first derivative, no overshoot
                // ringing on steps. Basis shared by both channels —
                // ZERO allocation per emitted sample.
                val b0 = -0.5 * t3 + t2 - 0.5 * t
                val b1 = 1.5 * t3 - 2.5 * t2 + 1.0
                val b2 = -1.5 * t3 + 2.0 * t2 + 0.5 * t
                val b3 = 0.5 * t3 - 0.5 * t2
                val rIdx = minOf(1, inputChannels - 1)
                val l = (
                    histFrameM2[0] * b0 + histFrameM1[0] * b1 +
                        f0L * b2 + p1L * b3
                    ).toFloat()
                val r = (
                    histFrameM2[rIdx] * b0 + histFrameM1[rIdx] * b1 +
                        f0R * b2 + p1R * b3
                    ).toFloat()
                emit(l, r)
                frac += ratio
            }
            frac -= 1.0

            // Slide the window: m2 ← m1, m1 ← f0; advance f0 ← p1.
            histFrameM2[0] = histFrameM1[0]
            histFrameM1[0] = f0L
            if (inputChannels == 2) {
                histFrameM2[1] = histFrameM1[1]
                histFrameM1[1] = f0R
            }
            f0L = p1L
            f0R = p1R
            inPos += inputChannels
        }
        flushOutput()
    }

    /** Held tail frame (streaming continuity — see the tail rule above). */
    private var pendingL = 0f
    private var pendingR = 0f
    private var hasPending = false

    /**
     * End-of-stream tail flush: emits the final held window `[m1,
     * pending)` with the kernel's right neighbor clamped (no future
     * frames exist BY CONTRACT at EOS) and clears the pending state.
     * The clamp degenerates exactly ONE kernel at exactly ONE stream
     * position — inaudible, and the only alternative (dropping the
     * window) would truncate the last ~23 µs of audio instead.
     */
    fun flushTail(onOutput: (FloatArray, Int, Int) -> Unit) {
        if (isPassthrough || !hasPending) {
            hasPending = false
            return
        }
        hasPending = false
        var outLen = 0
        fun emit(frameL: Float, frameR: Float) {
            if (outLen + outputChannels > outputStaging.size) {
                if (outLen > 0) {
                    onOutput(outputStaging, 0, outLen)
                    outputFramesEmitted += outLen / outputChannels
                }
                outLen = 0
                if (outputChannels > outputStaging.size) {
                    outputStaging = outputStaging.copyOf(outputStaging.size * 2)
                }
            }
            outputStaging[outLen++] = frameL
            if (outputChannels == 2) outputStaging[outLen++] = frameR
        }
        while (frac < 1.0) {
            val t = frac
            val t2 = t * t
            val t3 = t2 * t
            val b0 = -0.5 * t3 + t2 - 0.5 * t
            val b1 = 1.5 * t3 - 2.5 * t2 + 1.0
            val b2 = -1.5 * t3 + 2.0 * t2 + 0.5 * t
            val b3 = 0.5 * t3 - 0.5 * t2
            val rIdx = minOf(1, inputChannels - 1)
            emit(
                (histFrameM2[0] * b0 + histFrameM1[0] * b1 +
                    pendingL * b2 + pendingL * b3).toFloat(),
                (histFrameM2[rIdx] * b0 + histFrameM1[rIdx] * b1 +
                    pendingR * b2 + pendingR * b3).toFloat(),
            )
            frac += ratio
        }
        frac -= 1.0
        if (outLen > 0) {
            onOutput(outputStaging, 0, outLen)
            outputFramesEmitted += outLen / outputChannels
        }
    }

    /** Clears the streaming history (discontinuity / flush / reset). */
    fun reset() {
        histFrameM2.fill(0f)
        histFrameM1.fill(0f)
        frac = 0.0
        pendingL = 0f
        pendingR = 0f
        hasPending = false
    }

    /**
     * Extracts one Media3 input ByteBuffer into [inputStaging] as
     * interleaved f32 (PCM16 → /32768, f32 passthrough) and runs
     * [process] over it. Handles both endian orders without mutating the
     * caller's buffer order (absolute indexed reads + manual assembly
     * when the buffer is not little-endian).
     *
     * @return input frames ingested; the caller advances the ByteBuffer
     *         position by the corresponding byte count.
     */
    fun ingestByteBuffer(
        buffer: java.nio.ByteBuffer,
        position: Int,
        limit: Int,
        onOutput: (FloatArray, Int, Int) -> Unit,
    ): Int {
        val bytesPerSample = if (inputIsPcm16) 2 else 4
        val frameBytes = bytesPerSample * inputChannels
        val available = limit - position
        val frames = available / frameBytes
        if (frames <= 0) return 0
        ensureInputStaging(frames)
        val littleEndian = buffer.order() == java.nio.ByteOrder.LITTLE_ENDIAN
        var idx = position
        var out = 0
        val total = frames * inputChannels
        when {
            inputIsPcm16 && littleEndian -> while (out < total) {
                val v = buffer.getShort(idx)
                inputStaging[out++] = v / 32768f
                idx += 2
            }
            inputIsPcm16 -> while (out < total) {
                // Big-endian wire: the FIRST byte is the high byte.
                val b0 = buffer.get(idx).toInt() and 0xFF
                val b1 = buffer.get(idx + 1).toInt() and 0xFF
                val v = ((b0 shl 8) or b1).toShort()
                inputStaging[out++] = v / 32768f
                idx += 2
            }
            !inputIsPcm16 && littleEndian -> while (out < total) {
                inputStaging[out++] = buffer.getFloat(idx)
                idx += 4
            }
            else -> while (out < total) {
                val b0 = buffer.get(idx).toInt() and 0xFF
                val b1 = buffer.get(idx + 1).toInt() and 0xFF
                val b2 = buffer.get(idx + 2).toInt() and 0xFF
                val b3 = buffer.get(idx + 3).toInt() and 0xFF
                val bits = (b0 shl 24) or (b1 shl 16) or (b2 shl 8) or b3
                inputStaging[out++] = java.lang.Float.intBitsToFloat(bits)
                idx += 4
            }
        }
        process(inputStaging, 0, frames, onOutput)
        return frames
    }

    private fun ensureInputStaging(frames: Int) {
        val needed = frames * inputChannels
        if (inputStaging.size < needed) {
            var size = inputStaging.size
            while (size < needed) size *= 2
            inputStaging = FloatArray(size)
        }
    }

    private companion object {
        const val INITIAL_STAGING_FRAMES = 4096
    }
}
