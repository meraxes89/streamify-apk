package com.streamify.sync.v5.planec

import com.streamify.supabase.v5.DeviceRevokedEvent
import com.streamify.supabase.v5.SupabaseRpcExecutor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Observes [SupabaseRpcExecutor.deviceRevoked] across all Plane A/B/C RPCs and triggers
 * immediate local session teardown when the current device is revoked.
 */
class SessionSecurityGuard(
    private val executor: SupabaseRpcExecutor,
    private val onDeviceRevoked: suspend (DeviceRevokedEvent) -> Unit,
) {
    private var watchJob: Job? = null

    fun start(scope: CoroutineScope) {
        watchJob?.cancel()
        watchJob = scope.launch {
            executor.deviceRevokedEvents.collectLatest { event ->
                onDeviceRevoked(event)
            }
        }
    }

    fun stop() {
        watchJob?.cancel()
        watchJob = null
    }
}
