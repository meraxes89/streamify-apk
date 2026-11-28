---
id: S11
title: Sync engine
tier: client
status: active
owner: sync
sources: [streamify-modules/supabase/]
tests: [streamify-modules/supabase/src/commonTest/]
---

# S11 — Sync engine

> Part of the [subsystem index](../SYSTEMS.md). Documentation rules: [../README.md](../README.md).

## Owns

- Client-side LWW sync, outbox, push/pull RPC calls, auth refresh coordination and device-fleet/Wrapped uploads.

## Does not own

- Local storage (S10), server contract (S21), database schema (S22).

## Current truth

- Hybrid logical clocks with deterministic tiebreak; server contract v4 is the governing decision.
- `StreamifySyncEngine` gates delta sync on `localCursor > 0L || bootstrapCheckpoint == "DONE"` so an empty server changelog (`cursor = 0`) does not re-trigger cold bootstrap.
- Cold bootstrap flushes pending outbox changes via `syncOutboxPushInternal` before probing the server cursor, and bounds `syncLibraryTracksInternal` local song scans (`MAX_BOOTSTRAP_LIBRARY_SONGS = 2_000`).
- Echo suppression during Room writes relies on `SyncApplyingKey` (`withSyncApplying()`); missing `PlaybackEventEntity` rows for `SyncKind.HISTORY` outbox entries fall back to outbox metadata rather than being rejected.
- `applyDeltaOp` returns `Boolean` and `syncDeltaInternal` holds the pull cursor back on any failed op so unapplied inbound mutations are retried; failed delta pulls/pushes surface `SyncStatus.Error` instead of `SyncStatus.Success`.
- `CancellationException` is rethrown across `StreamifySyncEngine` and all `SupabaseLibrarySync` RPC wrappers; live `syncPull`/`syncPush` HTTP `Date` headers feed `HybridLogicalClock.updateServerTime`.
- `CloudUserLike.user_id` defaults to `""` and `fetchCloudLikesWithTimeResult` / `fetchCloudLikedTracksResult` surface transport/decode errors instead of returning empty cloud likes during `LIKES` bootstrap.
- `StreamifySyncEngine.resolveOrPersistPlaylistSyncId` persists generated UUID `syncId`s to `LocalPlaylistEntity` before cloud push; `core.patch` records `SyncKind.LIBRARY_TRACK` in `LocalDataSource.updateSongInLibrary`; `SupabaseClient.signInWithAccessToken` stores accompanying OAuth `refreshToken`s across all platforms.
- `SynchronizedLock.ios.kt` uses `NSRecursiveLock` (replacing the prior no-op) and `HybridLogicalClock.updateServerTime` runs under the same lock; `StreamifySyncEngine.getOrCreateDeviceId` is serialized with `deviceIdMutex`; `androidApp/proguard-rules.pro` keeps `com.maxrave.data.librarysync.SyncApplying.INSTANCE` for release builds.
- `SyncDeadLetterStore` quarantines rejected or terminal-4xx outbox `changeId`s without calling `SyncDao.markSent(ids, -1L)`, preventing poison ops from stalling the upload queue; `StreamifySyncEngine.onAuthenticatedUser` and `resetForAccountSwitch` isolate cursors, checkpoints, device IDs, HLC state, and dead-letter state across logout/user switches.
- `syncDeltaInternal` compares pending local ops against inbound ops using `HybridLogicalClock.HlcKey(hlc, deviceId)` after normalizing plain epoch-ms `changedAt` values via `normalizeToHlc`. `SupabaseWrappedSync` is currently a local no-op stub (no HTTP upload endpoint is wired yet).
- `SupabaseLibrarySync.fetchTracksByIds` batch-hydrates `CloudTrack` metadata from the `tracks` view (`track_index`) for `syncLibraryTracksInternal`, `syncLikesInternal`, and `syncDeltaInternal` (`like`, `library_track`, `playlist_track` ops), avoiding `"Synced Track"` / null-thumbnail placeholders, upgrading existing missing thumbnails/durations via `SongRepository`, normalizing `http://` artwork URLs to `https://` with `hqdefault.jpg` fallback, and filtering `"Synced Track"` placeholders from `uploadLikedTracksBatch` `tracks` upserts.
- `syncOutboxPushInternal` and `buildHistoryOutboxOp` normalize `SyncChangeEntity.changedAt` via `normalizeToHlc` before sending to `sync_push` (while `StreamifySyncEngine` stamps new outbox rows with monotonic epoch-ms via `nextOutboxMillis` so `SyncDao.pruneSent` operates on a single clock domain).
- `SupabaseLibrarySync` bootstrap/telemetry REST and RPC helpers use `getWithAuthRetry` / `postWithAuthRetry` (`ensureValidAccessToken` + 401 refresh retry) and return `Result` variants so failed bootstrap stages halt `streamify_bootstrap_checkpoint_v3` instead of advancing past unhydrated cloud data.
- `playlist_track` delta sync preserves `(playlist_id, track_id)` in `tg_bump_changelog` delete payloads (`2026_12_p11_s11_p0_changelog_playlist_track_delete.sql`), resolves composite `plId|trackId` keys in `resolvePlaylistTrackIds`, and applies `position` updates via `LocalPlaylistRepository.changePositionOfSongInPlaylist`.
- `partitionEchoedChanges` suppresses cycle-local Room/DataStore echoes when `withSyncApplying()` is unavailable (iOS/Wasm); `WasmFakeSyncOutboxRepository` delegates playlist/order/playback lookups to `LocalPlaylistRepository` and `AnalyticsRepository`; `resolveBootstrapHeadCursor` paginates to the true changelog head if `head_cursor` is absent; `syncDeltaInternal` schedules a continuation instead of entering error backoff on >20-page backlogs; `CURSOR_EXPIRED` enables `streamify_cloud_wins_reconcile_v1` to avoid resurrecting >90-day GC'd tombstones; and `2026_12_p12_s11_p1_server_hardening.sql` adds `head_cursor` to `sync_pull`, `streamify.skip_changelog` to `tg_bump_changelog`, and LWW/`'D'`/`safe_sync_uuid` support to `sync_push` `history`.
- `HybridLogicalClock.restore` persists/restores the HLC floor (`streamify_last_hlc_v1`) across restarts and clamps both forward and backward clock skew (`±MAX_SKEW_MS`); `markRemoteSettingWrite` / `shouldSuppressRemoteSettingEcho` prevents async DataStore collector races; `SupabaseLibrarySync.uploadLikedTracksBatch` routes through `sync_push` with `catalog` + `meta` payloads; and `_syncMetrics.pendingOutboxCount` refreshes from `getPendingCount()` with a 5,000-entry `syncDeltaInternal` pending-key window.
- **Plane A / v5 foundation (`com.streamify.sync.v5` & `com.streamify.supabase.v5`):** Added `LongAsStringSerializer` (64-bit JSON string safety for WasmJS), `SupabaseRpcExecutor` (single-flight 401 refresh, clock-skew sampling, `DEVICE_REVOKED` broadcast), `PlaneAStateSyncClient` (`sync_push_v5`, `sync_pull_v5`, `sync_bootstrap_v5`), `FractionalIndex` (Base-62 jittered ordering), reflection-free `SyncWriteGuard` (compile-time `SyncApplying` on Android/JVM + `StreamifyRemoteApplyContext`), `SyncV5Store` (indexed `DEAD_LETTER` isolation), `DefaultSyncSessionLifecycle`, and Plane A coordinators (`PlaneAStateSyncCoordinator`, `SyncOutboxProcessor`, `DeltaReplicator`, `BootstrapCoordinator`).
- **Plane B (History, Search & Wrapped):** Added `PlaneBEventClient` (9 RPCs: `ingest_playback_events`, `fetch_listening_history`, `upsert_search_query`, `delete_search_query`, `clear_search_history`, `fetch_search_history`, `fetch_wrapped_snapshots`, `upsert_wrapped_snapshot`, `compute_wrapped_snapshot`), wired `SupabaseWrappedSync` to `PlaneBEventClient` (removing the dead `user_playback_events` stub), and added `PlaybackEventPipeline` (checkpoint coalescing by `clientEventId`), `SearchHistoryCoordinator` (`clear_epoch` + tombstone reconciliation), and `WrappedSyncCoordinator`.
- **Plane C (Presence, Device Fleet & Active Users):** Added `PlaneCPresenceClient` (6 RPCs: `heartbeat_presence_v2`, `stop_presence`, `get_live_presence`, `record_device_session`, `revoke_device_session`, `get_my_devices`), `DeviceSessionRegistrar` (6-hour throttle + version-change refresh), `PresenceLeaseClient` (45s/70s TTL leases + dead-reckoning), and `SessionSecurityGuard` (observes `SupabaseRpcExecutor.deviceRevoked`).
- **Plane D (Catalog & Hydration):** Added `PlaneDCatalogClient` (`get_tracks_batch`, `ingest_track_hints`, `CatalogTrackDto`) and `CatalogHydrationCoordinator` (echo-free monotonic local catalog merge, 30-minute throttled `get_tracks_batch` repair sweeps, and `HintDebounceBuffer` for tracks with `catalogAuthority < 50`).

