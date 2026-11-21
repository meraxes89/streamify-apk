package com.streamify.app.media.audio
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.BaseAudioProcessor
import com.streamify.app.data.NativeBridge
import java.nio.ByteBuffer

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * SyncAudioProcessor v4 — Media3 AudioSink ⇄ native C++ Sinc resampler
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * Lockstep actuator on the PCM render path. Two independent correction
 * actuators, one per domain — never fused, never double-applied:
 *
 *  1. CLOCK DRIFT (this processor) — the PTP-measured offset between this
 *     DAC's playout clock and the room's atomic timeline. PRIMARY path:
 *     decoded PCM buffers stream through
 *     [NativeBridge.nativeResamplerProcessBuffer] (Engineer 1's Sinc
 *     resampler, drift-fed via [NativeBridge.nativeResamplerSetTargetDriftNanos]
 *     by JamEngine on every PTP sample). FALLBACK path: the legacy Kotlin
 *     micro-stretch loop for devices without the native artifact (or float
 *     passthrough for 16-bit chains).
 *
 *  2. PLAYBACK-RATE PLL (ExoPlayer playbackParameters) — the Kalman speed
 *     scalar (0.98x–1.02x) published by the guest PLL lives in
 *     [PlayerViewModel.setPlaybackSpeed]; it must NOT also be fused here or
 *     the correction would double-apply when this processor sits in the
 *     render chain.
 *
 * Outside a Jam session (or with |drift| < 0.5 ms) the processor is a strict
 * zero-copy passthrough — bit-exact audio for the 99% case, exactly like the
 * pre-Jam render path.
 */
class SyncAudioProcessor(private val context: Context? = null) : BaseAudioProcessor() {

    private var sampleStepAccumulator: Double = 0.0

    /** Sticky native-availability probe: null = untested, false = fallback. */
    private var nativeResamplerUsable: Boolean? = null

