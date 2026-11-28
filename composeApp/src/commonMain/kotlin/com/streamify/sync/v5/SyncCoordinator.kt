package com.streamify.sync.v5

import com.streamify.supabase.v5.DeviceRevokedEvent
import com.streamify.supabase.v5.SupabaseRpcExecutor
import com.streamify.sync.v5.planea.PlaneAFsmState
import com.streamify.sync.v5.planea.PlaneAStateSyncCoordinator
import com.streamify.sync.v5.planeb.PlaybackEventPipeline
import com.streamify.sync.v5.planeb.PlaybackFlushOutcome
import com.streamify.sync.v5.planeb.SearchHistoryCoordinator
import com.streamify.sync.v5.planec.DeviceMetadata
import com.streamify.sync.v5.planec.DeviceSessionRegistrar
import com.streamify.sync.v5.planec.PlaybackLeaseSnapshot
import com.streamify.sync.v5.planec.PresenceLeaseClient
import com.streamify.sync.v5.planec.SessionSecurityGuard
import com.streamify.sync.v5.planed.CatalogHydrationCoordinator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class SyncUiState(
    val userId: String? = null,
    val planeAState: PlaneAFsmState = PlaneAFsmState.IDLE,
    val pendingOutboxCount: Int = 0,
    val deadLetterCount: Int = 0,
    val lastSyncedAtMs: Long = 0L,
    val clockSkewOffsetMs: Long? = null,
    val lastError: String? = null,
)

interface SyncCoordinator {
    val uiState: StateFlow<SyncUiState>
    suspend fun onAccountAuthenticated(userId: String, deviceMeta: DeviceMetadata)
    suspend fun onAccountSignedOut(reason: SignOutReason)
    suspend fun triggerPlaneASync(deviceId: String): PlaneAFsmState
    suspend fun flushPlaybackEvents(deviceId: String): PlaybackFlushOutcome
    suspend fun syncSearchHistory(): Boolean
    suspend fun sendPresenceHeartbeat(snapshot: PlaybackLeaseSnapshot)
    suspend fun stopPresence(snapshot: PlaybackLeaseSnapshot, reason: String = "paused")
    suspend fun repairCatalog(force: Boolean = false): Int
    suspend fun <T> withBackupRestoreFence(block: suspend () -> T): T
}

/**
 * Plane-isolated Sync v5 orchestrator.
 * Each plane owns its own mutex/coroutine execution so a slow Plane A bootstrap
 * never blocks Plane B playback flushes or Plane C presence heartbeats.
 */
class StreamifySyncCoordinator(
    private val lifecycle: SyncSessionLifecycle,
    private val store: SyncV5Store,
    private val planeA: PlaneAStateSyncCoordinator,
    private val playbackPipeline: PlaybackEventPipeline,
    private val searchCoordinator: SearchHistoryCoordinator,
    private val presenceClient: PresenceLeaseClient,
    private val deviceRegistrar: DeviceSessionRegistrar,
    private val catalogHydrator: CatalogHydrationCoordinator,
    private val coreBridge: CoreSyncBridge? = null,
    private val rpcExecutor: SupabaseRpcExecutor? = null,
    private val onRevokedTeardown: suspend (DeviceRevokedEvent) -> Unit = {},
) : SyncCoordinator {

    private val playbackMutex = Mutex()
    private val searchMutex = Mutex()
    private val presenceMutex = Mutex()
    private val hydrationMutex = Mutex()

    private val _uiState = MutableStateFlow(SyncUiState())
    override val uiState: StateFlow<SyncUiState> = _uiState.asStateFlow()

    private var securityGuard: SessionSecurityGuard? = null

    override suspend fun onAccountAuthenticated(userId: String, deviceMeta: DeviceMetadata) {
        lifecycle.onUserAuthenticated(userId)
        val epoch = lifecycle.activeEpoch.value
        val scope = epoch.scope
        if (rpcExecutor != null && scope != null) {
            securityGuard?.stop()
            securityGuard = SessionSecurityGuard(rpcExecutor) { event ->
                onRevokedTeardown(event)
                onAccountSignedOut(SignOutReason.DEVICE_REVOKED)
            }.also { it.start(scope) }
        }
        deviceRegistrar.registerIfNeeded(deviceMeta, force = true)
        _uiState.value = _uiState.value.copy(userId = userId, lastError = null)
    }

    override suspend fun onAccountSignedOut(reason: SignOutReason) {
        securityGuard?.stop()
        deviceRegistrar.reset()
        lifecycle.onUserSignedOut(reason)
        _uiState.value = SyncUiState()
    }

    override suspend fun triggerPlaneASync(deviceId: String): PlaneAFsmState {
        val userId = lifecycle.activeEpoch.value.userId ?: return PlaneAFsmState.IDLE
        coreBridge?.drainCoreMutationsIntoV5()
        val state = planeA.syncOnce(userId, deviceId)
        val status = planeA.status.value
        _uiState.value = _uiState.value.copy(
            planeAState = state,
            pendingOutboxCount = store.pendingCount(),
            lastSyncedAtMs = status.lastSyncedAtMs,
            lastError = status.lastError,
        )
        return state
    }

    override suspend fun flushPlaybackEvents(deviceId: String): PlaybackFlushOutcome =
        playbackMutex.withLock {
            playbackPipeline.flushPending(deviceId)
        }

    override suspend fun syncSearchHistory(): Boolean =
        searchMutex.withLock {
            searchCoordinator.sync()
        }

    override suspend fun sendPresenceHeartbeat(snapshot: PlaybackLeaseSnapshot) {
        presenceMutex.withLock {
            presenceClient.sendPlaybackHeartbeat(snapshot)
        }
    }

    override suspend fun stopPresence(snapshot: PlaybackLeaseSnapshot, reason: String) {
        presenceMutex.withLock {
            presenceClient.stopLease(snapshot, reason = reason, saveHandoff = true)
        }
    }

    override suspend fun repairCatalog(force: Boolean): Int =
        hydrationMutex.withLock {
            catalogHydrator.repairMissingTracks(force = force)
        }

    override suspend fun <T> withBackupRestoreFence(block: suspend () -> T): T =
        lifecycle.withBackupRestoreFence(block)
}
