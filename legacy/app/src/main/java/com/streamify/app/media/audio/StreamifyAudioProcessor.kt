package com.streamify.app.media.audio
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.BaseAudioProcessor
import com.streamify.app.data.NativeBridge
import java.nio.ByteBuffer
import java.nio.ByteOrder
import com.streamify.app.media.playback.PlaybackService

/**
 * THE render-path processor. Replaces the old 4-processor chain
 * (RustDSP -> MeshPcm -> Crossfade -> Sync) which cost 5 full buffer copies +
 * 3 JNI crossings + an interpreted per-sample loop per 20ms buffer — all on
 * the real-time audio thread.
 *
 * Now: ONE processor, ONE fused JNI crossing per buffer
 * (i16 -> EQ/DSP -> RMS normalization -> f32), direct buffers reused,
 * native states freed on [release].
 *
 * CONTRACT: queueInput always consumes the whole input buffer and always
 * produces output (pure-Kotlin i16->f32 fallback if the native pass fails),
 * so a native failure can never stall or glitch the Media3 sink.
 *
 * ENCODING CONTRACT: the processor's OUTPUT encoding always mirrors its INPUT
 * encoding. Downstream of any user processor, media3 1.2.1 chains its internal
 * SilenceSkippingAudioProcessor and SonicAudioProcessor — both accept ONLY
 * 16-bit PCM and throw UnhandledAudioFormatException on float input (they sit
 * in the pipeline even when inactive). Advertising float here used to kill
 * every stream with ERROR_CODE_AUDIO_TRACK_INIT_FAILED -> silent docked
 * player. DSP still runs internally at f32; conversion back happens at the
 * buffer edge.
 */
class StreamifyAudioProcessor : BaseAudioProcessor() {

    companion object {
        /**
         * PHASE 1 LOUDNESS TRUTH: YouTube's own per-stream measurement
         * (loudnessDb, relative to −14 LUFS reference). Applied as exact
         * pre-gain; when present the legacy RMS normalizer is bypassed.
         * Null → non-YT/local source → legacy RMS path.
         */
        @Volatile var currentPreGainDb: Float? = null

        /**
         * PHASE 2 PARAMETRIC EQ: 10 band gains in dB, published by
         * EqualizerManager. Applied through the Rust StudioEqualizer biquads —
         * ONLY on 44100 Hz streams, because the native EQ singleton locks its
         * coefficient design rate at first construction. Non-44100 streams
         * keep the system Equalizer engine (activeEqEngine == "SYSTEM").
         */
        @Volatile var eqBandGainsDb: FloatArray? = null

        /** Which engine owns EQ for the CURRENT stream — set on configure. */
        @Volatile var activeEqEngine: String = "SYSTEM"

        /** PHASE 2 SAFETY: soft-knee ceiling after every gain stage. */
        @Volatile var limiterEnabled: Boolean = false // opt-in after device testing

        // ═══ Phase 1 AUDIOPHILE DSP PREFERENCES (Gaps #39 + #41) ═══
        // Published by DspPreferences.apply() — read lock-free on the audio
        // callback thread. These are USER-FACING contracts: they take effect
        // even while the development DSP_BYPASS fast-path is active (the
        // bypass stays 100% bit-exact only while every one of them is off).

        /** True-peak limiter ceiling in dBFS (EU compliance: −1.0). */
        @Volatile var limiterCeilingDbfs: Float = DspPreferences.TRUE_PEAK_CEILING_DBFS

        /** Accessibility mono downmix (L+R)/2. */
        @Volatile var monoDownmixEnabled: Boolean = false

        /** Stereo balance: −100 (full left) … +100 (full right). 0 = centred. */
        @Volatile var stereoBalancePercent: Int = 0

        /**
         * Loudness normalization target in LUFS (null = normalization off).
         * Shifts the per-stream loudness pre-gain relative to the −14
         * calibration: preGain = loudnessDb + (target − (−14)).
         */
        @Volatile var normalizeTargetLufs: Float? = null

        /** Gapless playback discipline (JIT pre-hydration + hard splice). */
        @Volatile var gaplessEnabled: Boolean = true

        /**
         * GLOBAL DSP BYPASS:
         * When true, all audio frames pass through 100% bit-exact and unaltered.
         * Temporarily active while DSP v2 (biquad acoustic cleanup, BS.1770-4
         * K-weighting calibration, and true-peak limiter overhaul) is developed.
         */
        @Volatile var DSP_BYPASS: Boolean = true
    }

