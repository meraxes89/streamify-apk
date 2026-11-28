package com.streamify.lyrics.slyr

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.concurrent.Volatile
import kotlin.math.abs

/**
 * Universal latency decomposition model:
 * L_output = L_hal_buffer + L_link
 *
 * Implements smooth slewing over ~250ms on route transitions to prevent abrupt visual jumps.
 */
class LatencyModel(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) {
    private val _snapshot = MutableStateFlow(LatencySnapshot())
    val snapshot: StateFlow<LatencySnapshot> = _snapshot.asStateFlow()

    private val _slewedDelayMs = MutableStateFlow(0L)
    val slewedDelayMs: StateFlow<Long> = _slewedDelayMs.asStateFlow()

    @Volatile
    private var targetDelayMs: Long = 0L

    init {
        scope.launch {
            val stepIntervalMs = 16L
            val slewDurationMs = 250f
            while (isActive) {
                val current = _slewedDelayMs.value
                val target = targetDelayMs
                if (current != target) {
                    val delta = target - current
                    val step = (delta / (slewDurationMs / stepIntervalMs)).toLong()
                    val next = if (abs(delta) <= 2L || step == 0L) {
                        target
                    } else {
                        current + step
                    }
                    _slewedDelayMs.value = next
                }
                delay(stepIntervalMs)
            }
        }
    }

    /**
     * Updates the latency model parameters with a measured HAL buffer delay and modeled/calibrated link delay.
     */
    fun updateLatency(
        halBufferMs: Long,
        linkMs: Long,
        confidence: LatencyConfidence,
        routeName: String
    ) {
        val total = (halBufferMs + linkMs).coerceAtLeast(0L)
        targetDelayMs = total
        _snapshot.value = LatencySnapshot(
            halBufferMs = halBufferMs,
            linkMs = linkMs,
            totalDelayMs = total,
            confidence = confidence,
            routeName = routeName
        )
    }

    /**
     * For immediate route reset (e.g. track change or seek).
     */
    fun snapToTarget() {
        _slewedDelayMs.value = targetDelayMs
    }

    companion object {
        // Standard Bluetooth codec profiles (API 33+ telemetry mapping)
        const val PROFILE_LE_AUDIO_MS = 40L
        const val PROFILE_A2DP_SBC_MS = 180L
        const val PROFILE_A2DP_AAC_MS = 140L
        const val PROFILE_A2DP_APTX_MS = 90L
        const val PROFILE_A2DP_LDAC_MS = 130L
        const val PROFILE_SCO_MS = 80L
        const val PROFILE_WIRED_SPEAKER_MS = 0L
    }
}
