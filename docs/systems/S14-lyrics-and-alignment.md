---
id: S14
title: Lyrics & alignment
tier: feature
status: active
owner: lyrics
sources: [core/service/lyricsService/, streamify-modules/lyrics-slyr/, server/src/slices/lyrics/]
tests: []
---

# S14 — Lyrics & alignment

> Part of the [subsystem index](../SYSTEMS.md). Documentation rules: [../README.md](../README.md).

## Owns

- LRC fetching, bytecode compilation/playback, clock synchronization and Bluetooth latency offset.

## Does not own

- Playback position from S06/S07.

## Current truth

- `SlyrClock` and `LatencyModel` are shared across Android/desktop; the active-route latency model is only partly wired.

## Interfaces

- Track position → SlyrClock → active line → Compose lyrics canvas.

## Decisions and specs

- Spec: [03_lyrics_alignment.md](../architecture/engines/03_lyrics_alignment.md)

## Known limits / debt

- Latency constants are presence-based rather than route-based in the current tracker.

## Refinement backlog

- Use the API 33+ active-route API and codec-aware constants.

<!-- doc-verified-against: 1961dc73cadbe7a283e12416cef86651ea21f034 -->
