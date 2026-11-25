---
id: S17
title: Ingestion & importers
tier: feature
status: active
owner: ingestion
sources: [core/service/spotify/, core/data/src/commonMain/kotlin/com/maxrave/data/lastfm/, core/service/discord/, core/service/kizzy/, core/service/listenTogether/]
tests: []
---

# S17 — Ingestion & importers

> Part of the [subsystem index](../SYSTEMS.md). Documentation rules: [../README.md](../README.md).

## Owns

- Importing catalogs and listening history from external services into the local library.

## Does not own

- Catalog mapping (S13), auth (S18), sync (S11).

## Current truth

- All importers are Kotlin service modules in the `core` submodule.

## Interfaces

- External API → importer → local DAO/outbox.

## Decisions and specs

- Spec: [08_ingestion_scraper.md](../architecture/engines/08_ingestion_scraper.md)

## Known limits / debt

- Rate-limit and retry behaviour is per-importer.

## Refinement backlog

- Document each importer's credential requirements in one table.

<!-- doc-verified-against: 1961dc73cadbe7a283e12416cef86651ea21f034 -->
