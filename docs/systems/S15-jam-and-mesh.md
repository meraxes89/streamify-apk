---
id: S15
title: Jam & mesh
tier: feature
status: active
owner: jam
sources: [streamify-modules/jam/, server/src/slices/room/]
tests: []
---

# S15 — Jam & mesh

> Part of the [subsystem index](../SYSTEMS.md). Documentation rules: [../README.md](../README.md).

## Owns

- Listen-together sessions, CRDT op encoding, PTP/clock sync and LAN broadcast.

## Does not own

- Playback transport (S06), identity (S18).

## Current truth

- Server room slice broadcasts; client coordinator owns session state.

## Interfaces

- Coord → op encoding → server broadcast → peer apply.

## Decisions and specs

- Spec: [06_jam_distributed.md](../architecture/engines/06_jam_distributed.md)
- Spec: [JAM_ENGINE_SPEC.md](../architecture/JAM_ENGINE_SPEC.md)

## Known limits / debt

- Historical duplicate JNI symbols affected op identity; native bridge was removed.

## Refinement backlog

- Pin op encoding with golden vectors on the current Kotlin path.

<!-- doc-verified-against: 1961dc73cadbe7a283e12416cef86651ea21f034 -->
