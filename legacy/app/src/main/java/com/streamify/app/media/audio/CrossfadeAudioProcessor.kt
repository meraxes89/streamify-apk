package com.streamify.app.media.audio
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

@OptIn(UnstableApi::class)
class CrossfadeAudioProcessor : BaseAudioProcessor() {

    companion object {
        var crossfadeDurationMs: Long = 2500L
    }

    private var currentCrossfadeDurationMs = 2500L
    private var isCrossfading = false
    private var totalCrossfadeFrames = 0L
    private var currentFrameCount = 0L

    // 256-Entry Pre-computed Constant-Energy Trigonometric LUT (G_out^2 + G_in^2 == 1.0)
    private val lutSize = 256
    private val gainOutLut = FloatArray(lutSize)
    private val gainInLut = FloatArray(lutSize)

    init {
        for (i in 0 until lutSize) {
            val phase = (i.toDouble() / (lutSize - 1)) * (PI / 2.0)
            gainOutLut[i] = cos(phase).toFloat()
            gainInLut[i] = sin(phase).toFloat()
        }
    }

    fun startCrossfade(durationMs: Long = 2500L) {
        this.currentCrossfadeDurationMs = durationMs
        val sampleRate = inputAudioFormat.sampleRate.takeIf { it > 0 } ?: 44100
        this.totalCrossfadeFrames = (sampleRate * (durationMs / 1000f)).toLong()
        this.currentFrameCount = 0L
        this.isCrossfading = true
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT && inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            return AudioProcessor.AudioFormat.NOT_SET
        }
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remainingBytes = inputBuffer.remaining()
        if (remainingBytes == 0) return

        val buffer = replaceOutputBuffer(remainingBytes)
        val channelCount = inputAudioFormat.channelCount.coerceAtLeast(1)

        if (!isCrossfading || totalCrossfadeFrames == 0L) {
            buffer.put(inputBuffer)
        } else {
            val isFloat = inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT
            if (isFloat) {
                val floatCount = remainingBytes / 4
                val inFloats = inputBuffer.asFloatBuffer()
                val outFloats = buffer.asFloatBuffer()

                var i = 0
                while (i < floatCount) {
                    val progress = (currentFrameCount.toFloat() / totalCrossfadeFrames.toFloat()).coerceIn(0.0f, 1.0f)
                    val lutIndex = (progress * (lutSize - 1)).toInt().coerceIn(0, lutSize - 1)
                    val gain = gainOutLut[lutIndex]

                    for (ch in 0 until channelCount) {
                        if (i < floatCount) {
                            val sample = inFloats.get()
                            outFloats.put(sample * gain)
                            i++
                        }
                    }
                    currentFrameCount++
                    if (currentFrameCount >= totalCrossfadeFrames) {
                        isCrossfading = false
                        break
                    }
                }
                buffer.position(buffer.position() + remainingBytes)
            } else {
                val shortCount = remainingBytes / 2
                val inShorts = inputBuffer.asShortBuffer()
                val outShorts = buffer.asShortBuffer()

                var i = 0
                while (i < shortCount) {
                    val progress = (currentFrameCount.toFloat() / totalCrossfadeFrames.toFloat()).coerceIn(0.0f, 1.0f)
                    val lutIndex = (progress * (lutSize - 1)).toInt().coerceIn(0, lutSize - 1)
                    val gain = gainOutLut[lutIndex]

                    for (ch in 0 until channelCount) {
                        if (i < shortCount) {
                            val sample = inShorts.get()
                            outShorts.put((sample * gain).toInt().coerceIn(-32768, 32767).toShort())
                            i++
                        }
                    }
                    currentFrameCount++
                    if (currentFrameCount >= totalCrossfadeFrames) {
                        isCrossfading = false
                        break
                    }
                }
                buffer.position(buffer.position() + remainingBytes)
            }
        }

        buffer.flip()
    }

    override fun onReset() {
        isCrossfading = false
        currentFrameCount = 0L
    }
}
