package com.streamify.lyrics.slyr

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

actual object BluetoothLatencyTracker {
    private val _dynamicDelayMs = MutableStateFlow(0)
    actual val dynamicDelayMs: StateFlow<Int> = _dynamicDelayMs.asStateFlow()

    actual fun init(context: Any?) {
        // Desktop (JVM) audio engines generally handle hardware timestamp delay natively
    }

    actual fun getDelayMs(): Int = 0
}
