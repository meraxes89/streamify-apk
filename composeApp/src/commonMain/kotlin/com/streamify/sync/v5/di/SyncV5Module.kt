package com.streamify.sync.v5.di

import com.maxrave.domain.manager.DataStoreManager
import com.streamify.supabase.SupabaseClient
import com.streamify.supabase.SupabaseWrappedSync
import com.streamify.supabase.v5.PlaneAStateSyncClient
import com.streamify.supabase.v5.PlaneBEventClient
import com.streamify.supabase.v5.PlaneCPresenceClient
import com.streamify.supabase.v5.PlaneDCatalogClient
import com.streamify.supabase.v5.PlaneFCompanionClient
import com.streamify.supabase.v5.SupabaseRpcExecutor
import com.streamify.sync.StreamifySyncEngine
import com.streamify.sync.SyncOutboxRepository
import com.streamify.sync.v5.CoreSyncBridge
import com.streamify.sync.v5.CoreSyncSource
import com.streamify.sync.v5.DataStoreSyncV5Store
import com.streamify.sync.v5.DefaultSyncSessionLifecycle
import com.streamify.sync.v5.SettingsSyncObserver
import com.streamify.sync.v5.StreamifySyncCoordinator
import com.streamify.sync.v5.SyncCoordinator
import com.streamify.sync.v5.SyncOutboxCoreSource
import com.streamify.sync.v5.SyncSessionLifecycle
import com.streamify.sync.v5.SyncV5Store
import com.streamify.sync.v5.SyncWriteGuard
import com.streamify.sync.v5.createPlatformSyncV5Store
import com.streamify.sync.v5.createPlatformSyncWriteGuard
import com.streamify.sync.v5.planea.BootstrapCoordinator
import com.streamify.sync.v5.planea.DeltaReplicator
import com.streamify.sync.v5.planea.EntityApplierRegistry
import com.streamify.sync.v5.planea.PlaneAStateSyncCoordinator
import com.streamify.sync.v5.planea.SyncOutboxProcessor
import com.streamify.sync.v5.planea.buildDefaultPlaneAAppliers
import com.streamify.sync.v5.planeb.InMemoryPlaybackEventStorage
import com.streamify.sync.v5.planeb.InMemorySearchHistoryStorage
import com.streamify.sync.v5.planeb.PlaybackEventPipeline
import com.streamify.sync.v5.planeb.PlaybackEventStorage
import com.streamify.sync.v5.planeb.SearchHistoryCoordinator
import com.streamify.sync.v5.planeb.SearchHistoryStorage
import com.streamify.sync.v5.planeb.WrappedSyncCoordinator
import com.streamify.sync.v5.planec.DeviceSessionRegistrar
import com.streamify.sync.v5.planec.PresenceLeaseClient
import com.streamify.sync.v5.planed.CatalogHydrationCoordinator
import com.streamify.sync.v5.planed.InMemorySongCatalogStore
import com.streamify.sync.v5.planed.SongCatalogStore
import kotlinx.coroutines.flow.firstOrNull
import org.koin.dsl.module
import kotlin.time.Clock

/**
 * Constructor-injected Koin DI module for Sync v5 (Planes A–F).
 * Keeps zero global `KoinComponent` singletons in the sync layer.
 */
val syncV5Module = module {
    single<SyncWriteGuard> { createPlatformSyncWriteGuard() }
    single<SyncV5Store> {
        val ds = getOrNull<DataStoreManager>()
        if (ds != null) {
            DataStoreSyncV5Store(
                readSnapshot = { ds.getString("streamify_sync_v5_store_v1").firstOrNull() },
                writeSnapshot = { payload -> ds.putString("streamify_sync_v5_store_v1", payload) },
            )
        } else {
            createPlatformSyncV5Store()
        }
    }

    single<SupabaseRpcExecutor> { SupabaseRpcExecutor.default() }
    single { PlaneAStateSyncClient(get()) }
    single { PlaneBEventClient(get()) }
    single { PlaneCPresenceClient(get()) }
    single { PlaneDCatalogClient(get()) }
    single { PlaneFCompanionClient(get()) }

    single<PlaybackEventStorage> { InMemoryPlaybackEventStorage() }
    single<SearchHistoryStorage> { InMemorySearchHistoryStorage() }
    single<SongCatalogStore> { InMemorySongCatalogStore() }

    single { PlaybackEventPipeline(get(), get()) { Clock.System.now().toEpochMilliseconds() } }
    single { SearchHistoryCoordinator(get(), get()) }
    single { WrappedSyncCoordinator(get()) }
    single { PresenceLeaseClient(get()) { Clock.System.now().toEpochMilliseconds() } }
    single { DeviceSessionRegistrar(get()) { Clock.System.now().toEpochMilliseconds() } }
    single { CatalogHydrationCoordinator(get(), get()) { Clock.System.now().toEpochMilliseconds() } }

    single { SettingsSyncObserver(get()) { getOrNull<StreamifySyncEngine>()?.getOrCreateDeviceId() ?: "kmp_device" } }

    single<CoreSyncSource?> {
        getOrNull<SyncOutboxRepository>()?.let { SyncOutboxCoreSource(it) }
    }
    single<CoreSyncBridge?> {
        val src = getOrNull<CoreSyncSource>() ?: return@single null
        CoreSyncBridge(
            coreSource = src,
            store = get(),
            deviceIdProvider = { getOrNull<StreamifySyncEngine>()?.getOrCreateDeviceId() ?: "kmp_device" },
        )
    }

    single {
        EntityApplierRegistry(
            buildDefaultPlaneAAppliers(
                songRepository = getOrNull(),
                artistRepository = getOrNull(),
                albumRepository = getOrNull(),
                playlistRepository = getOrNull(),
                podcastRepository = getOrNull(),
                localPlaylistRepository = getOrNull(),
                dataStoreManager = getOrNull(),
                settingsObserver = getOrNull(),
            )
        )
    }

    single {
        val bridge = getOrNull<CoreSyncBridge>()
        SyncOutboxProcessor(
            store = get(),
            client = get(),
            onMutationsAcked = { ids -> bridge?.onMutationsAcked(ids) },
        )
    }
    single { DeltaReplicator(store = get(), client = get(), appliers = get(), writeGuard = get()) }
    single { BootstrapCoordinator(store = get(), client = get(), appliers = get(), writeGuard = get()) }
    single {
        PlaneAStateSyncCoordinator(
            store = get(),
            outboxProcessor = get(),
            deltaReplicator = get(),
            bootstrapCoordinator = get(),
        )
    }

    single<SyncSessionLifecycle> {
        DefaultSyncSessionLifecycle(
            store = get(),
            writeGuard = get(),
            clearLegacyCoreOutbox = { getOrNull<CoreSyncBridge>()?.clearAll() },
        )
    }

    single<SyncCoordinator> {
        StreamifySyncCoordinator(
            lifecycle = get(),
            store = get(),
            planeA = get(),
            playbackPipeline = get(),
            searchCoordinator = get(),
            presenceClient = get(),
            deviceRegistrar = get(),
            catalogHydrator = get(),
            coreBridge = getOrNull(),
            rpcExecutor = get(),
            onRevokedTeardown = { SupabaseClient.clearSession() },
        )
    }

    single { SupabaseWrappedSync }
}
