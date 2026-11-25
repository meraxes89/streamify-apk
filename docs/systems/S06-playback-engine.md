---
id: S06
title: Playback engine
tier: client
status: active
owner: playback
sources: [core/media/media3/, core/data/src/androidMain/kotlin/com/maxrave/data/mediaservice/, core/domain/src/commonMain/kotlin/com/maxrave/domain/mediaservice/]
tests: [core/media/media3/src/test/]
---

# S06 — Playback engine

> Part of the [subsystem index](../SYSTEMS.md). Documentation rules: [../README.md](../README.md).

## Owns

- Media3 player instances, crossfade automaton, focus/ducking, queue orchestration and playback state machine.

## Does not own

- DSP kernels (S08), desktop playback (S07), resolver (S12).

## Current truth

- Android playback runs two players during crossfade; settings are sampled per buffer by processors.

## Interfaces

- Transport/queue commands → adapter → ExoPlayer → AudioProcessor chain → AudioTrack.

## Decisions and specs

- Spec: [13_playback_dataplane.md](../architecture/engines/13_playback_dataplane.md)
- Spec: [11_playback_ui.md](../architecture/engines/11_playback_ui.md)

## Known limits / debt

- Attention/focus interactions were a historical defect class; see the Phase-1 audit.

## Refinement backlog

- Add an attention-matrix test and an explicit limiter stage to the processor chain.

<!-- doc-verified-against: dd58e0b3b60e16bed71200d58c9b204431562349 -->
