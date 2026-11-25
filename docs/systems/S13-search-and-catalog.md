---
id: S13
title: Search & catalog
tier: feature
status: active
owner: catalog
sources: [server/src/slices/catalog/, core/service/spotify/]
tests: []
---

# S13 — Search & catalog

> Part of the [subsystem index](../SYSTEMS.md). Documentation rules: [../README.md](../README.md).

## Owns

- Search, charts, playlists, metadata mapping and the catalog-facing server slice.

## Does not own

- Stream URLs (S12), library state (S10/S11).

## Current truth

- `server/ARCHITECTURE_PLAN.md` makes catalog a vertical slice of the monolith, not a separate service.

## Interfaces

- UI query → catalog slice → mapped models → library/queue.

## Decisions and specs

- Spec: [09_search_graph.md](../architecture/engines/09_search_graph.md)

## Known limits / debt

- Catalog purges historically cascaded into user libraries; see the backend RFC.

## Refinement backlog

- Track the V4 catalog/user-graph decoupling from RFC-002.

<!-- doc-verified-against: 1d387b7ed4d5f3d4cb13f5f2681eb2bcbf1bb05e -->
