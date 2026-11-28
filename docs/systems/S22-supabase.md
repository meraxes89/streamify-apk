---
id: S22
title: Supabase
tier: backend
status: active
owner: backend-data
sources: [supabase/]
tests: [supabase/sql/tests/]
---

# S22 — Supabase

> Part of the [subsystem index](../SYSTEMS.md). Documentation rules: [../README.md](../README.md).

## Owns

- Postgres schema, migrations, RLS policies, RPCs, triggers, realtime publications and the device fleet tables.

## Does not own

- Local persistence (S10) and client sync (S11) policies — this layer stores, it does not decide client state.

## Current truth

- Plane A Delta Sync v5 (`20261201000000_v5_plane_a_expand_and_backfill.sql`, `20261201000100_v5_plane_a_rpcs_and_shim.sql`) is deployed on `dbgtvzgwixerhnwvhtil` per [ADR-011](../adr/ADR-011-sync-v5-six-plane-architecture.md), replacing `tg_*_changelog` triggers with `sync_push_v5`, `sync_pull_v5`, `sync_bootstrap_v5`, `sync_gc_v5`, single-policy `FORCE ROW LEVEL SECURITY`, and v4 compatibility shims (`sync_push`/`sync_pull`).
- Plane B Append-Only Event Ingestion (`20261201000200_v5_plane_b_events_and_wrapped.sql`) is deployed on `dbgtvzgwixerhnwvhtil`: monthly range-partitioned `user_listening_history` + 30-day `playback_session_checkpoints` + `user_listening_daily`, `user_search_state` (`clear_epoch`) + `(user_id, query_norm)` `user_search_history`, `user_wrapped_snapshots`, `v_user_listening_daily`/`v_user_graph_summary` views, `tg_history_changelog`/`tg_search_changelog` dropped, daily `pg_cron` job `plane-b-partition-maintenance` (`maintain_plane_b_partitions`), all 10 Plane B RPCs, and v4 `sync_push` shim routing for `history`/`search`.
- Plane C Real-Time Presence & Device Fleet (`20261201000300_v5_plane_c_presence_and_fleet.sql`) is deployed on `dbgtvzgwixerhnwvhtil` per [ADR-011](../adr/ADR-011-sync-v5-six-plane-architecture.md): TTL-leased `user_presence` + `v_live_presence`, durable `user_playback_handoff`, platform-normalized `user_devices`, `user_daily_activity`, `service_role`-only `admin_fleet_overview` / `admin_active_users_overview`, `_plane_c_is_device_revoked` + 2-minute throttled `_plane_c_touch_activity` (`streamify.trusted_activity_touch`), all 6 Plane C RPCs + legacy `heartbeat_presence` bridge, and `pg_cron` maintenance.
- Plane D Authoritative Catalog & Hydration (`20261201000400_v5_plane_d_catalog_and_hydration.sql`) is deployed on `dbgtvzgwixerhnwvhtil`: `artist_index`, `album_index`, trust-tiered `track_index`, `service_role`-only `catalog_enrichment_queue` (`SKIP LOCKED`), read-only catalog RLS + hint-only `tg_tracks_view_upsert()` shim, `get_tracks_batch`, `ingest_track_hints`, S21 worker RPCs (`claim_catalog_enrichment_batch`, `upsert_catalog_authoritative`, `complete_catalog_enrichment_batch`), `sync_pull_v5` catalog sidecar, `tg_plane_d_seed_track`, and `fetch_listening_history` catalog `LEFT JOIN`.
- Plane F Companion Read-Models, Admin Analytics & Shelves (`20261201000500_v5_plane_f_companions_and_shelves.sql`) is deployed on `dbgtvzgwixerhnwvhtil`: epoch-locked `user_handoff_queue_snapshot`, `update_active_session_v5` / `fetch_active_session_v5` (delegating to Plane C), `is_admin()`-gated `get_admin_dashboard_stats` / `get_admin_edge_compute_stats` / `get_admin_top_tracks` / `get_admin_fleet_overview`, $k$-anonymous (`>= 3`) `mv_shelf_community_hits` / `mv_shelf_hourly_community_tracks` + `get_hybrid_home_shelves`, and `pg_cron` refresh jobs.
- S20 Phase 2 Targeted User Announcements (`20261202000000_s20_user_announcements.sql`) is deployed on `dbgtvzgwixerhnwvhtil`: `public.user_announcements` with `FORCE ROW LEVEL SECURITY` (`user_id IS NULL OR user_id = auth.uid()` read, `is_admin` write) and `SECURITY DEFINER` RPC `public.get_targeted_announcements()` for `authenticated`.

## Interfaces

- RPC/realtime → RLS-scoped rows → client sync cursor.

## Decisions and specs

- Spec: [RFC-002-backend-cloud-graph.md](../rfc/RFC-002-backend-cloud-graph.md)
- Decision: [ADR-003-hybrid-sqlite-supabase-sync](../adr/ADR-003-hybrid-sqlite-supabase-sync.md)
- Decision: [ADR-006-unified-hybrid-sync-v2](../adr/ADR-006-unified-hybrid-sync-v2.md)
- Decision: [ADR-008-server-contract-v4-and-sync-dataplane-hardening](../adr/ADR-008-server-contract-v4-and-sync-dataplane-hardening.md)
- Decision: [ADR-011-sync-v5-six-plane-architecture](../adr/ADR-011-sync-v5-six-plane-architecture.md)

## Known limits / debt

- Historical audits found RLS exposure and catalog cascade defects; see RFC-002.
- Planes B, C, D, and F migrations are next in sequence under `ADR-011`.

## Refinement backlog

- Keep migration numbering and RLS tests in the same PR as RPC changes.

<!-- doc-verified-against: 78f89c795f628fd8e550a5a944d1606f4b2edbd0 -->
