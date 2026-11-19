package com.streamify.media.hal

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

actual object DolbySpatialManager {
    actual val isDolbyAtmosDetected: StateFlow<Boolean> = MutableStateFlow(false).asStateFlow()
    actual val isSpatializerAvailable: StateFlow<Boolean> = MutableStateFlow(false).asStateFlow()
    actual val hasHeadTracking: StateFlow<Boolean> = MutableStateFlow(false).asStateFlow()
    actual val hardwareDetail: StateFlow<String> = MutableStateFlow("Desktop HAL Studio DSP (WASAPI / PipeWire)").asStateFlow()
}
