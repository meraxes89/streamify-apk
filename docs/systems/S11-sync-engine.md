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

## Interfaces

- Local change → outbox → batched push RPC → cursor pull → local apply.

## Decisions and specs

- Spec: [07_database_storage.md](../architecture/engines/07_database_storage.md)
- Decision: [ADR-006-unified-hybrid-sync-v2](../adr/ADR-006-unified-hybrid-sync-v2.md)
- Decision: [ADR-007-user-graph-alignment-and-cross-device-continuity](../adr/ADR-007-user-graph-alignment-and-cross-device-continuity.md)
- Decision: [ADR-008-server-contract-v4-and-sync-dataplane-hardening](../adr/ADR-008-server-contract-v4-and-sync-dataplane-hardening.md)

## Known limits / debt

- Earlier audits found full-set diff egress and cursor-corruption defects; ADR-008 is the remediation record.
- Legacy direct-REST helpers in `SupabaseLibrarySync` now route through `sync_push` with HLC timestamps (`2026_12_p10_hlc_profiles_history_search.sql`).
- Server-side migrations `2026_12_p11_s11_p0_changelog_playlist_track_delete.sql` and `2026_12_p12_s11_p1_server_hardening.sql` are committed in `supabase/migrations/` and await application to the live Supabase project (`supabase_migrations.schema_migrations` currently stops at `2026_11_p8_v4_server_contract_and_lww_hardening`).

## Refinement backlog

- Keep the S11 page in lockstep with any RPC contract change.

<!-- doc-verified-against: ddd5c30fe4e714c1b704ee24d47d9c6fd01df991 -->
