---
id: S02
title: Vivo store variant
tier: shell
status: active
owner: platform-android
sources: [vivoAndroidApp/]
tests: []
---

# S02 — Vivo store variant

> Part of the [subsystem index](../SYSTEMS.md). Documentation rules: [../README.md](../README.md).

## Owns

- Vivo package identity, store-specific manifest and OAuth callback resolution.

## Does not own

- Main Android behaviour (S01); update resolver policy (S20).

## Current truth

- Exists because the Vivo store requires a distinct package identity; see the ADR for the callback decision.

## Interfaces

- Store build → S20 update resolver → signed artifact.

## Decisions and specs

- Decision: [ADR-005-vivo-package-identity-and-oauth-resolution](../adr/ADR-005-vivo-package-identity-and-oauth-resolution.md)

## Known limits / debt

- Store-specific QA is manual and not in the deep matrix.

## Refinement backlog

- Document the Vivo release checklist in one place.

<!-- doc-verified-against: 1961dc73cadbe7a283e12416cef86651ea21f034 -->
