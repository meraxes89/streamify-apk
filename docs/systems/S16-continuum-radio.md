---
id: S16
title: Continuum radio
tier: feature
status: planned
owner: recommendation
sources: [docs/architecture/engines/04_neural_continuum.md]
tests: []
---

# S16 — Continuum radio

> Part of the [subsystem index](../SYSTEMS.md). Documentation rules: [../README.md](../README.md).

## Owns

- Recommendation/continuum engine specification: embeddings, preference graph and radio sequencing.

## Does not own

- Everything else — no shipped implementation is present in this tree.

## Current truth

- Spec-only subsystem. The former native implementation was part of the retired core.

## Interfaces

- Planned: library signals → embedding → radio queue → S06.

## Decisions and specs

- Spec: [04_neural_continuum.md](../architecture/engines/04_neural_continuum.md)

## Known limits / debt

- No runtime code or tests; do not assume shipped behaviour from the spec.

## Refinement backlog

- Pick an implementation strategy (Kotlin vs native) before promoting to active.

<!-- doc-verified-against: 1961dc73cadbe7a283e12416cef86651ea21f034 -->
