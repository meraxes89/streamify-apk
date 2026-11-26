---
id: S21
title: Server
tier: backend
status: active
owner: backend
sources: [server/src/]
tests: [server/test/]
---

# S21 — Server

> Part of the [subsystem index](../SYSTEMS.md). Documentation rules: [../README.md](../README.md).

## Owns

- Single-process Fastify modular monolith with six vertical slices: auth, catalog, lyrics, room, stream, thumb; stream owns gateway, cache, pacer and workers.

## Does not own

- Separate microservices, Redis and cluster orchestration — explicitly non-goals in `server/ARCHITECTURE_PLAN.md`.

## Current truth

- The architecture plan is authoritative for the monolith decision; RFC-002 is the V4 schema proposal.

## Interfaces

- HTTP → auth plugin → slice service → disk-backed cache / Supabase.

## Decisions and specs

- Spec: [12_server_security.md](../architecture/engines/12_server_security.md)
- Spec: [RFC-002-backend-cloud-graph.md](../rfc/RFC-002-backend-cloud-graph.md)
- Decision: [ADR-008-server-contract-v4-and-sync-dataplane-hardening](../adr/ADR-008-server-contract-v4-and-sync-dataplane-hardening.md)

## Known limits / debt

- Media bytes must not enter the JS heap; zero media buffering in Node.

## Refinement backlog

- Keep the no-microservices rule in the ADR before proposing any split.

<!-- doc-verified-against: dd58e0b3b60e16bed71200d58c9b204431562349 -->
