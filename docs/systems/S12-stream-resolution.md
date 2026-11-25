---
id: S12
title: Stream resolution
tier: feature
status: active
owner: resolver
sources: [core/service/kotlinYtmusicScraper/, server/src/slices/stream/]
tests: [core/service/kotlinYtmusicScraper/src/commonTest/]
---

# S12 — Stream resolution

> Part of the [subsystem index](../SYSTEMS.md). Documentation rules: [../README.md](../README.md).

## Owns

- YouTube Music extraction tiers, cipher solving, SABR/progressive stream handling and the range gateway client.

## Does not own

- Catalog metadata (S13), playback (S06).

## Current truth

- Client and server share the resolver contract; server owns the gateway and cache.

## Interfaces

- Query → resolver tier → signed stream URL → S06 data source.

## Decisions and specs

- Spec: [01_stream_resolution.md](../architecture/engines/01_stream_resolution.md)

## Known limits / debt

- Retry policy has no backoff/jitter historically; upstream YouTube churn is constant.

## Refinement backlog

- Add one shared retry schedule and document tier fallback order.

<!-- doc-verified-against: ee2590133b8ccf610640278dca3b3374139573b4 -->
