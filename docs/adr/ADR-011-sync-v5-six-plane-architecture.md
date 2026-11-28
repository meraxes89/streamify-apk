# ADR-011: Sync v5 Six-Plane Architecture (S11 / S22 / S23)

* Status: Proposed (Supersedes ADR-008 / Server Contract v4)
* Date: 2026-11-28
* Verified against: 5090ce2996675e6373da0f9fe2e6bf97240a7ecd

## Context

Server Contract v4 (`ADR-008`) routed four fundamentally different workloads—mutable user library state, append-only playback/search telemetry, ephemeral playback presence, and shared catalog metadata—through a single trigger-driven `sync_changelog` pipe and a 2,698-line global client singleton (`StreamifySyncEngine`). Live database audits on `dbgtvzgwixerhnwvhtil` exposed:

1. **Changelog pollution & trigger fragility:** Playback history and search events accounted for ~43% of `sync_changelog` rows, while `tg_bump_changelog` wiped payloads on delete and failed on tables lacking `user_id` (`playlist_tracks`).
2. **Dual clock domains & WasmJS precision loss:** Client outbox rows mixed `nextOutboxMillis` with packed 64-bit HLCs (`(physical_ms << 22) | counter`), and raw JSON numbers > $2^{53}-1$ truncated in WasmJS.
3. **Stale presence & unbounded fleet views:** `user_presence` lacked a hard TTL lease (leaving ghost `is_playing = true` rows), and `admin_fleet_overview` counted all lifetime non-revoked devices without time windows.
4. **Catalog clobbering:** Untrusted client writes to `track_index` left 75/97 rows with `duration_sec = 0` or `NULL`.
5. **Migration drift:** Live DB stopped at `2026_11_p8`, while 5 unapplied `2026_12_*` patches accumulated in `supabase/migrations/`.

## Decision

Decompose S11/S22 synchronization into six strictly decoupled planes backed by a machine-checked contract manifest ([sync-contract-v5.json](../contracts/sync-contract-v5.json)):

1. **Plane A — Authoritative Library & Playlist State (Delta Sync v5):** Zero triggers; single `SECURITY DEFINER` write authority (`sync_push_v5`); per-user contiguous `rev` via `user_sync_state ... FOR UPDATE`; exact-once `mutation_id` idempotency (`sync_mutations_applied`); 64-bit HLC serialized as base-10 strings in JSON with `+300,000 ms` skew guard; Base-62 jittered fractional indexing (`sort_key COLLATE "C"`) for `playlist_tracks`; watermark-safe 90-day GC (`sync_gc_v5`) and `CURSOR_EXPIRED` paginated bootstrap (`sync_bootstrap_v5`).
2. **Plane B — Append-Only Event Ingestion (History, Search & Wrapped):** Monthly range-partitioned `user_listening_history`, 30-day `playback_session_checkpoints` ledger with monotonic non-inflating `profiles` deltas, durable `user_listening_daily` rollups, `clear_epoch`-converged `user_search_history`, and real `user_wrapped_snapshots` storage.
3. **Plane C — Real-Time Presence, Device Fleet & Active Users:** Hard-TTL leased `user_presence` (`v_live_presence`), separate durable `user_playback_handoff`, 2-minute MVCC-coalesced `_plane_c_touch_activity`, cross-plane `_plane_c_is_device_revoked` lockout, and windowed `admin_fleet_overview` / `admin_active_users_overview` views locked to `service_role`.
4. **Plane D — Authoritative Catalog & Metadata Hydration:** Trust-tiered `track_index` (`0` stub $\to$ `10` client hint $\to$ `50` stream-inferred $\to$ `100` server-verified $\to$ `200` admin), decoupled `catalog_enrichment_queue` (`SKIP LOCKED`), `get_tracks_batch` version-aware hydration, `ingest_track_hints`, and single-round-trip `p_include_catalog` sidecars on Plane A pulls.
5. **Plane E — Client Engine Decomposition, KMP Parity & Governance:** Decompose `StreamifySyncEngine.kt` and `SupabaseLibrarySync.kt` into plane-isolated Koin coordinators over `SupabaseRpcExecutor` (single-flight `401` refresh, HTTP `Date` skew check, `DEVICE_REVOKED` bus), unified `SyncV5Store` (Room `streamify_sync_v5.db` on Android/JVM/iOS; IndexedDB + `navigator.locks` on WasmJS), reflection-free `SyncWriteGuard` (`SyncApplying` + `StreamifyRemoteApplyContext`), and `CoreSyncBridge` keeping `core/` untouched.
6. **Plane F — Companion Read-Models, Admin Analytics & Local Peer-Sync Bridge:** Strictly additive layer over Planes A–E providing `user_handoff_queue_snapshot` (`update_active_session_v5` / `fetch_active_session_v5`), `is_admin()`-gated analytics RPCs, $k$-anonymous (`>= 3` listeners) `mv_shelf_community_hits` / `mv_shelf_hourly_community_tracks`, and `PeerSyncBridge` for LAN/QR `LibrarySync` and `LoginSync` (excluding `SupabaseFriendSync`).

## Consequences

- **Positive:** Eliminates changelog bloat, commit-order sequence holes, WasmJS 53-bit HLC truncation, reflection/ProGuard fragility, and stale presence/fleet telemetry.
- **Negative:** Requires phased v4 $\to$ v5 migration with temporary server-side v4 shims during mobile rollout, plus maintaining `sync-contract-v5.json` parity.
- **Follow-up:** Execute Planes A–F migrations (14-digit UTC prefixes `YYYYMMDDHHMMSS_*.sql`), client decomposition PRs, SQL contract tests, and S11/S22 doc updates.

## Compliance

- Enforced by `.github/workflows/sync-governance-s23.yml`:
  1. Valid JSON & RPC manifest checks on `docs/contracts/sync-contract-v5.json`.
  2. Strict 14-digit UTC prefix enforcement for new `supabase/migrations/*.sql` files.
  3. Static guardrail blocking `Class.forName` inside `sync/` and dirty `core/` submodule diffs.

---
<!-- doc-verified-against: 5090ce2996675e6373da0f9fe2e6bf97240a7ecd -->
