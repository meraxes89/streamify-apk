---
id: S09
title: Audio output & HAL
tier: client
status: active
owner: audio-platform
sources: [streamify-modules/media-hal/, cast/, core/media/media3/src/main/java/com/maxrave/media3/exoplayer/AudioOutputRouter.kt]
tests: []
---

# S09 — Audio output & HAL

> Part of the [subsystem index](../SYSTEMS.md). Documentation rules: [../README.md](../README.md).

## Owns

- Device route observation, cast capability detection, spatial/Dolby capability probes and latency reporting.

## Does not own

- DSP signal math (S08), player state (S06).

## Current truth

- Some header claims in this module were historically stronger than the implementation; trust the code, not the docstring.

## Interfaces

- OS route events → output selected by player; capability flags → UI.

## Decisions and specs

- None recorded.

## Known limits / debt

- Bluetooth latency model is only partially wired; capability detection conflates AOSP Spatializer with Dolby Atmos.

## Refinement backlog

- Rewrite module docstrings to match reality and wire the latency model or delete it.

<!-- doc-verified-against: ee2590133b8ccf610640278dca3b3374139573b4 -->