## Interfaces

- Local change → outbox → batched push RPC → cursor pull → local apply.

## Decisions and specs

- Spec: [07_database_storage.md](../architecture/engines/07_database_storage.md)
- Decision: [ADR-006-unified-hybrid-sync-v2](../adr/ADR-006-unified-hybrid-sync-v2.md)
- Decision: [ADR-007-user-graph-alignment-and-cross-device-continuity](../adr/ADR-007-user-graph-alignment-and-cross-device-continuity.md)
- Decision: [ADR-008-server-contract-v4-and-sync-dataplane-hardening](../adr/ADR-008-server-contract-v4-and-sync-dataplane-hardening.md)
- Decision: [ADR-011-sync-v5-six-plane-architecture](../adr/ADR-011-sync-v5-six-plane-architecture.md)

## Known limits / debt

- Earlier audits found full-set diff egress and cursor-corruption defects; ADR-008 is the remediation record, now superseded by ADR-011 (Sync v5).
- Legacy `StreamifySyncEngine` continues operating via server-side v4 shims while Planes B–F and caller cutover complete.

## Refinement backlog

- Keep the S11 page in lockstep with any RPC contract change.

<!-- doc-verified-against: 3fce4cd9c7268bb7e8dd1113d47e9635d9e81e68 -->