    private var statePtr: Long = 0L
    private var normalizerPtr: Long = 0L

    private var currentFormat: AudioFormat = AudioFormat.NOT_SET

    // Direct native buffers for zero-copy FFI (reused across callbacks).
    private var nativeInputBuffer: ByteBuffer = ByteBuffer.allocateDirect(16384).order(ByteOrder.nativeOrder())
    private var nativeOutputBuffer: ByteBuffer = ByteBuffer.allocateDirect(32768).order(ByteOrder.nativeOrder())
    private var scratchFloats: FloatArray = FloatArray(4096)

    init {
        ensureNativeHandles()
    }

    /**
     * Lazily (re-)initializes native DSP state. This processor is a process-
     * lifetime singleton (companion val in PlaybackService), but [release]
     * zeroes the native pointers on service teardown. Without re-init, every
     * subsequent service incarnation would silently run the degraded
     * pure-Kotlin fallback forever.
     */
    private fun ensureNativeHandles() {
        if (statePtr == 0L) {
            statePtr = try {
                NativeBridge.nativeInitDsp()
            } catch (_: Throwable) {
                0L
            }
        }
        if (normalizerPtr == 0L) {
            normalizerPtr = try {
                NativeBridge.nativeInitNormalizer(0.25f)
            } catch (_: Throwable) {
                0L
            }
        }
    }

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT &&
            inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT
        ) {
            return AudioFormat.NOT_SET
        }
        ensureNativeHandles()
        currentFormat = inputAudioFormat
        // PHASE 2: route EQ ownership by stream sample rate (native biquad
        // coefficients are designed at 44.1k).
        activeEqEngine =
            if (inputAudioFormat.sampleRate == 44_100) "RUST" else "SYSTEM"
        // Audit #22 item 9: pin the native float-DSP singletons (LUFS
        // K-filters + limiter time constants) to the ACTUAL stream
        // geometry — they were hardcoded at 48 kHz, so 44.1 kHz content
        // measured and limited through the wrong math. Native-side
        // change-detection makes re-configure a no-op.
        runCatching {
            NativeBridge.nativeConfigureAudioDSP(
                inputAudioFormat.sampleRate,
                inputAudioFormat.channelCount
            )
        }
        // OUTPUT = INPUT encoding. media3 1.2.1's internal silence-skip/sonic
        // processors downstream are 16-bit-only and throw on float regardless
        // of whether they are active. f32 mastering happens internally; the
        // final quantization back to i16 is a single clamped pass per buffer.
        return AudioFormat(
            inputAudioFormat.sampleRate,
            inputAudioFormat.channelCount,
            inputAudioFormat.encoding
        )
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remainingBytes = inputBuffer.remaining()
        if (remainingBytes == 0) return

        // Global DSP bypass fast-path: zero allocations, 100% bit-exact
        // passthrough — EXCEPT the user-facing Phase 1 channel policy
        // (mono / balance / true-peak limiter), which are explicit user
        // toggles and must work regardless of the development DSP bypass.
        // While every one of them is off the path remains bit-exact.
        if (DSP_BYPASS) {
            val channels = currentFormat.channelCount.coerceAtLeast(1)
            val needsChannelPolicy = channels >= 2 && (monoDownmixEnabled || stereoBalancePercent != 0)
            if (!needsChannelPolicy && !limiterEnabled) {
                val out = replaceOutputBuffer(remainingBytes)
                out.put(inputBuffer)
                out.flip()
                return
            }
            applyUserChannelPolicyBypass(inputBuffer, remainingBytes, channels)
            return
        }

        val channelCount = currentFormat.channelCount.coerceAtLeast(1)
        val is16Bit = currentFormat.encoding == C.ENCODING_PCM_16BIT
        val bytesPerFrame = if (is16Bit) channelCount * 2 else channelCount * 4
        val numFrames = remainingBytes / bytesPerFrame
        if (numFrames <= 0) {
            // Sub-frame tail (<1 frame): consume it as silence-equivalent passthrough.
            consumeTail(inputBuffer, remainingBytes, channelCount, is16Bit)
            return
        }

        val sampleCount = numFrames * channelCount
        val requiredInputCap = sampleCount * 2   // i16 PCM for the Rust engine
        val requiredOutputCap = sampleCount * 4  // f32 output

        if (nativeInputBuffer.capacity() < requiredInputCap) {
            nativeInputBuffer = ByteBuffer.allocateDirect(requiredInputCap * 2).order(ByteOrder.nativeOrder())
        }
        if (nativeOutputBuffer.capacity() < requiredOutputCap) {
            nativeOutputBuffer = ByteBuffer.allocateDirect(requiredOutputCap * 2).order(ByteOrder.nativeOrder())
        }

        // ═══ PHASE 1 QUANTIZATION FIX + LOUDNESS TRUTH ═══
        // FLOAT input never round-trips through i16 anymore (that injected
        // ditherless quantization into a supposedly-float pipeline).
        if (!is16Bit) {
            nativeOutputBuffer.clear()
            nativeOutputBuffer.put(inputBuffer)
            nativeOutputBuffer.position(0)
            nativeOutputBuffer.limit(sampleCount * 4)

            // Phase 1 audiophile contract: the target-LUFS slider shifts the
            // per-stream loudness pre-gain relative to the −14 calibration
            // (loudnessDb is measured against −14); null disables the
            // normalization stage entirely (bit-exact loudness).
            val preGainLin = currentPreGainDb?.let { db ->
                val adjustedDb = normalizeTargetLufs?.let { target -> db + (target + 14f) } ?: db
                Math.pow(10.0, adjustedDb / 20.0).toFloat()
            }
            if (preGainLin != null && preGainLin != 1.0f) {
                for (i in 0 until sampleCount) {
                    val idx = i * 4
                    val v = nativeOutputBuffer.getFloat(idx) * preGainLin
                    nativeOutputBuffer.putFloat(idx, if (v > 1f) 1f else if (v < -1f) -1f else v)
                }
            }

            // Zero-Copy Native Float32 DSP: EQ -> LUFS Normalization -> Soft-Knee Limiter
            val gains = if (activeEqEngine == "RUST") eqBandGainsDb else null
            runCatching {
                NativeBridge.nativeProcessFloatAudio(nativeOutputBuffer, numFrames, channelCount, gains)
            }

            val out = replaceOutputBuffer(sampleCount * 4)
            nativeOutputBuffer.position(0)
            nativeOutputBuffer.limit(sampleCount * 4)
            out.put(nativeOutputBuffer)
            out.flip()
            return
        }

        nativeInputBuffer.clear()
        nativeInputBuffer.put(inputBuffer)
        nativeInputBuffer.flip()

        nativeOutputBuffer.clear()
        val result: Int = try {
            if (statePtr != 0L) {
                NativeBridge.nativeProcessFusedAudio(statePtr, normalizerPtr, nativeInputBuffer, nativeOutputBuffer, numFrames)
            } else -1
        } catch (_: Throwable) {
            -1
        }

        if (result == 0) {
            postProcessFloat(nativeOutputBuffer, sampleCount, channelCount)
            emitProcessedOutput(sampleCount)
        } else {
            // Native failed: still emit clean audio via pure-Kotlin conversion
            // so the sink never starves. Rewind what we consumed from the copy.
            emitFallbackOutput(sampleCount)
        }
    }

    /** Output byte count for [sampleCount] samples under the declared output encoding. */
    private fun declaredOutputBytes(sampleCount: Int): Int =
        if (currentFormat.encoding == C.ENCODING_PCM_FLOAT) sampleCount * 4 else sampleCount * 2

    /**
     * Emits the [sampleCount] processed f32 samples sitting in nativeOutputBuffer
     * in the configured output encoding. On 16-bit streams this is the final
     * f32->i16 quantization stage: single clamped pass, zero allocations — safe
     * for the real-time audio thread.
     */
    private fun emitProcessedOutput(sampleCount: Int) {
        val out = replaceOutputBuffer(declaredOutputBytes(sampleCount))
        nativeOutputBuffer.position(0)
        nativeOutputBuffer.limit(sampleCount * 4)
        if (currentFormat.encoding == C.ENCODING_PCM_FLOAT) {
            out.put(nativeOutputBuffer)
        } else {
            repeat(sampleCount) {
                val v = nativeOutputBuffer.float
                out.putShort((v.coerceIn(-1f, 1f) * 32767f).toInt().toShort())
            }
        }
        out.flip()
    }

    /**
     * PHASE 2 POST STAGE — applied to EVERY rendered buffer:
     *   1. Rust parametric EQ when this stream is RUST-owned (44.1 kHz),
     *      using live gains published from EqualizerManager.
     *   2. Phase 1 user channel policy: mono downmix + stereo balance.
     *   3. C++ SoftKneeLimiter at the user ceiling (default −1 dBFS) / 2 dB
     *      knee — the always-on true-peak safety net that makes loudness
     *      pre-gain clip-free.
     * One reusable scratch array; one bulk copy in/out per buffer.
     */
    private fun postProcessFloat(buffer: java.nio.ByteBuffer, sampleCount: Int, channels: Int) {
        if (sampleCount <= 0) return
        val gains = eqBandGainsDb
        val needsEq = activeEqEngine == "RUST" && gains != null && gains.size == 10
        val needsChannelPolicy = channels >= 2 && (monoDownmixEnabled || stereoBalancePercent != 0)
        if (!needsEq && !limiterEnabled && !needsChannelPolicy) return

        if (scratchFloats.size < sampleCount) scratchFloats = FloatArray(sampleCount * 2)
        buffer.position(0)
        buffer.limit(sampleCount * 4)
        // ByteBuffer has no bulk float ops: copy through an aligned view.
        buffer.asFloatBuffer().get(scratchFloats, 0, sampleCount)

        if (needsEq) {
            runCatching {
                NativeBridge.rustProcessEqualizerFrame(
                    pcmFloats = scratchFloats,
                    channels = channels,
                    gains = gains
                )
            }
        }

        if (needsChannelPolicy) {
            applyChannelPolicy(scratchFloats, sampleCount, channels)
        }

        if (limiterEnabled) {
            runCatching {
                NativeBridge.processLimiterFloats(
                    buffer = scratchFloats,
                    length = sampleCount,
                    threshold = limiterCeilingDbfs,
                    kneeWidth = 2.0f
                )
            }
        }

        buffer.position(0)
        buffer.asFloatBuffer().put(scratchFloats, 0, sampleCount)
        buffer.position(0)
        buffer.limit(sampleCount * 4)
    }

    /**
     * Phase 1 user channel policy on the bypass fast-path: mono downmix
     * and/or stereo balance and/or the true-peak limiter, applied to the
     * raw passthrough bytes in the stream's own encoding. Zero allocation
     * per buffer (scratch reuse), full [inputBuffer] consumption contract
     * preserved.
     */
    private fun applyUserChannelPolicyBypass(inputBuffer: ByteBuffer, remainingBytes: Int, channels: Int) {
        val is16Bit = currentFormat.encoding == C.ENCODING_PCM_16BIT
        val frameBytes = if (is16Bit) channels * 2 else channels * 4
        val sampleCount = remainingBytes / frameBytes * channels
        val tail = remainingBytes % frameBytes

        if (scratchFloats.size < sampleCount) scratchFloats = FloatArray(sampleCount.coerceAtLeast(1) * 2)
        // Unpack (input is consumed as we read it).
        var i = 0
        if (is16Bit) {
            while (i < sampleCount) {
                scratchFloats[i] = inputBuffer.short.toFloat() / 32767.0f
                i++
            }
        } else {
            while (i < sampleCount) {
                scratchFloats[i] = inputBuffer.float
                i++
            }
        }

        applyChannelPolicy(scratchFloats, sampleCount, channels)
        if (limiterEnabled) {
            runCatching {
                NativeBridge.processLimiterFloats(
                    buffer = scratchFloats,
                    length = sampleCount,
                    threshold = limiterCeilingDbfs,
                    kneeWidth = 2.0f
                )
            }
        }

        val outBytes = if (is16Bit) sampleCount * 2 else sampleCount * 4
        val out = replaceOutputBuffer(outBytes + tail)
        if (is16Bit) {
            for (s in 0 until sampleCount) {
                val v = scratchFloats[s].coerceIn(-1f, 1f)
                out.putShort((v * 32767f).toInt().toShort())
            }
        } else {
            for (s in 0 until sampleCount) out.putFloat(scratchFloats[s].coerceIn(-1f, 1f))
        }
        // Sub-frame tail: bit-exact passthrough (channel policy is
        // frame-based; a lone byte cannot be transformed).
        if (tail > 0) {
            val mark = inputBuffer.position()
            out.put(inputBuffer)
            inputBuffer.position(mark)
        }
        out.flip()
    }

    /**
     * Mono downmix (L+R)/2 followed by constant-gain stereo balance
     * (−100 % = full left, +100 % = full right). In-place, frame-paired;
     * mono applies to every channel pair, balance to the first pair —
     * matching the accessibility semantics of the OS mono toggle.
     */
    private fun applyChannelPolicy(samples: FloatArray, sampleCount: Int, channels: Int) {
        if (channels < 2) return
        val mono = monoDownmixEnabled
        val balance = stereoBalancePercent.coerceIn(-100, 100)
        if (!mono && balance == 0) return
        val leftGain: Float
        val rightGain: Float
        if (balance >= 0) {
            leftGain = 1f - balance / 100f
            rightGain = 1f
        } else {
            leftGain = 1f
            rightGain = 1f + balance / 100f
        }
        var f = 0
        while (f + channels <= sampleCount) {
            val l = samples[f]
            val r = samples[f + 1]
            if (mono) {
                val m = (l + r) * 0.5f
                samples[f] = m * leftGain
                samples[f + 1] = m * rightGain
            } else {
                samples[f] = l * leftGain
                samples[f + 1] = r * rightGain
            }
            f += channels
        }
    }

    /** Fallback: convert the staged i16 copy in nativeInputBuffer to the declared output encoding. */
    private fun emitFallbackOutput(sampleCount: Int) {
        val out = replaceOutputBuffer(declaredOutputBytes(sampleCount))
        nativeInputBuffer.position(0)
        nativeInputBuffer.limit(sampleCount * 2)
        val asFloat = currentFormat.encoding == C.ENCODING_PCM_FLOAT
        repeat(sampleCount) {
            val f = nativeInputBuffer.short.toFloat() / 32767.0f
            if (asFloat) {
                out.putFloat(f)
            } else {
                out.putShort((f.coerceIn(-1f, 1f) * 32767f).toInt().toShort())
            }
        }
        out.flip()
    }

    /** Consume arbitrary remainder (sub-frame tail) with best-effort conversion. */
    private fun consumeTail(input: ByteBuffer, byteCount: Int, channelCount: Int, is16Bit: Boolean) {
        val samples = if (is16Bit) byteCount / 2 else byteCount / 4
        val asFloat = currentFormat.encoding == C.ENCODING_PCM_FLOAT
        val out = replaceOutputBuffer(if (asFloat) samples * 4 else samples * 2)
        repeat(samples) {
            val f = if (is16Bit) input.short.toFloat() / 32767.0f else input.float
            if (asFloat) {
                out.putFloat(f)
            } else {
                out.putShort((f.coerceIn(-1f, 1f) * 32767f).toInt().toShort())
            }
        }
        // Contract: ALWAYS leave the input fully consumed.
        input.position(input.limit())
        out.flip()
    }

    override fun onFlush() {
        // Filter state lives natively; nothing to clear Kotlin-side beyond
        // buffer cursors, which BaseAudioProcessor handles.
    }

    /**
     * Frees native DSP/normalizer state. Call from service teardown — the old
     * implementation leaked both permanently.
     */
    fun release() {
        if (statePtr != 0L) {
            try { NativeBridge.nativeFreeDsp(statePtr) } catch (_: Throwable) {}
            statePtr = 0L
        }
        if (normalizerPtr != 0L) {
            try { NativeBridge.nativeFreeNormalizer(normalizerPtr) } catch (_: Throwable) {}
            normalizerPtr = 0L
        }
    }
}
