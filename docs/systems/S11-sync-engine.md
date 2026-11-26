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

## Interfaces

- Local change → outbox → batched push RPC → cursor pull → local apply.

## Decisions and specs

- Spec: [07_database_storage.md](../architecture/engines/07_database_storage.md)
- Decision: [ADR-006-unified-hybrid-sync-v2](../adr/ADR-006-unified-hybrid-sync-v2.md)
- Decision: [ADR-007-user-graph-alignment-and-cross-device-continuity](../adr/ADR-007-user-graph-alignment-and-cross-device-continuity.md)
- Decision: [ADR-008-server-contract-v4-and-sync-dataplane-hardening](../adr/ADR-008-server-contract-v4-and-sync-dataplane-hardening.md)

## Known limits / debt

- Earlier audits found full-set diff egress and cursor-corruption defects; ADR-008 is the remediation record.

## Refinement backlog

- Keep the S11 page in lockstep with any RPC contract change.

<!-- doc-verified-against: 19f03b174d83d85682607dd194f1d4ca6acb58f8 -->
