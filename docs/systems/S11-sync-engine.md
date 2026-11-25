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

<!-- doc-verified-against: 1961dc73cadbe7a283e12416cef86651ea21f034 -->
