package com.streamify.app.media.sync
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.streamify.app.data.NativeBridge
import com.streamify.app.data.models.Track
import com.streamify.app.jam.PlaybackReadyGate
import com.streamify.app.util.SLog
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicReference

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * ScheduledAudioScheduler v4 — atomic nanosecond countdown playout
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * Executes sample-accurate scheduled starts against the room's atomic clock
 * (PTP domain, [NativeBridge.nativeGetSynchronizedClockNanos]):
 *
 *  1. PRE-BUFFER — the track is prepared in a paused, READY state (gated by
 *     [PlaybackReadyGate], never a magic sleep) so the decoder has PCM
 *     decoded and the AudioTrack is primed before the deadline.
 *
 *  2. PLAYOUT-DELAY COMPENSATION — the fire instant is pulled EARLIER by the
 *     physical DAC / Bluetooth A2DP pipeline delay
 *     ([NativeBridge.nativeGetHardwarePlayoutDelayNanos]) so the ACOUSTIC
 *     onset — what reaches the listeners' ears — lands on the atomic target,
 *     not the electrical one.
 *
 *  3. THREE-STAGE WAIT — coarse coroutine delay() (battery friendly, ~ms
 *     accuracy) → 4 ms precision window (thread park granularity) →
 *     Thread.onSpinWait() busy-spin for the final sub-millisecond approach,
 *     bypassing the Linux scheduler tick entirely.
 *
 * Atomicity: the countdown is held in a single [AtomicReference] job slot —
 * scheduling a new target cancels any in-flight countdown before it can
 * release playback, so a superseded schedule can never fire late and fight
 * the newer one.
 */
class ScheduledAudioScheduler(
    private val player: Player,
    private val precisionProtocol: PrecisionTimeProtocol
) {
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val scheduledJob = AtomicReference<Job?>(null)

    /**
     * Legacy millisecond API (kept for existing callers): target expressed
     * in the atomic clock's millisecond domain.
     */
    fun scheduleAtomicPlayback(
        track: Track,
        targetAtomicTimestampMs: Long,
        startPositionMs: Long = 0L,
        onPlaybackStarted: (() -> Unit)? = null
    ) {
        scheduleAtomicPlaybackNanos(
            track = track,
            targetAtomicNanos = targetAtomicTimestampMs * 1_000_000L,
            startPositionMs = startPositionMs,
            onPlaybackStarted = onPlaybackStarted
        )
    }

    /**
     * Nanosecond-precision API: fires playback so the acoustic onset of the
     * first sample lands exactly on [targetAtomicNanos] of the room clock.
     */
    fun scheduleAtomicPlaybackNanos(
        track: Track,
        targetAtomicNanos: Long,
        startPositionMs: Long = 0L,
        onPlaybackStarted: (() -> Unit)? = null
    ) {
        // Atomic slot swap: cancel any superseded countdown first.
        scheduledJob.getAndSet(null)?.cancel()

        // The finally-block must only release the slot if it still holds THIS
        // countdown — but a local `val job` cannot be captured inside its own
        // initializer, so the release check goes through an indirection var
        // that is assigned right after launch returns.
        var jobRef: Job? = null
        val job = scope.launch {
            try {
                // 1. Prepare + pre-buffer in the paused state; the readiness
                //    gate suspends until Media3 reports STATE_READY.
                val mediaItem = MediaItem.Builder()
                    .setUri(track.filepath)
                    .setMediaId(track.id.toString())
                    .build()

                player.setMediaItem(mediaItem)
                if (startPositionMs > 0) player.seekTo(startPositionMs)
                player.prepare()
                player.playWhenReady = false
                if (player.playbackState != Player.STATE_READY) {
                    PlaybackReadyGate.awaitReadyThenSeek(
                        player = player,
                        positionMs = startPositionMs,
                        play = false,
                        timeoutMs = 5_000L,
                        tag = "AtomicScheduler:${track.title.take(16)}"
                    )
                }

                // 2. Pull the fire instant earlier by the hardware playout
                //    delay so the ACOUSTIC onset hits the atomic target.
                val playoutDelayNanos = playoutDelayNanos()
                val fireAtNanos = targetAtomicNanos - playoutDelayNanos

                // 3. Three-stage countdown.
                withContext(Dispatchers.Default) {
                    while (isActive) {
                        val nowNanos = atomicNowNanos()
                        val timeUntilNanos = fireAtNanos - nowNanos

                        when {
                            timeUntilNanos <= 0 -> {
                                // Target reached: release playback atomically.
                                withContext(Dispatchers.Main) {
                                    player.playWhenReady = true
                                    player.play()
                                    onPlaybackStarted?.invoke()
                                }
                                SLog.i(
                                    TAG,
                                    "atomic release: targetΔ=${(atomicNowNanos() - targetAtomicNanos) / 1000}µs " +
                                        "playoutComp=${playoutDelayNanos / 1_000_000}ms"
                                )
                                break
                            }
                            timeUntilNanos > 4_000_000 -> {
                                // Coarse sleep: conserve CPU and battery.
                                delay((timeUntilNanos - 3_000_000) / 1_000_000)
                            }
                            timeUntilNanos > 1_500_000 -> {
                                // Precision window: park-level granularity.
                                delay(1)
                            }
                            else -> {
                                // Final approach: tight spin, sub-millisecond.
                                Thread.onSpinWait()
                            }
                        }
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Cancelled (superseded or aborted): MUST rethrow — swallowing
                // it made cancel() trigger immediate playback.
                throw e
            } catch (e: Exception) {
                // If scheduled execution fails, fallback to immediate playback.
                SLog.e(TAG, "atomic schedule failed — falling back to immediate play", e)
                withContext(Dispatchers.Main) {
                    player.playWhenReady = true
                    player.play()
                    onPlaybackStarted?.invoke()
                }
            } finally {
                scheduledJob.compareAndSet(jobRef, null) // release the slot if still ours
            }
        }
        jobRef = job
        scheduledJob.set(job)
    }

    /** The atomic room clock in nanoseconds (PTP domain). */
    private fun atomicNowNanos(): Long = precisionProtocol.getSynchronizedClockNanos()

    /** Physical DAC / A2DP playout pipeline delay in nanoseconds. */
    private fun playoutDelayNanos(): Long {
        val nativeNanos = NativeBridge.hardwarePlayoutDelayNanos()
        if (nativeNanos >= 0L) return nativeNanos
        return precisionProtocol.estimatedPlayoutDelayNanos()
    }

    fun cancel() {
        scheduledJob.getAndSet(null)?.cancel()
    }

    fun release() {
        cancel()
        scope.cancel()
    }

    private companion object {
        const val TAG = "AtomicScheduler"
    }
}
