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

<!-- doc-verified-against: 3fce4cd9c7268bb7e8dd1113d47e9635d9e81e68 -->
