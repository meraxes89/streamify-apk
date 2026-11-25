---
id: S07
title: Desktop playback
tier: client
status: active
owner: playback-desktop
sources: [core/media/media-jvm/]
tests: []
---

# S07 — Desktop playback

> Part of the [subsystem index](../SYSTEMS.md). Documentation rules: [../README.md](../README.md).

## Owns

- libmpv binding through JNA, single-thread player adapter, and the `af` filter graph builder.

## Does not own

- Media3 engine (S06), DSP math (S08 settings are shared but kernels are ffmpeg filters).

## Current truth

- Same stored EQ/echo/reverb settings produce ffmpeg `lavfi` graphs on desktop and Kotlin processors on Android.

## Interfaces

- Adapter → mpv property/commands → libmpv → OS audio output.

## Decisions and specs

- Spec: [11_playback_ui.md](../architecture/engines/11_playback_ui.md)

## Known limits / debt

- Full `af` graph rebuild on EQ slider ticks and seeks; pump-death and hotplug handling have known gaps.

## Refinement backlog

- Use per-band `af-command` updates and add output hotplug observation.

<!-- doc-verified-against: ee2590133b8ccf610640278dca3b3374139573b4 -->
