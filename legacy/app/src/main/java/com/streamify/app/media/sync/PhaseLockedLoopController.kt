package com.streamify.app.media.sync
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.*
import kotlin.math.abs
import com.streamify.app.media.audio.SyncAudioProcessor

class PhaseLockedLoopController(
    private val player: Player,
    private val syncAudioProcessor: SyncAudioProcessor? = null
) {
    companion object {
        private const val KP = 0.0008f  // Proportional gain
        private const val KI = 0.00005f // Integral gain
        private const val MAX_DRIFT_TOLERANCE_MS = 20L
        private const val HARD_RESYNC_THRESHOLD_MS = 1500L
    }

    private var integralErrorAccumulator = 0.0f
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var pllJob: Job? = null
    private var isRunning = false

    @Volatile
    private var hostTargetPositionMs: Long = 0L

    @Volatile
    private var hostIsPlaying: Boolean = false

    @Volatile
    private var hostClockTimestamp: Long = 0L

    /**
     * Micro-adjusts playback speed to eliminate audio comb-filtering.
     * 
     * @param hostPositionMs Current high-precision timeline position of the Jam host
     * @param guestPositionMs Local ExoPlayer playback position
     */
    fun updatePllClock(hostPositionMs: Long, guestPositionMs: Long) {
        val phaseErrorMs = (hostPositionMs - guestPositionMs).toFloat()

        // 1. Hard Resync: If network dropped and drift exceeds 1.5s, seek directly
        if (abs(phaseErrorMs) > HARD_RESYNC_THRESHOLD_MS) {
            player.seekTo(hostPositionMs)
            integralErrorAccumulator = 0.0f
            player.playbackParameters = PlaybackParameters(1.0f)
            return
        }

        // 2. Lockstep: If drift is within +/-20ms, maintain normal 1.0x playback
        if (abs(phaseErrorMs) <= MAX_DRIFT_TOLERANCE_MS) {
            integralErrorAccumulator = 0.0f
            if (player.playbackParameters.speed != 1.0f) {
                player.playbackParameters = PlaybackParameters(1.0f)
            }
            return
        }

        // 3. PI Control Loop: Micro-adjust speed inaudibly (0.96x to 1.04x)
        integralErrorAccumulator += phaseErrorMs
        integralErrorAccumulator = integralErrorAccumulator.coerceIn(-500.0f, 500.0f)

        val speedAdjustment = (KP * phaseErrorMs) + (KI * integralErrorAccumulator)
        val targetSpeed = (1.0f + speedAdjustment).coerceIn(0.96f, 1.04f)

        player.playbackParameters = PlaybackParameters(targetSpeed)
    }

    fun startPll(precisionProtocol: PrecisionTimeProtocol) {
        if (isRunning) return
        isRunning = true

        pllJob?.cancel()
        pllJob = scope.launch {
            while (isActive && isRunning) {
                try {
                    if (player.isPlaying && hostIsPlaying && hostTargetPositionMs > 0) {
                        val synchronizedNow = precisionProtocol.getSynchronizedClockMs()
                        val hostElapsed = (synchronizedNow - hostClockTimestamp).coerceAtLeast(0L)
                        val expectedHostAcousticPos = hostTargetPositionMs + hostElapsed

                        val rawPlayerPos = player.currentPosition
                        val clientAcousticPos = syncAudioProcessor?.getAcousticPositionMs(rawPlayerPos) ?: rawPlayerPos

                        updatePllClock(expectedHostAcousticPos, clientAcousticPos)
                    }
                } catch (e: Exception) {
                    // Ignore transient PLL errors
                }
                delay(400) // 2.5Hz PLL correction loop
            }
        }
    }

    fun updateHostReference(positionMs: Long, isPlaying: Boolean, clockTimestamp: Long) {
        this.hostTargetPositionMs = positionMs
        this.hostIsPlaying = isPlaying
        this.hostClockTimestamp = clockTimestamp
    }

    fun reset() {
        integralErrorAccumulator = 0.0f
        player.playbackParameters = PlaybackParameters(1.0f)
    }

    fun stop() {
        isRunning = false
        pllJob?.cancel()
        reset()
    }
}
