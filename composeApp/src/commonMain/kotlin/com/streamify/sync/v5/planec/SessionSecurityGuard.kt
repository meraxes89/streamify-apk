package com.streamify.sync.v5.planec

import com.streamify.supabase.v5.DeviceRevokedEvent
import com.streamify.supabase.v5.SupabaseRpcExecutor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Observes [SupabaseRpcExecutor.deviceRevokedEvents] across all Plane A/B/C RPCs and triggers
 * idempotent local session teardown when the current device is revoked.
 */
class SessionSecurityGuard(
    private val executor: SupabaseRpcExecutor,
    private val onDeviceRevoked: suspend (DeviceRevokedEvent) -> Unit,
) {
    private val teardownMutex = Mutex()
    private var watchJob: Job? = null
    private var handledForSession: Boolean = false

    fun start(scope: CoroutineScope) {
        watchJob?.cancel()
        handledForSession = false
        watchJob = scope.launch {
            executor.deviceRevokedEvents.collect { event ->
                teardownMutex.withLock {
                    if (!handledForSession) {
                        handledForSession = true
                        onDeviceRevoked(event)
                    }
                }
            }
        }
    }

    fun stop() {
        watchJob?.cancel()
        watchJob = null
        handledForSession = false
    }
}
