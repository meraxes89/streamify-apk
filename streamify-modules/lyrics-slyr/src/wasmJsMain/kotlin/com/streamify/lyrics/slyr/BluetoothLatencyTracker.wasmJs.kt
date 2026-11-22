package com.streamify.lyrics.slyr

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

actual object BluetoothLatencyTracker {
    private val _dynamicDelayMs = MutableStateFlow(0)
    actual val dynamicDelayMs: StateFlow<Int> = _dynamicDelayMs.asStateFlow()

    var latencyModel: LatencyModel? = null

    actual fun init(context: Any?) {
        // Web AudioContext outputLatency telemetry
        latencyModel?.updateLatency(
            halBufferMs = 0L,
            linkMs = 0L,
            confidence = LatencyConfidence.MEASURED,
            routeName = "Web AudioContext"
        )
    }

    actual fun getDelayMs(): Int = 0
}
