# supabase/ — Postgres schema, RLS, and hybrid sync

The server-side half of the hybrid storage design (decision record:
[ADR-003](../docs/adr/ADR-003-hybrid-sqlite-supabase-sync.md); client half in
[composeApp](../composeApp/README.md) and
[streamify-modules/supabase](../streamify-modules/README.md)). Design
commentary: [HYBRID_SYNC_ARCHITECTURE.md](HYBRID_SYNC_ARCHITECTURE.md).

## Files and apply order

Base contract: `schema.sql` (48 KB). Apply order for a fresh database
(fixing an omission in earlier revisions of this README, which skipped the
W1-B migration):

1. `migrations/2025_12_drift_catchup.sql` (drift catch-up, if listed in your
   snapshot)
2. `schema.sql`
3. `migrations/2026_01_stats_overhaul.sql`
4. `migrations/2026_04_sec10_rls_hardening.sql`
5. `sql/jam_rpc.sql`, then `sql/jam_lease.sql`
6. `migrations/2026_11_ws_w_canary_results.sql`
7. `migrations/2026_12_w1b_item_favorites.sql`

Client and schema changes must land in the same PR. Operational runbook:
[docs/playbooks/BACKEND_DB_SYNC.md](../docs/playbooks/BACKEND_DB_SYNC.md).

## Tables (selection)

`profiles` (with admin guard), `tracks` (with `embedding vector(512)` and
HNSW cosine index), `user_likes`, `user_item_favorites` (album/artist),
`playlists`, `playlist_tracks`, `listening_sessions` (jam rooms: 6-char
session code, queue JSONB, lease columns), `track_comments`,
`user_listening_history`, `friend_connections`, `admin_broadcasts`,
telemetry family (`user_listening_patterns`, `user_musical_chronotype`,
`user_device_context`, `track_hook_telemetry`, `track_cooccurrence_graph`,
`track_markov_2nd`), edge compute family, stats (`user_track_plays`,
`user_play_events`), `canary_results` (anon insert-only), and the
append-only jam op journal `jam_ops` (client-monotonic `op_id`, dedupe
unique index per session).

## Row Level Security

RLS is enabled on every table. Policies are owner-scoped by default
("Users manage own likes", "Users manage own playlists", "Hosts can manage
their sessions"), public reads where intended ("Anyone can view tracks"),
admin-guarded elsewhere ("Admin has full track access" via `is_admin()`),
and rate-limited for ingest ("Authenticated users can ingest tracks
(rate-limited)" — 60 tracks/user/hour via `track_ingest_allowed()`).
Triggers: `on_auth_user_created` provisions profiles (never mints admin);
`profiles_is_admin_guard` blocks client writes to `is_admin`.

Known hardening debt: several pre-SEC-10 telemetry policies still contain
`USING (auth.uid() = user_id OR TRUE)` — effectively world-writable despite
their owner-scoped names. The SEC-10 suite pins that listening_sessions,
history, and comments no longer do this; the remaining tables are tracked
follow-up.

## Realtime and conflict resolution

- Realtime publication: `listening_sessions` and `jam_ops` (WAL-backed
  fan-out via `pg_notify`); managed by `sql/jam_lease.sql` and
  `sql/jam_rpc.sql`.
- Conflict resolution is deterministic last-write-wins: timestamps on every
  synced entity (`updated_at`, `created_at`); a newer local un-like deletes
  the cloud record rather than restoring it locally. Counters use
  `GREATEST()`-based upserts (`upsert_user_telemetry()`) so aggregates are
  monotonic regardless of arrival order. Jam host succession is lease-based
  with a fencing token (`host_epoch`, `jam_takeover` serialized with
  `FOR UPDATE`), not timestamp LWW.
- Jam RPCs are `SECURITY DEFINER` with membership guards; admin RPCs are
  gated behind `is_admin()`.

## Tests

`sql/tests/` runs on a throwaway `pgvector/pgvector:pg16` Docker container:
a shim emulates Supabase auth roles, then the schema, grants, and an
11-pinned-assertion RLS suite execute. This is the `supabase-rls` CI job.

## Client counterpart

Current consumers: `streamify-modules/supabase/` (Ktor REST client:
`SupabaseClient`, `SupabaseLibrarySync`, `StreamifyAuthManager`,
`SupabaseAdminClient`, Koin wiring) and
`composeApp/.../com/streamify/sync/StreamifySyncEngine.kt` (staged LWW sync
with echo suppression). The legacy pre-CMP client lives under `legacy/app/`.

---
Verified against commit 8b858b1c. Staleness protocol: docs/adr/README.md.
<!-- doc-verified-against: 8b858b1c89b91f643cd7159180706fbd931f925e -->
