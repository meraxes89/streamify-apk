---
id: S10
title: Local persistence
tier: client
status: active
owner: data
sources: [core/data/]
tests: [core/data/src/jvmTest/]
---

# S10 — Local persistence

> Part of the [subsystem index](../SYSTEMS.md). Documentation rules: [../README.md](../README.md).

## Owns

- Room database, DAOs, migrations, offline-first library/queue/playlist state and generated schemas.

## Does not own

- Remote truth (S22) and conflict policy (S11).

## Current truth

- Local Room state is the source of truth; Supabase is the sync peer.

## Interfaces

- Repository interfaces (core/domain) → Room DAO → local state → S11 outbox.

## Decisions and specs

- Spec: [07_database_storage.md](../architecture/engines/07_database_storage.md)
- Decision: [ADR-003-hybrid-sqlite-supabase-sync](../adr/ADR-003-hybrid-sqlite-supabase-sync.md)

## Known limits / debt

- `runBlocking` DataStore reads on service/main threads were flagged in the Phase-1 audit.

## Refinement backlog

- Replace blocking reads with cached settings snapshots.

<!-- doc-verified-against: 1961dc73cadbe7a283e12416cef86651ea21f034 -->