    private val audioManager = context?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    companion object {
        /**
         * JAM PHASE-1: Kalman speed scalar (0.98x–1.02x) published by the guest
         * PLL. Applied via ExoPlayer's playbackParameters by
         * [com.streamify.app.viewmodel.PlayerViewModel] — the PRIMARY rate
         * actuator (see class doc: never fused into this processor).
         */
        @Volatile
        var kalmanSpeedScalar: Float = 1.0f
            private set

        fun setSpeedScalar(scalar: Float) {
            kalmanSpeedScalar = scalar.coerceIn(0.98f, 1.02f)
        }

        /**
         * Render-chain activation switch: rate correction only runs inside a
         * live Jam session — outside one, the processor is bit-exact
         * passthrough regardless of any stale drift state.
         */
        @Volatile
        private var jamSyncActive: Boolean = false

        fun setJamSyncActive(active: Boolean) {
            jamSyncActive = active
        }

        /**
         * Signed PTP-measured clock offset (ms) — shared state driven by the
         * Jam engine on every SYNC_ACK so whichever processor instance sits
         * in the render chain sees the live value without instance wiring.
         */
        @Volatile
        private var targetDriftCorrectionMs: Float = 0.0f

        fun setClockDriftAdjustment(offsetMs: Float) {
            targetDriftCorrectionMs = offsetMs.coerceIn(-50.0f, 50.0f)
        }

        /** True when the drift actuator should engage for the current buffer. */
        internal fun driftActive(): Boolean =
            jamSyncActive && kotlin.math.abs(targetDriftCorrectionMs) >= 0.5f
    }

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT &&
            inputAudioFormat.encoding != C.ENCODING_PCM_16BIT
        ) {
            return AudioFormat.NOT_SET
        }
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return

        val isFloat = inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT
        val kalmanActive = kotlin.math.abs(kalmanSpeedScalar - 1.0f) >= 0.001f

        // Passthrough: no jam, no measurable drift, or non-float chains.
        // (Kalman-only corrections are handled by playbackParameters.)
        if (!driftActive() || !isFloat) {
            val out = replaceOutputBuffer(remaining)
            out.put(inputBuffer)
            out.flip()
            return
        }

        // ── PRIMARY: C++ Sinc resampler (Engineer 1, frozen ABI) ──────────
        if (nativeResamplerUsable != false) {
            val channels = inputAudioFormat.channelCount
            val sampleRate = inputAudioFormat.sampleRate
            val frameBytes = channels * 4
            val frames = remaining / frameBytes
            if (frames > 0) {
                // Rate correction moves frame counts by ≤ ±2%; +64 frames of
                // headroom absorbs any resampler look-ahead padding.
                val outCapacityFrames = frames + 64
                val out = replaceOutputBuffer(outCapacityFrames * frameBytes)
                val produced = NativeBridge.resamplerProcessBuffer(
                    inputBuffer, out, frames, channels, sampleRate
                )
                if (produced >= 0) {
                    inputBuffer.position(inputBuffer.limit()) // fully consumed
                    val producedBytes = (produced.coerceAtMost(outCapacityFrames)) * frameBytes
                    out.position(producedBytes)
                    out.flip()
                    return
                }
                // Native core unavailable: sticky-fallback to the Kotlin path.
                nativeResamplerUsable = false
            }
        }

        // ── FALLBACK: Kotlin micro-stretch (bounded to ±2%, inaudible) ────
        fallbackResample(inputBuffer, remaining)
    }

    /**
     * Legacy rate-modified frame stepper: duplicates/drops frames so the
     * playout clock slews toward the room timeline. Kept verbatim-in-spirit
     * from v3 — it is the correctness floor when no native artifact ships.
     */
    private fun fallbackResample(inputBuffer: ByteBuffer, remaining: Int) {
        val driftRate = 1.0 + (targetDriftCorrectionMs / 5000.0)
        val rateModifier = driftRate.coerceIn(0.98, 1.02)
        val outputCapacityEstimate = (remaining * 1.05).toInt()
        val outputBuffer = replaceOutputBuffer(outputCapacityEstimate)

        val channelCount = inputAudioFormat.channelCount
        val floatsPerFrame = channelCount

        while (inputBuffer.remaining() >= floatsPerFrame * 4) {
            for (ch in 0 until channelCount) {
                outputBuffer.putFloat(inputBuffer.getFloat(inputBuffer.position() + ch * 4))
            }
            sampleStepAccumulator += rateModifier
            if (sampleStepAccumulator >= 1.0) {
                val advanceFrames = sampleStepAccumulator.toInt()
                val nextPos = inputBuffer.position() + advanceFrames * floatsPerFrame * 4
                if (nextPos <= inputBuffer.limit()) {
                    inputBuffer.position(nextPos)
                } else {
                    inputBuffer.position(inputBuffer.limit())
                }
                sampleStepAccumulator -= advanceFrames
            }
        }
        outputBuffer.flip()
    }

    /**
     * Hardware output latency in nanoseconds (DAC / Bluetooth A2DP pipeline).
     * PRIMARY: the native playout-delay probe (frozen ABI). FALLBACK: the
     * AudioManager device-class heuristic table.
     */
    fun getHardwarePlayoutDelayNanos(): Long {
        val nativeNanos = NativeBridge.hardwarePlayoutDelayNanos()
        if (nativeNanos >= 0L) return nativeNanos
        return getHardwareOutputLatencyMs() * 1_000_000L
    }

    /**
     * Calculates the estimated hardware latency in milliseconds (e.g. Bluetooth A2DP vs Built-in DAC).
     */
    fun getHardwareOutputLatencyMs(): Long {
        if (audioManager == null) return 15L

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                for (d in devices) {
                    when (d.type) {
                        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                        AudioDeviceInfo.TYPE_BLE_HEADSET,
                        AudioDeviceInfo.TYPE_BLE_SPEAKER -> return 140L

                        AudioDeviceInfo.TYPE_USB_DEVICE,
                        AudioDeviceInfo.TYPE_USB_HEADSET -> return 25L

                        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                        AudioDeviceInfo.TYPE_WIRED_HEADSET -> return 12L
                    }
                }
            }
        } catch (e: Exception) {
            // Fallback
        }
        return 18L
    }

    /**
     * Acoustic (in-the-air) position: the playout clock sits BEHIND the
     * transport position by the hardware pipeline delay.
     */
    fun getAcousticPositionMs(basePositionMs: Long): Long {
        val hardwareLatency = getHardwareOutputLatencyMs()
        return (basePositionMs - hardwareLatency).coerceAtLeast(0L)
    }
}
