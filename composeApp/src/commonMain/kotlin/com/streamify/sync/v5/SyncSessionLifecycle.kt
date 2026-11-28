package com.streamify.sync.v5

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class SignOutReason {
    USER_SIGN_OUT,
    ACCOUNT_SWITCH,
    DEVICE_REVOKED,
}

data class SessionEpoch(
    val epochId: Long = 0L,
    val userId: String? = null,
    val scope: CoroutineScope? = null,
)

interface SyncSessionLifecycle {
    val activeEpoch: StateFlow<SessionEpoch>
    suspend fun onUserAuthenticated(userId: String)
    suspend fun onUserSignedOut(reason: SignOutReason)
    suspend fun <T> withBackupRestoreFence(block: suspend () -> T): T
}

/**
 * Fences account switches, sign-outs, `DEVICE_REVOKED` lockouts, and `.backup` restores
 * behind a monotonically increasing [SessionEpoch] so stale in-flight responses never commit.
 */
class DefaultSyncSessionLifecycle(
    private val store: SyncV5Store,
    private val writeGuard: SyncWriteGuard,
    private val clearLegacyCoreOutbox: suspend () -> Unit = {},
) : SyncSessionLifecycle {

    private val lifecycleMutex = Mutex()
    private val _activeEpoch = MutableStateFlow(SessionEpoch())
    override val activeEpoch: StateFlow<SessionEpoch> = _activeEpoch.asStateFlow()

    override suspend fun onUserAuthenticated(userId: String) {
        lifecycleMutex.withLock {
            val current = _activeEpoch.value
            if (current.userId == userId && current.scope != null) return
            if (current.userId != null && current.userId != userId) {
                current.scope?.cancel()
                store.wipeAllForAccountSwitch()
                clearLegacyCoreOutbox()
            } else {
                current.scope?.cancel()
            }
            _activeEpoch.value = SessionEpoch(
                epochId = current.epochId + 1L,
                userId = userId,
                scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            )
        }
    }

    override suspend fun onUserSignedOut(reason: SignOutReason) {
        lifecycleMutex.withLock {
            val current = _activeEpoch.value
            current.scope?.cancel()
            store.wipeAllForAccountSwitch()
            clearLegacyCoreOutbox()
            _activeEpoch.value = SessionEpoch(
                epochId = current.epochId + 1L,
                userId = null,
                scope = null,
            )
        }
    }

    override suspend fun <T> withBackupRestoreFence(block: suspend () -> T): T {
        return lifecycleMutex.withLock {
            val current = _activeEpoch.value
            current.scope?.cancel()
            val nextEpoch = current.epochId + 1L
            _activeEpoch.value = SessionEpoch(epochId = nextEpoch, userId = current.userId, scope = null)

            val result = writeGuard.withRemoteApply { block() }

            store.wipeAllForAccountSwitch()
            clearLegacyCoreOutbox()
            _activeEpoch.value = SessionEpoch(
                epochId = nextEpoch + 1L,
                userId = current.userId,
                scope = current.userId?.let { CoroutineScope(SupervisorJob() + Dispatchers.Default) },
            )
            result
        }
    }
}
