package com.streamify.sync.v5

import com.streamify.supabase.v5.PlaneAStateSyncClient
import com.streamify.supabase.v5.PlaneBEventClient
import com.streamify.supabase.v5.PlaneCPresenceClient
import com.streamify.supabase.v5.PlaneDCatalogClient
import com.streamify.sync.v5.planea.BootstrapCoordinator
import com.streamify.sync.v5.planea.DeltaReplicator
import com.streamify.sync.v5.planea.EntityApplierRegistry
import com.streamify.sync.v5.planea.PlaneAStateSyncCoordinator
import com.streamify.sync.v5.planea.SyncOutboxProcessor
import com.streamify.sync.v5.planeb.InMemoryPlaybackEventStorage
import com.streamify.sync.v5.planeb.InMemorySearchHistoryStorage
import com.streamify.sync.v5.planeb.PendingPlaybackEvent
import com.streamify.sync.v5.planeb.PlaybackEventPipeline
import com.streamify.sync.v5.planeb.PlaybackFlushOutcome
import com.streamify.sync.v5.planeb.SearchHistoryCoordinator
import com.streamify.sync.v5.planec.DeviceMetadata
import com.streamify.sync.v5.planec.DeviceSessionRegistrar
import com.streamify.sync.v5.planec.PlaybackLeaseSnapshot
import com.streamify.sync.v5.planec.PresenceLeaseClient
import com.streamify.sync.v5.planec.PresenceLeaseState
import com.streamify.sync.v5.planed.CatalogHydrationCoordinator
import com.streamify.sync.v5.planed.InMemorySongCatalogStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaneEV5Test {

    @Test
    fun settingsObserverSuppressesExactRemoteEchoOnly() : Unit = runBlocking {
        val store = InMemorySyncV5Store()
        val observer = SettingsSyncObserver(store) { "dev-1" }

        observer.markRemoteWrite("theme_mode", "\"DARK\"")
        assertFalse(observer.onLocalSettingChanged("theme_mode", "\"DARK\""))
        assertEquals(0, store.pendingCount())

        // Subsequent user toggle is NOT suppressed
        assertTrue(observer.onLocalSettingChanged("theme_mode", "\"LIGHT\""))
        assertEquals(1, store.pendingCount())
    }

    @Test
    fun coreSyncBridgeDrainsAndClearsCoreSource() : Unit = runBlocking {
        val store = InMemorySyncV5Store()
        val coreRows = mutableListOf(
            CorePendingMutation(1L, "like", "yt:abc", "I", """{"track_id":"yt:abc"}"""),
            CorePendingMutation(2L, "playlist", "pl-1", "D", """{"id":"pl-1"}"""),
        )
        var cleared = false
        val bridge = CoreSyncBridge(
            coreSource = object : CoreSyncSource {
                override suspend fun readPendingBatch(limit: Int) = coreRows.take(limit)
                override suspend fun deleteConsumed(rowIds: List<Long>) {
                    coreRows.removeAll { it.coreRowId in rowIds }
                }
                override suspend fun clearAll() {
                    cleared = true
                    coreRows.clear()
                }
            },
            store = store,
            deviceIdProvider = { "dev-1" },
        )

        val drained = bridge.drainCoreMutationsIntoV5()
        assertEquals(2, drained)
        // Core rows remain until server acks them (P0-6 zero-data-loss guarantee)
        assertEquals(2, coreRows.size)
        assertEquals(2, store.pendingCount())

        val claimed = store.claimPendingBatch(10)
        bridge.onMutationsAcked(claimed.map { it.mutationId })
        assertTrue(coreRows.isEmpty())

        bridge.clearAll()
        assertTrue(cleared)
    }

    @Test
    fun slowPlaneABootstrapNeverBlocksPlaneCHeartbeatOrPlaneBFlush() : Unit = runBlocking {
        val planeABlocked = CompletableDeferred<Unit>()
        val planeAResumed = CompletableDeferred<Unit>()

        val executor = FakeRpcExecutor { rpcName, _ ->
            when (rpcName) {
                "sync_bootstrap_v5" -> {
                    planeABlocked.complete(Unit)
                    planeAResumed.await()
                    FakeRpcResponse(
                        200,
                        """{"protocol_version":5,"status":"OK","snapshot_rev":"1","has_more":false,"rows":[]}""",
                    )
                }
                "record_device_session" -> FakeRpcResponse(200, """{"status":"OK","device_id":"dev-1","platform":"android"}""")
                "heartbeat_presence_v2" -> FakeRpcResponse(200, """{"status":"OK","state_seq":"1"}""")
                "ingest_playback_events" -> FakeRpcResponse(
                    200,
                    """{"status":"OK","results":[{"client_event_id":"e1","status":"inserted","accepted_duration_sec":30}]}""",
                )
                else -> FakeRpcResponse(200, """{"status":"OK"}""")
            }
        }
        val store = InMemorySyncV5Store()
        val guard = createPlatformSyncWriteGuard()
        val lifecycle = DefaultSyncSessionLifecycle(store, guard)
        val planeAClient = PlaneAStateSyncClient(executor)
        val planeA = PlaneAStateSyncCoordinator(
            store = store,
            outboxProcessor = SyncOutboxProcessor(store, planeAClient),
            deltaReplicator = DeltaReplicator(store, planeAClient, EntityApplierRegistry(emptyList()), guard),
            bootstrapCoordinator = BootstrapCoordinator(store, planeAClient, EntityApplierRegistry(emptyList()), guard),
        )
        val playbackStorage = InMemoryPlaybackEventStorage().apply {
            upsert(PendingPlaybackEvent("e1", "yt:abc", "2026-11-28T00:00:00Z", 30))
        }
        val playbackPipeline = PlaybackEventPipeline(playbackStorage, PlaneBEventClient(executor)) { 1000L }
        val searchCoordinator = SearchHistoryCoordinator(InMemorySearchHistoryStorage(), PlaneBEventClient(executor))
        val presenceClient = PresenceLeaseClient(PlaneCPresenceClient(executor)) { 1000L }
        val registrar = DeviceSessionRegistrar(PlaneCPresenceClient(executor)) { 1000L }
        val catalogHydrator = CatalogHydrationCoordinator(InMemorySongCatalogStore(), PlaneDCatalogClient(executor)) { 1000L }

        val coordinator = StreamifySyncCoordinator(
            lifecycle = lifecycle,
            store = store,
            planeA = planeA,
            playbackPipeline = playbackPipeline,
            searchCoordinator = searchCoordinator,
            presenceClient = presenceClient,
            deviceRegistrar = registrar,
            catalogHydrator = catalogHydrator,
            rpcExecutor = executor,
        )

        coordinator.onAccountAuthenticated(
            "user-1",
            DeviceMetadata("dev-1", "Pixel", "android", appVersion = "5.0.0", versionCode = 500),
        )

        val slowPlaneAJob = async { coordinator.triggerPlaneASync("dev-1") }
        planeABlocked.await()

        // While Plane A is blocked in bootstrap, Plane C & Plane B complete immediately
        coordinator.sendPresenceHeartbeat(PlaybackLeaseSnapshot("dev-1", "Pixel", "android", isPlaying = true, trackId = "yt:abc"))
        assertEquals(PresenceLeaseState.PLAYING_LEASE_ACTIVE, presenceClient.state)

        val flushOutcome = coordinator.flushPlaybackEvents("dev-1")
        assertEquals(PlaybackFlushOutcome.Flushed(acked = 1, requeued = 0, rejected = 0), flushOutcome)

        planeAResumed.complete(Unit)
        slowPlaneAJob.await()
    }
}
