package com.streamify.media.hal.di

import com.streamify.media.hal.DacClockBridge
import com.streamify.media.hal.DolbySpatialManager
import com.streamify.media.hal.StreamifyAudioProcessor
import com.streamify.media.hal.StreamifyMedia3Module
import org.koin.core.module.Module
import org.koin.dsl.module

actual val mediaHalModule: Module = module {
    single<DacClockBridge> { DacClockBridge() }
    single<StreamifyAudioProcessor> { StreamifyAudioProcessor() }
    single<DolbySpatialManager> { DolbySpatialManager }
    single<StreamifyMedia3Module> { StreamifyMedia3Module }
}
