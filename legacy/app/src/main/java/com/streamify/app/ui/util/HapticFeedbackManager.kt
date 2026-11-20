package com.streamify.app.ui.util

import androidx.core.content.getSystemService

/**
 * Multi-sensory tactile haptic engine (Phase 5, deliverable 4).
 *
 * A centralized, throttle-aware haptic manager for every playback gesture:
 *  - Play / Pause ............ crisp confirmation click (EFFECT_CLICK)
 *  - Seek scrubbing .......... micro-tick pulses (EFFECT_TICK) every 5s of
 *                              audio scrubbed, or on 10% volume increments
 *  - Favorite / Like ......... dual-pulse heartbeat: TICK then CLICK exactly
 *                              80ms later (single waveform — deterministic
 *                              spacing, synchronized with the heart burst)
 *  - Queue reorder ........... firm grab click on pickup, subtle tick on
 *                              index-boundary crossings, soft drop thud
 *  - Jam upvote .............. energetic rising tactile pitch
 *
 * ARCHITECTURE — pure-Kotlin core, Android isolated behind two seams:
 *  - [HapticActuator]: the vibration backend. Production wraps
 *    Vibrator/VibratorManager + VibrationEffect with API-level guards; the
 *    JVM unit tests inject a recording actuator.
 *  - [HapticClock]: monotonic time source for trigger throttling (the
 *    vibrator HAL queues effects — unthrottled spam is both perceptually
 *    muddy and a real UI-thread jank source). Tests inject a manual clock.
 *
 * Trigger throttling: every pattern class carries a minimum re-trigger
 * interval; out-of-window triggers are silently dropped so a 120Hz gesture
 * stream can never queue an effect storm.
 */

// ── Pure data model ───────────────────────────────────────────────────────

/**
 * A haptic waveform: alternating delay/duration pairs (ms) with optional
 * per-segment amplitudes (0-255), or a platform predefined effect id.
 * Pure value — constructible and assertable on the JVM.
 */
class HapticWaveform private constructor(
    val timings: LongArray,
    val amplitudes: IntArray?,
    val predefinedEffect: Int?
) {
    companion object {
        /** Platform predefined effect (e.g. EFFECT_CLICK / EFFECT_TICK). */
        fun predefined(effect: Int): HapticWaveform =
            HapticWaveform(LongArray(0), null, effect)

        /**
         * Custom waveform from per-pulse primitives, converted HERE —
         * purely — into the platform-native arrays: [durations] ms ON per
         * pulse, [amplitudes] 0..255 per pulse, [gaps] ms of silence
         * BETWEEN consecutive pulses (gaps.size == durations.size - 1).
         * The resulting [timings] alternates ON/OFF starting with ON and
         * [amplitudes] aligns 1:1 with zeros on OFF segments — the actuator
         * is a dumb pass-through to VibrationEffect.createWaveform.
         */
        fun waveform(
            gaps: LongArray,
            durations: LongArray,
            amplitudes: IntArray
        ): HapticWaveform {
            require(durations.size == amplitudes.size) { "durations/amplitudes must align" }
            require(gaps.size == durations.size - 1) { "gaps must be durations.size - 1" }
            val n = durations.size
            if (n == 0) return HapticWaveform(LongArray(0), IntArray(0), null)
            val timings = LongArray(2 * n - 1)
            val amps = IntArray(2 * n - 1)
            for (i in 0 until n) {
                timings[2 * i] = durations[i]
                amps[2 * i] = amplitudes[i]
                if (i < n - 1) {
                    timings[2 * i + 1] = gaps[i]
                    amps[2 * i + 1] = 0
                }
            }
            return HapticWaveform(timings, amps, null)
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is HapticWaveform) return false
        if (timings.size != other.timings.size) return false
        if (predefinedEffect != other.predefinedEffect) return false
        if (amplitudes == null) return other.amplitudes == null
        if (other.amplitudes == null) return false
        return timings.contentEquals(other.timings) &&
                amplitudes.contentEquals(other.amplitudes)
    }

    override fun hashCode(): Int {
        var result = timings.contentHashCode()
        result = 31 * result + (amplitudes?.contentHashCode() ?: 0)
        result = 31 * result + (predefinedEffect ?: 0)
        return result
    }

    override fun toString(): String =
        "HapticWaveform(timings=${timings.contentToString()}, " +
                "amplitudes=${amplitudes?.contentToString()}, predefined=$predefinedEffect)"
}

// ── Seams ─────────────────────────────────────────────────────────────────

/** Vibration backend seam. */
interface HapticActuator {
    /** True when the backend can actually vibrate (HW + permission probe). */
    val hasVibrator: Boolean

    /** Fire a custom waveform (amplitudes non-null) or a predefined effect. */
    fun vibrate(waveform: HapticWaveform)
}

