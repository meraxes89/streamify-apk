package com.streamify.app.util

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.content.getSystemService

object StreamifyHapticEngine {
    private var vibrator: Vibrator? = null
    private var hasAmplitudeControl: Boolean = false
    private var hasPredefinedEffects: Boolean = false
    var isEnabled: Boolean = true

    // Cached Zero-Allocation Effects
    private var scrubberTick: VibrationEffect? = null
    private var heartbeatFlutter: VibrationEffect? = null
    private var tokenImpact: VibrationEffect? = null
    private var magneticDetent: VibrationEffect? = null
    private var playbackPulse: VibrationEffect? = null
    private var queueGrab: VibrationEffect? = null

    fun init(context: Context) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val manager = context.getSystemService<VibratorManager>()
                vibrator = manager?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                vibrator = context.getSystemService<Vibrator>()
            }

            val vib = vibrator ?: return
            if (!vib.hasVibrator()) return

            hasAmplitudeControl = vib.hasAmplitudeControl()
            hasPredefinedEffects = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

            buildEffects()
        } catch (e: Exception) {
            // Silently absorb init issues on custom ROMs
        }
    }

    private fun buildEffects() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        try {
            // 1. Scrubber Rotary Tick (5ms)
            scrubberTick = if (hasPredefinedEffects) {
                VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK)
            } else {
                VibrationEffect.createOneShot(5, if (hasAmplitudeControl) 50 else VibrationEffect.DEFAULT_AMPLITUDE)
            }

            // 2. Heartbeat Double-Flutter (15ms -> 40ms rest -> 25ms)
            heartbeatFlutter = if (hasAmplitudeControl) {
                VibrationEffect.createWaveform(
                    longArrayOf(0, 15, 40, 25),
                    intArrayOf(0, 255, 0, 180),
                    -1
                )
            } else {
                VibrationEffect.createWaveform(longArrayOf(0, 15, 40, 25), -1)
            }

            // 3. 3D Token Impact (15ms snap)
            tokenImpact = if (hasPredefinedEffects) {
                VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK)
            } else {
                VibrationEffect.createOneShot(15, if (hasAmplitudeControl) 200 else VibrationEffect.DEFAULT_AMPLITUDE)
            }

            // 4. Magnetic Detent (8ms double click)
            magneticDetent = if (hasPredefinedEffects) {
                VibrationEffect.createPredefined(VibrationEffect.EFFECT_DOUBLE_CLICK)
            } else {
                VibrationEffect.createWaveform(longArrayOf(0, 8, 30, 8), -1)
            }

            // 5. Playback Transient Pulse (10ms tick)
            playbackPulse = if (hasPredefinedEffects) {
                VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK)
            } else {
                VibrationEffect.createOneShot(10, if (hasAmplitudeControl) 120 else VibrationEffect.DEFAULT_AMPLITUDE)
            }

            // 6. Queue Drag Grab (Heavy click)
            queueGrab = if (hasPredefinedEffects) {
                VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK)
            } else {
                VibrationEffect.createOneShot(20, if (hasAmplitudeControl) 255 else VibrationEffect.DEFAULT_AMPLITUDE)
            }
        } catch (e: Exception) {
            // Graceful fallback
        }
    }

    // --- Public Trigger API ---
    fun scrubberTick() = vibrate(scrubberTick)
    fun heartbeatFlutter() = vibrate(heartbeatFlutter)
    fun tokenImpact() = vibrate(tokenImpact)
    fun tokenImpactDetent() = vibrate(tokenImpact ?: magneticDetent)
    fun magneticDetent() = vibrate(magneticDetent)
    fun playbackPulse() = vibrate(playbackPulse)
    fun queueGrab() = vibrate(queueGrab)
    fun magneticQueueGrab() = vibrate(queueGrab)

    private var thresholdCrossed = false
    fun evaluatePull(progress: Float) {
        if (progress >= 1f && !thresholdCrossed) {
            thresholdCrossed = true
            magneticDetent()
        } else if (progress < 0.85f) {
            thresholdCrossed = false
        }
    }

    private fun vibrate(effect: VibrationEffect?) {
        if (!isEnabled || effect == null) return
        val vib = vibrator ?: return

        // OEM Safety Net: Prevent crashes on custom/broken OEM firmware
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vib.vibrate(effect)
            } else {
                @Suppress("DEPRECATION")
                vib.vibrate(10)
            }
        } catch (e: Exception) {
            // Silently fail. UI thread must never crash due to haptics.
        }
    }
}
