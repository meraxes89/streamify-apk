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

- Contract v4 and deterministic LWW are governed by ADR-008 (`2026_11_p8_v4_server_contract_and_lww_hardening.sql`); RFC-002 is the target-schema proposal.

## Interfaces

- RPC/realtime → RLS-scoped rows → client sync cursor.

## Decisions and specs

- Spec: [RFC-002-backend-cloud-graph.md](../rfc/RFC-002-backend-cloud-graph.md)
- Decision: [ADR-003-hybrid-sqlite-supabase-sync](../adr/ADR-003-hybrid-sqlite-supabase-sync.md)
- Decision: [ADR-006-unified-hybrid-sync-v2](../adr/ADR-006-unified-hybrid-sync-v2.md)
- Decision: [ADR-008-server-contract-v4-and-sync-dataplane-hardening](../adr/ADR-008-server-contract-v4-and-sync-dataplane-hardening.md)

## Known limits / debt

- Historical audits found RLS exposure and catalog cascade defects; see RFC-002.

## Refinement backlog

- Keep migration numbering and RLS tests in the same PR as RPC changes.
- Phase P10 (`2026_12_p10_hlc_profiles_history_search.sql`) adds `(modified_hlc, modified_by)` LWW to `user_search_history` / `user_listening_history` and enforces monotonic server-`NOW()` updates on `profiles.last_active_at`.

<!-- doc-verified-against: cbf2f9bf8a2f5978a089a58612c5cf9a15ab8d5a -->
