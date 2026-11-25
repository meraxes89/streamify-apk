# ADR-003: Hybrid SQLite WAL + Supabase sync

* Status: Accepted
* Date: 2026-11-22
* Verified against: 8b858b1c (staleness protocol in docs/adr/README.md)

## Context

The library must work offline (playback, likes, playlist edits on a plane)
and reconcile across devices when connectivity returns. A server-authoritative
design breaks the first requirement; a pure local store breaks the second.
Schemas evolve on both sides, so the mechanism must tolerate schema drift and
echo (a cloud write re-entering the local store and re-uploading).

## Decision

Local-first hybrid with Room SQLite as the single source of truth and
Supabase Postgres as an async durable ledger:

- **Local store.** KMP Room (entities/DAOs in the `core` submodule,
  `BundledSQLiteDriver`, WAL mode; `core.patch` adds index callbacks on
  song/album/playlist/artist/playback_event). The UI never blocks on network.
- **Sync engine.** `StreamifySyncEngine` (composeApp,
  `com/streamify/sync/StreamifySyncEngine.kt`) syncs liked songs, followed
  artists, and playlists in stages, in bounded batched transactions, with
  echo suppression via an `isSyncActive` re-entrancy gate and deterministic
  timestamp last-write-wins. A newer local un-like deletes the cloud record
  rather than resurrecting it locally.
- **Server schema.** `supabase/schema.sql` plus ordered migrations; RLS on
  every table (owner-scoped writes, admin-guarded privileges, rate-limited
  track ingest at 60 tracks/user/hour); `supabase_realtime` publication for
  `listening_sessions` and `jam_ops` with `pg_notify` fan-out; jam host
  succession uses a lease + fencing token (`host_epoch`), not timestamps.
- **Monotonic aggregates.** Counters (listening seconds, total plays) use
  `GREATEST()`-based upserts (`migrations/2026_01_stats_overhaul.sql`) so
  absolute values can never regress, independent of arrival order.

## Alternatives considered

- **Server-authoritative store.** Rejected: playback and library edits must
  work offline.
- **Full CRDT everywhere.** Rejected: CRDTs are adopted only where they pay
  for themselves (jam ops, the 48-byte CRDT wire with BLAKE3 state hash);
  a playlist is small enough that LWW plus tombstones is simpler and
  debuggable.
- **Firebase/Realtime-DB style push-everything.** Rejected: no SQL, no RLS,
  vendor lock-in; Supabase keeps the schema in-tree and reviewable.

## Consequences

**Positive.** Offline-first behavior; one reviewable SQL schema; RLS tested
in CI (`supabase-rls` job on a pgvector/pg16 container with an 11-assertion
pinned suite); echo suppression prevents feedback loops.

**Negative.** Timestamp LWW depends on roughly synchronized clocks and loses
concurrent edits to the later write; the batch transaction design means a
failed batch rolls back whole (no partial progress); pre-SEC-10 RLS policies
on some telemetry tables still contain an `OR TRUE` clause and are tracked
for hardening; schema and client changes must land in the same PR (enforced
by convention in supabase/README.md).

## Compliance

RLS suite in CI; `docs/playbooks/BACKEND_DB_SYNC.md` is the reconciliation
runbook; `supabase/HYBRID_SYNC_ARCHITECTURE.md` records the pillar design
(local-first, atomic batches, deterministic LWW, echo suppression) — note its
"outbox" phrasing describes engine behavior, not a separate persistent queue
artifact.

---
<!-- doc-verified-against: dd58e0b3b60e16bed71200d58c9b204431562349 -->
