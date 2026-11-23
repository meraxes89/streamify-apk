package com.streamify.media.hal

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

actual object DolbySpatialManager {
    actual val isDolbyAtmosDetected: StateFlow<Boolean> = MutableStateFlow(true).asStateFlow()
    actual val isSpatializerAvailable: StateFlow<Boolean> = MutableStateFlow(true).asStateFlow()
    actual val hasHeadTracking: StateFlow<Boolean> = MutableStateFlow(true).asStateFlow()
    actual val hardwareDetail: StateFlow<String> = MutableStateFlow("Apple CoreAudio Spatial Engine / AirPods Pro Spatial Audio").asStateFlow()
}
