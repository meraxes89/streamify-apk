package com.streamify.sync.v5.planea

import com.streamify.sync.HybridLogicalClock
import com.streamify.sync.v5.SyncCheckpointRow
import com.streamify.sync.v5.SyncV5Store
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock

enum class PlaneAFsmState {
    IDLE,
    BOOTSTRAPPING,
    PUSHING,
    PULLING,
    CONVERGED,
    CLOCK_SKEW_BLOCKED,
    BACKOFF,
}

data class PlaneASyncStatus(
    val fsmState: PlaneAFsmState = PlaneAFsmState.IDLE,
    val lastSyncedAtMs: Long = 0L,
    val pullCursorRev: Long = 0L,
    val pendingCount: Int = 0,
    val lastError: String? = null,
    val clockSkewServerMs: Long? = null,
)

/**
 * Deterministic Plane A FSM orchestrator (`IDLE` -> `BOOTSTRAPPING` -> `PUSHING` -> `PULLING` -> `CONVERGED`).
 * Serialized strictly under its own [planeAMutex] so it never blocks Plane B, C, or D.
 */
class PlaneAStateSyncCoordinator(
    private val store: SyncV5Store,
    private val outboxProcessor: SyncOutboxProcessor,
    private val deltaReplicator: DeltaReplicator,
    private val bootstrapCoordinator: BootstrapCoordinator,
    private val onDeviceRevoked: suspend (String?) -> Unit = {},
    private val nowMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private val planeAMutex = Mutex()
    private val _status = MutableStateFlow(PlaneASyncStatus())
    val status: StateFlow<PlaneASyncStatus> = _status.asStateFlow()

    private var lastPrunedAtMs: Long = 0L

    suspend fun syncOnce(userId: String, deviceId: String): PlaneAFsmState = planeAMutex.withLock {
        store.resetInFlightToPending()

        var checkpoint = store.getCheckpoint(userId)
            ?: SyncCheckpointRow(userId = userId, deviceId = deviceId)
        if (checkpoint.lastHlc > 0L) {
            HybridLogicalClock.restore(checkpoint.lastHlc)
        }

        if (!checkpoint.bootstrapComplete) {
            _status.value = _status.value.copy(fsmState = PlaneAFsmState.BOOTSTRAPPING, lastError = null)
            when (val boot = bootstrapCoordinator.runBootstrap(userId, deviceId)) {
                is BootstrapOutcome.Completed -> {
                    checkpoint = store.getCheckpoint(userId) ?: checkpoint
                }
                is BootstrapOutcome.DeviceRevoked -> {
                    onDeviceRevoked(boot.revokedAt)
                    _status.value = _status.value.copy(fsmState = PlaneAFsmState.BACKOFF, lastError = "DEVICE_REVOKED")
                    return@withLock PlaneAFsmState.BACKOFF
                }
                is BootstrapOutcome.TransientFailure -> {
                    _status.value = _status.value.copy(fsmState = PlaneAFsmState.BACKOFF, lastError = boot.message)
                    return@withLock PlaneAFsmState.BACKOFF
                }
            }
        }

        _status.value = _status.value.copy(fsmState = PlaneAFsmState.PUSHING, lastError = null)
        when (val push = outboxProcessor.pushPending(userId, deviceId, checkpoint.pullCursorRev)) {
            is PushOutcome.Completed -> Unit
            is PushOutcome.ClockSkewBlocked -> {
                _status.value = _status.value.copy(
                    fsmState = PlaneAFsmState.CLOCK_SKEW_BLOCKED,
                    clockSkewServerMs = push.serverTimeMs,
                    lastError = "CLOCK_SKEW_EXCESSIVE",
                )
                return@withLock PlaneAFsmState.CLOCK_SKEW_BLOCKED
            }
            is PushOutcome.DeviceRevoked -> {
                onDeviceRevoked(push.revokedAt)
                _status.value = _status.value.copy(fsmState = PlaneAFsmState.BACKOFF, lastError = "DEVICE_REVOKED")
                return@withLock PlaneAFsmState.BACKOFF
            }
            is PushOutcome.TransientFailure -> {
                _status.value = _status.value.copy(fsmState = PlaneAFsmState.BACKOFF, lastError = push.message)
                return@withLock PlaneAFsmState.BACKOFF
            }
        }

        _status.value = _status.value.copy(fsmState = PlaneAFsmState.PULLING)
        when (val pull = deltaReplicator.pullToHead(userId, deviceId)) {
            is PullOutcome.Completed -> {
                val now = nowMs()
                if (now - lastPrunedAtMs >= PRUNE_INTERVAL_MS) {
                    val cutoffHlc = HybridLogicalClock.pack(now - SEVEN_DAYS_MS, 0)
                    store.pruneSentOlderThan(cutoffHlc)
                    lastPrunedAtMs = now
                }
                _status.value = PlaneASyncStatus(
                    fsmState = PlaneAFsmState.CONVERGED,
                    lastSyncedAtMs = now,
                    pullCursorRev = pull.cursorRev,
                    pendingCount = store.pendingCount(),
                )
                return@withLock PlaneAFsmState.CONVERGED
            }
            is PullOutcome.CursorExpired -> {
                _status.value = _status.value.copy(fsmState = PlaneAFsmState.BOOTSTRAPPING)
                val reboot = bootstrapCoordinator.runBootstrap(userId, deviceId)
                if (reboot is BootstrapOutcome.Completed) {
                    val catchUp = deltaReplicator.pullToHead(userId, deviceId)
                    if (catchUp is PullOutcome.Completed) {
                        _status.value = PlaneASyncStatus(
                            fsmState = PlaneAFsmState.CONVERGED,
                            lastSyncedAtMs = nowMs(),
                            pullCursorRev = catchUp.cursorRev,
                            pendingCount = store.pendingCount(),
                        )
                        return@withLock PlaneAFsmState.CONVERGED
                    }
                }
                _status.value = _status.value.copy(fsmState = PlaneAFsmState.BACKOFF, lastError = "CURSOR_EXPIRED_RETRY")
                return@withLock PlaneAFsmState.BACKOFF
            }
            is PullOutcome.DeviceRevoked -> {
                onDeviceRevoked(pull.revokedAt)
                _status.value = _status.value.copy(fsmState = PlaneAFsmState.BACKOFF, lastError = "DEVICE_REVOKED")
                return@withLock PlaneAFsmState.BACKOFF
            }
            is PullOutcome.TransientFailure -> {
                _status.value = _status.value.copy(fsmState = PlaneAFsmState.BACKOFF, lastError = pull.message)
                return@withLock PlaneAFsmState.BACKOFF
            }
        }
    }

    companion object {
        private const val SEVEN_DAYS_MS = 7L * 24L * 60L * 60L * 1000L
        private const val PRUNE_INTERVAL_MS = 24L * 60L * 60L * 1000L
    }
}