/** Monotonic clock seam for throttling. */
interface HapticClock {
    fun now(): Long
}

// ── Pattern vocabulary ─────────────────────────────────────────────────────

/** Pattern classes with their throttle windows (ms). */
enum class HapticPattern(val minIntervalMs: Long) {
    PLAY_PAUSE_CLICK(120),
    SCRUB_TICK(45),
    VOLUME_STEP(90),
    LIKE_HEARTBEAT(220),
    QUEUE_GRAB(180),
    QUEUE_BOUNDARY(70),
    QUEUE_DROP(150),
    JAM_UPRISE(200),
    SKIP_TRIGGER(150)
}

// ── Manager ───────────────────────────────────────────────────────────────

/**
 * Stateful haptic orchestrator. ONE instance per app; all triggers funnel
 * through here so throttling and the enabled gate are global.
 */
class HapticFeedbackManager(
    private val actuator: HapticActuator,
    private val clock: HapticClock
) {
    var isEnabled: Boolean = true

    private val lastTriggerAt = HashMap<HapticPattern, Long>()

    // ── Pattern builders (pure; assertable in tests) ──────────────────────

    /** Crisp confirmation click. */
    fun buildPlayPauseClick(): HapticWaveform = HapticWaveform.predefined(EFFECT_CLICK)

    /** Micro-tick pulse. */
    fun buildScrubTick(): HapticWaveform = HapticWaveform.predefined(EFFECT_TICK)

    /**
     * Dual-pulse heartbeat: TICK, exactly 80ms of silence, then CLICK —
     * expressed as a SINGLE waveform (the 80ms inter-pulse gap is a waveform
     * segment), so the spacing survives vibrator-HAL queueing and stays in
     * lockstep with the expanding heart-burst animation.
     */
    fun buildLikeHeartbeat(): HapticWaveform = HapticWaveform.waveform(
        gaps = longArrayOf(80L),
        durations = longArrayOf(15L, 25L),
        amplitudes = intArrayOf(255, 190)
    )

    /** Firm grab click on queue-row pickup. */
    fun buildQueueGrab(): HapticWaveform = HapticWaveform.predefined(EFFECT_HEAVY_CLICK)

    /** Subtle boundary-crossing tick. */
    fun buildQueueBoundaryTick(): HapticWaveform = HapticWaveform.predefined(EFFECT_TICK)

    /** Soft drop thud: short, low-amplitude pulse. */
    fun buildQueueDrop(): HapticWaveform = HapticWaveform.waveform(
        gaps = longArrayOf(),
        durations = longArrayOf(28L),
        amplitudes = intArrayOf(110)
    )

    /**
     * Energetic rising tactile pitch for a promoting Jam upvote: four
     * successively stronger, successively shorter pulses over ~230ms — a
     * perceptual "lift-off".
     */
    fun buildJamUpvoteRising(): HapticWaveform = HapticWaveform.waveform(
        gaps = longArrayOf(45L, 45L, 45L),
        durations = longArrayOf(40L, 32L, 26L, 22L),
        amplitudes = intArrayOf(60, 110, 170, 240)
    )

    /** Firm trigger at the swipe-to-skip arming threshold. */
    fun buildSkipTrigger(): HapticWaveform = HapticWaveform.predefined(EFFECT_CLICK)

    // ── Trigger API (throttled) ───────────────────────────────────────────

    fun playPauseClick() = trigger(HapticPattern.PLAY_PAUSE_CLICK, buildPlayPauseClick())
    fun likeHeartbeat() = trigger(HapticPattern.LIKE_HEARTBEAT, buildLikeHeartbeat())
    fun queueGrab() = trigger(HapticPattern.QUEUE_GRAB, buildQueueGrab())
    fun queueBoundaryTick() = trigger(HapticPattern.QUEUE_BOUNDARY, buildQueueBoundaryTick())
    fun queueDrop() = trigger(HapticPattern.QUEUE_DROP, buildQueueDrop())
    fun jamUpvoteRising() = trigger(HapticPattern.JAM_UPRISE, buildJamUpvoteRising())
    fun skipTrigger() = trigger(HapticPattern.SKIP_TRIGGER, buildSkipTrigger())

    /**
     * Seek-scrub micro-ticks: call on every scrub position update; a tick is
     * emitted each time the scrubbed position crosses a 5-second audio
     * boundary (independent of pixel detents), subject to throttling. The
     * first call of a gesture always fires (arming tick).
     */
    fun onScrubPositionChanged(positionMs: Long) {
        val bucket = positionMs / SCRUB_BUCKET_MS
        val isArming = lastScrubBucket == null
        if (isArming || bucket != lastScrubBucket) {
            lastScrubBucket = bucket
            trigger(HapticPattern.SCRUB_TICK, buildScrubTick(), force = isArming)
        }
    }

    /** Volume steps: a tick on each crossed 10% increment. */
    fun onVolumeFractionChanged(fraction: Float) {
        val clamped = fraction.coerceIn(0f, 1f)
        val bucket = (clamped * 10f).toInt()
        val isArming = lastVolumeBucket == null
        if (isArming || bucket != lastVolumeBucket) {
            lastVolumeBucket = bucket
            trigger(HapticPattern.VOLUME_STEP, buildScrubTick(), force = isArming)
        }
    }

    /** Reset scrub/volume bucket tracking (e.g. on gesture end). */
    fun resetBuckets() {
        lastScrubBucket = null
        lastVolumeBucket = null
    }

    // ── Internals ─────────────────────────────────────────────────────────

    private var lastScrubBucket: Long? = null
    private var lastVolumeBucket: Int? = null

    /** 5 seconds of scrubbed audio per micro-tick (design spec). */
    val scrubBucketMs: Long get() = SCRUB_BUCKET_MS

    private fun trigger(pattern: HapticPattern, waveform: HapticWaveform, force: Boolean = false) {
        if (!isEnabled || !actuator.hasVibrator) return
        val now = clock.now()
        val last = lastTriggerAt[pattern]
        if (!force && last != null && now - last < pattern.minIntervalMs) return
        lastTriggerAt[pattern] = now
        try {
            actuator.vibrate(waveform)
        } catch (_: Throwable) {
            // OEM safety net: the UI thread must never crash on haptics.
        }
    }

    companion object {
        const val SCRUB_BUCKET_MS = 5_000L

        // Android VibrationEffect constants (kept here so the pure core
        // stays free of android.os imports; values are platform-stable).
        const val EFFECT_CLICK = 0
        const val EFFECT_TICK = 2
        const val EFFECT_HEAVY_CLICK = 5

        /** Global instance wired in AppGraph with the Android actuator. */
        @Volatile
        var instance: HapticFeedbackManager? = null

        /** Convenience accessor that no-ops before init / when disabled. */
        fun get(): HapticFeedbackManager? = instance
    }
}

