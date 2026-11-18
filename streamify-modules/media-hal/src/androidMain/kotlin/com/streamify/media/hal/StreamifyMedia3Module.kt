package com.streamify.media.hal

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.SilenceSkippingAudioProcessor
import androidx.media3.exoplayer.audio.SonicAudioProcessor

/**
 * Creates the enhanced audio sink chain incorporating Streamify's
 * real-time C++20 DSP processor (ITU-R BS.1770-4 LUFS normalizer & true-peak limiter).
 */
@OptIn(UnstableApi::class)
object StreamifyMedia3Module {

    val audioProcessor: StreamifyAudioProcessor by lazy { StreamifyAudioProcessor() }

    fun createEnhancedAudioProcessorChain(
        existingProcessors: Array<AudioProcessor> = emptyArray()
    ): DefaultAudioSink.AudioProcessorChain {
        val allProcessors = existingProcessors + audioProcessor
        return DefaultAudioSink.DefaultAudioProcessorChain(
            allProcessors,
            SilenceSkippingAudioProcessor(
                2_000_000,
                (20_000 / 2_000_000).toFloat(),
                2_000_000,
                0,
                256
            ),
            SonicAudioProcessor()
        )
    }

    fun init(context: Context) {
        DolbySpatialManager.init(context)
    }
}
