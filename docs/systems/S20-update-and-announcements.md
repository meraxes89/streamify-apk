---
id: S20
title: Update & announcements
tier: feature
status: active
owner: release
sources: [composeApp/src/commonMain/kotlin/com/maxrave/simpmusic/data/update/, composeApp/src/commonMain/kotlin/com/streamify/announcements/, server/src/platform/bundle-gate.mjs]
tests: [composeApp/src/commonTest/kotlin/com/maxrave/simpmusic/data/update/, composeApp/src/commonTest/kotlin/com/streamify/announcements/]
---

# S20 — Update & announcements

> Part of the [subsystem index](../SYSTEMS.md). Documentation rules: [../README.md](../README.md).

## Owns

- Version resolution, signed artifact selection, content-addressed web bundles and governed announcement popups.

## Does not own

- Release pipeline (S23), shell variants (S01/S02).

## Current truth

- Update resolver lives in commonMain with per-platform binary resolvers.

## Interfaces

- Version manifest → resolver → dialog/announcement arbiter → user.

## Decisions and specs

- None recorded.

## Known limits / debt

- Monotonicity/revocation bugs were fixed historically; keep those tests.

## Refinement backlog

- Document the end-to-end release-to-update chain in one runbook.

<!-- doc-verified-against: 1961dc73cadbe7a283e12416cef86651ea21f034 -->
