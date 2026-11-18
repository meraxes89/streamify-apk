package com.streamify.media.hal.di

import com.streamify.media.hal.DacClockBridge
import com.streamify.media.hal.DesktopHalAudioBridge
import org.koin.core.module.Module
import org.koin.dsl.module

actual val mediaHalModule: Module = module {
    single { DacClockBridge() }
    single { DesktopHalAudioBridge }
}
