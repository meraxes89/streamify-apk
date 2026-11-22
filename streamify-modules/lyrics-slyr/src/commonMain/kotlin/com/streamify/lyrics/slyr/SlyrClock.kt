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
import kotlin.time.TimeSource

/**
 * Unified presentation-time clock for all lyrics consumers.
 *
 * HeardTime = PlayerPresentationPosition - L_output - UserOffset
 *
 * Guarantees monotonic progression during playback, handles seeks explicitly,
 * and eliminates divergence between separate UI subscribers.
 */
class SlyrClock(
    private val latencyModel: LatencyModel,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) {
    private val _heardTimeMs = MutableStateFlow(0L)
    val heardTimeMs: StateFlow<Long> = _heardTimeMs.asStateFlow()

    private var rawPositionMs: Long = 0L
    private var userOffsetMs: Long = 0L
    private var isPlaying: Boolean = false
    private var lastTimeMark: TimeSource.Monotonic.ValueTimeMark? = null

    init {
        scope.launch {
            while (isActive) {
                if (isPlaying) {
                    val mark = lastTimeMark
                    if (mark != null) {
                        val elapsedMs = mark.elapsedNow().inWholeMilliseconds
                        if (elapsedMs > 0L) {
                            rawPositionMs += elapsedMs
                            lastTimeMark = TimeSource.Monotonic.markNow()
                        }
                    } else {
                        lastTimeMark = TimeSource.Monotonic.markNow()
                    }

                    recomputeHeardTime()
                }
                delay(16L) // ~60 Hz cadence for clock interpolation
            }
        }
    }

    /**
     * Updates playback state and position directly from player/HAL anchors.
     */
    fun updatePosition(positionMs: Long, playing: Boolean, isSeek: Boolean = false) {
        rawPositionMs = positionMs
        isPlaying = playing
        lastTimeMark = if (playing) TimeSource.Monotonic.markNow() else null

        if (isSeek) {
            latencyModel.snapToTarget()
            recomputeHeardTime(forceSnap = true)
        } else {
            recomputeHeardTime()
        }
    }

    /**
     * Updates user-configured lyric offset preference.
     */
    fun setUserOffsetMs(offsetMs: Long) {
        userOffsetMs = offsetMs
        recomputeHeardTime()
    }

    private fun recomputeHeardTime(forceSnap: Boolean = false) {
        val delayMs = latencyModel.slewedDelayMs.value
        val computed = rawPositionMs - delayMs - userOffsetMs

        if (forceSnap || !isPlaying) {
            _heardTimeMs.value = computed
        } else {
            // Guarantee monotonic non-decreasing heardTime during active continuous playback
            val current = _heardTimeMs.value
            _heardTimeMs.value = if (computed >= current) computed else current
        }
    }
}
