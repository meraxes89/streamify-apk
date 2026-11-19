package com.streamify.media.hal

import kotlinx.coroutines.flow.StateFlow

expect object DolbySpatialManager {
    val isDolbyAtmosDetected: StateFlow<Boolean>
    val isSpatializerAvailable: StateFlow<Boolean>
    val hasHeadTracking: StateFlow<Boolean>
    val hardwareDetail: StateFlow<String>
}
