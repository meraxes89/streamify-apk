package com.streamify.lyrics.slyr

import kotlinx.coroutines.flow.StateFlow

/**
 * Universal Bluetooth hardware latency and audio routing tracker.
 * Automatically computes hardware latency compensation (e.g. 140ms on Bluetooth A2DP/LE)
 * so lyrics synchronization stays accurate across connect/disconnect events without manual calibration.
 */
expect object BluetoothLatencyTracker {
    /**
     * Flow emitting the current hardware compensation latency in milliseconds.
     * Typically 0ms on built-in speakers / wired DAC, and ~140ms on Bluetooth A2DP.
     */
    val dynamicDelayMs: StateFlow<Int>

    /**
     * Initializes hardware audio device routing callbacks.
     */
    fun init(context: Any? = null)

    /**
     * Current instant delay compensation in milliseconds.
     */
    fun getDelayMs(): Int
}