// ── Android production backend ──────────────────────────────────────────────

/**
 * Production actuator over Vibrator/VibratorManager with API-level guards:
 * predefined effects (Q+), amplitude waveforms (O+), one-shot fallback for
 * older devices, and an OEM crash safety net. Mirrors the guards proven in
 * StreamifyHapticEngine across Phase 1–4 CI runs.
 */
class AndroidHapticActuator(context: android.content.Context) : HapticActuator {

    private var vibrator: android.os.Vibrator? = null
    private var hasAmplitudeControl = false
    override var hasVibrator: Boolean = false
        private set

    init {
        try {
            val vib = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                val manager = context.getSystemService<android.os.VibratorManager>()
                manager?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService<android.os.Vibrator>()
            }
            vibrator = vib
            if (vib != null && vib.hasVibrator()) {
                hasVibrator = true
                hasAmplitudeControl = vib.hasAmplitudeControl()
            }
        } catch (_: Throwable) {
            // Custom ROMs: absorb init issues silently.
        }
    }

    override fun vibrate(waveform: HapticWaveform) {
        val vib = vibrator ?: return
        if (!hasVibrator) return
        try {
            val effect = when {
                waveform.predefinedEffect != null &&
                        android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q ->
                    android.os.VibrationEffect.createPredefined(waveform.predefinedEffect)
                waveform.predefinedEffect != null -> fallbackOneShot()
                android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O -> {
                    val amps = waveform.amplitudes
                        ?: IntArray(waveform.timings.size) { DEFAULT_AMP }
                    // No amplitude control: keep the ON/OFF structure, map any
                    // non-zero pulse to the platform default amplitude.
                    val scaled = if (!hasAmplitudeControl) {
                        IntArray(amps.size) { i ->
                            if (amps[i] > 0) android.os.VibrationEffect.DEFAULT_AMPLITUDE else 0
                        }
                    } else amps
                    if (waveform.timings.isEmpty()) fallbackOneShot()
                    else android.os.VibrationEffect.createWaveform(waveform.timings, scaled, -1)
                }
                else -> fallbackOneShot()
            }
            vib.vibrate(effect)
        } catch (_: Throwable) {
            // The UI thread must never crash due to haptics.
        }
    }

    private fun fallbackOneShot(): android.os.VibrationEffect =
        android.os.VibrationEffect.createOneShot(10, android.os.VibrationEffect.DEFAULT_AMPLITUDE)

    private companion object {
        const val DEFAULT_AMP = 180
    }
}

/** Uptime clock (immune to wall-clock changes). */
class UptimeHapticClock : HapticClock {
    override fun now(): Long = android.os.SystemClock.uptimeMillis()
}
