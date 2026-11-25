# ADR-008: Server Contract v4 and Sync Data-Plane Hardening

* Status: Accepted
* Date: 2026-11-25
* Supersedes: Partial sync specifications in ADR-005 (v2) and ADR-007
* Verified against: d6a48d617334f75289889d833ad0f5d0df058897 (staleness protocol in docs/adr/README.md)

## Context

Phase 2 audit (`docs/nano/PHASE_2_SYNC_DATAPLANE_AUDIT.md`) uncovered four critical structural vulnerabilities in the Supabase PostgreSQL backend (`dbgtvzgwixerhnwvhtil`) driving the universal sync protocol:

1. **Push Cursor Corruption & Remote Change Skip (P0-2):**
   The `sync_push` RPC previously returned the account-wide maximum changelog ID (`SELECT max(id) FROM sync_changelog WHERE user_id = v_uid`). When a client received this response and advanced its local pull cursor, it skipped any changelog rows authored concurrently by other devices between the client's last pull and push. Because the sequence never backtracks, those skipped rows were lost permanently to that device.

2. **Non-Deterministic Last-Write-Wins (LWW) Conflicts (P0-8):**
   Conflict resolution guards in `sync_push` evaluated bare bigint inequalities:
   ```sql
   WHERE modified_hlc IS NULL OR modified_hlc <= v_hlc
   ```
   When two devices emitted changes during the same physical millisecond or logical tick, the second write to hit PostgreSQL unconditionally overwrote the first, regardless of device identity. This produced divergent local states that never converged across nodes.

3. **Playlist Track Unique Position Collisions (P0-5):**
   `playlist_tracks` enforced a non-partial unique constraint on `(playlist_id, position) WHERE deleted_at IS NULL` while `sync_push` generated random UUIDs (`gen_random_uuid()`) for new track inserts. Concurrent track insertions or position reorderings produced unique violation exceptions, incremented `v_rejected`, and dropped user track modifications silently.

4. **Missing Cursor Horizon Expiry Guard:**
   `pg_cron` job `#1` purges changelog entries older than 90 days. If a dormant device came online after 90 days with `p_since > 0`, `sync_pull` returned an empty set, misleading the client into assuming it was fully synchronized while missing all pruned changes.

5. **Untracked Migration History:**
   The production database lacked `supabase_migrations.schema_migrations`, leading to drift between local migration files and live database state.

---

## Decision

We establish **Server Contract v4** and execute migration `2026_11_p8_v4_server_contract_and_lww_hardening.sql` on the live PostgreSQL 17.6 database:

### 1. Push Never Advances the Pull Cursor (Rule 1)
- `sync_push` now returns `own_cursor`:
  ```sql
  'cursor', (SELECT coalesce(max(id), 0) FROM public.sync_changelog
              WHERE user_id = v_uid AND origin = v_device_id)
  ```
- This cursor reflects strictly the changes originated by the calling device (`v_device_id`). The client pull cursor (`streamify_sync_cursor_v3`) must only be advanced by responses from `sync_pull`.

### 2. Structured Per-Op Status Telemetry (Rule 2)
- `sync_push` returns a structured results array alongside batch counters:
  ```json
  {
    "applied": 10,
    "rejected": 0,
    "cursor": 1042,
    "results": [
      { "key": "track_1", "entity": "like", "status": "applied" },
      { "key": "track_2", "entity": "like", "status": "stale" }
    ]
  }
  ```
- Status classifications:
  - `applied`: Mutation committed to database and changelog.
  - `stale`: Op lost LWW evaluation against a newer write; acknowledged and not retried.
  - `rejected`: Constraint or cast failure; includes error message and stops local outbox acknowledgement.

### 3. Deterministic LWW with Two-Part Ordering Key (Rule 3)
- Added `modified_by TEXT NOT NULL DEFAULT ''` to all synced tables:
  - `user_likes`, `user_library_tracks`, `user_podcast_episode_state`, `user_settings`, `playlist_tracks`, `playlists`, `user_item_favorites`.
- Every conflict guard in `sync_push` now evaluates strictly greater tuple ordering:
  ```sql
  WHERE (table.modified_hlc IS NULL)
     OR (table.modified_hlc, table.modified_by) < (v_hlc, v_device_id)
  ```
- Ties are broken deterministically by device ID comparison. Idempotent re-deliveries (`equal key`) no-op safely.

### 4. Natural-Key Conflict Target on Playlist Tracks (Rule 4)
- Dropped `uq_playlist_position` and non-partial index `uq_playlist_track`.
- Enforced active partial unique index:
  ```sql
  CREATE UNIQUE INDEX uq_playlist_track_active
    ON public.playlist_tracks (playlist_id, track_id)
    WHERE deleted_at IS NULL;
  ```
- Track upsert targets `ON CONFLICT (playlist_id, track_id) WHERE deleted_at IS NULL DO UPDATE SET position = excluded.position, modified_hlc = ..., modified_by = ...`. Concurrent reorderings update position without unique constraint violations.

### 5. Cursor Horizon Expiration Check (Rule 5)
- In `sync_pull`, if `p_since > 0` and `p_since < (min(id) - 1)` from `public.sync_changelog`, the RPC raises `'CURSOR_EXPIRED'`.
- Clients detect this error and trigger a clean full-bootstrap reconciliation rather than accepting a corrupted delta stream.

### 6. Formal Migration Tracking
- Initialized `supabase_migrations.schema_migrations` on production and recorded all 13 canonical migrations.

---

## Consequences

### Positive
- **Zero Silent Data Loss:** Devices never skip foreign operations due to push responses.
- **Deterministic Multi-Device Convergence:** Edits during identical milliseconds resolve identically across Android, Desktop, iOS, and Wasm nodes.
- **Robust Playlist Management:** Adding, deleting, and re-adding tracks, as well as reordering playlists, succeeds idempotently under concurrency.
- **Observability:** Client outbox drain can differentiate between applied, stale, and rejected operations.

### Negative / Trade-offs
- Added `modified_by` column slightly increases row storage (text pointer in Postgres heap, negligible for our 14 MB footprint).
- Clients that experience `CURSOR_EXPIRED` (offline > 90 days) will incur a full re-bootstrap, which is the mathematically necessary trade-off for 90-day changelog garbage collection.

---

## Compliance

- Schema state verified directly via Supabase Management API.
- All subsequent database modifications must be submitted as versioned files in `supabase/migrations/` and recorded in `supabase_migrations.schema_migrations`.
- Tested in `supabase/sql/tests/`.

---
<!-- doc-verified-against: d6a48d617334f75289889d833ad0f5d0df058897 -->
