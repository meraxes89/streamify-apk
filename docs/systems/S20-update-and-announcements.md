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

- Update resolver lives in commonMain with per-platform binary resolvers and resumable `PlatformUpdater` (HTTP `Range` + SHA-256 verification).
- 48-hour `UpdateUrgency` escalation (`SOFT` 0–12h, `ACTIVE_5S` 12–36h, `URGENT_10S` 36–48h, `ENFORCED` 48h+ or `isBreaking`).

## Interfaces

- Version manifest → resolver → dialog/announcement arbiter → user.

## Decisions and specs

- None recorded.

## Known limits / debt

- Monotonicity/revocation bugs were fixed historically; keep those tests.

## Refinement backlog

- Signed `version.json` policy (`scripts/sign_version_json.js` + `UpdateResolver.verifyAndCompare`), `UpdateStateHolder` `StateFlow`, non-blank SHA-256 enforcement, manual `force = true` checks, and the online-streaming capability gate (`SharedViewModel.loadMediaItemFromTrack`, preserving offline downloads) are implemented per [RFC-005](../rfc/RFC-005-signed-release-policy-and-gates.md) (#372).
- Document the end-to-end release-to-update chain in one runbook.

<!-- doc-verified-against: 2ef784d84528c94b98a0412d8cc5101453e15b90 -->
