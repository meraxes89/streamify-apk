package com.streamify.sync.v5.di

import com.streamify.supabase.SupabaseWrappedSync
import com.streamify.supabase.v5.PlaneAStateSyncClient
import com.streamify.supabase.v5.PlaneBEventClient
import com.streamify.supabase.v5.PlaneCPresenceClient
import com.streamify.supabase.v5.PlaneDCatalogClient
import com.streamify.supabase.v5.SupabaseRpcExecutor
import com.streamify.sync.v5.CoreSyncBridge
import com.streamify.sync.v5.DefaultSyncSessionLifecycle
import com.streamify.sync.v5.SettingsSyncObserver
import com.streamify.sync.v5.SyncSessionLifecycle
import com.streamify.sync.v5.SyncV5Store
import com.streamify.sync.v5.SyncWriteGuard
import com.streamify.sync.v5.createPlatformSyncV5Store
import com.streamify.sync.v5.createPlatformSyncWriteGuard
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
import org.koin.dsl.module
import kotlin.time.Clock

/**
 * Constructor-injected Koin DI module for Sync v5 (Planes A–E).
 * Keeps zero global `KoinComponent` singletons in the sync layer.
 */
val syncV5Module = module {
    single<SyncWriteGuard> { createPlatformSyncWriteGuard() }
    single<SyncV5Store> { createPlatformSyncV5Store() }

    single<SupabaseRpcExecutor> { SupabaseRpcExecutor.default() }
    single { PlaneAStateSyncClient(get()) }
    single { PlaneBEventClient(get()) }
    single { PlaneCPresenceClient(get()) }
    single { PlaneDCatalogClient(get()) }

    single<PlaybackEventStorage> { InMemoryPlaybackEventStorage() }
    single<SearchHistoryStorage> { InMemorySearchHistoryStorage() }
    single<SongCatalogStore> { InMemorySongCatalogStore() }

    single { PlaybackEventPipeline(get(), get()) { Clock.System.now().toEpochMilliseconds() } }
    single { SearchHistoryCoordinator(get(), get()) }
    single { WrappedSyncCoordinator(get()) }
    single { PresenceLeaseClient(get()) { Clock.System.now().toEpochMilliseconds() } }
    single { DeviceSessionRegistrar(get()) { Clock.System.now().toEpochMilliseconds() } }
    single { CatalogHydrationCoordinator(get(), get()) { Clock.System.now().toEpochMilliseconds() } }

    single { SettingsSyncObserver(get()) { "kmp_device" } }
    single<SyncSessionLifecycle> {
        DefaultSyncSessionLifecycle(
            store = get(),
            writeGuard = get(),
            clearLegacyCoreOutbox = { getOrNull<CoreSyncBridge>()?.clearAll() },
        )
    }
    single { SupabaseWrappedSync }
}
