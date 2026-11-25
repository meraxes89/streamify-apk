---
id: S01
title: Android shell
tier: shell
status: active
owner: platform-android
sources: [androidApp/]
tests: []
---

# S01 — Android shell

> Part of the [subsystem index](../SYSTEMS.md). Documentation rules: [../README.md](../README.md).

## Owns

- Application entry, manifest, foreground service declarations, widgets and release signing for the main phone app.
- Hosts the Compose UI and wires Koin; delegates playback to S06.

## Does not own

- Playback policy (S06), DSP math (S08), sync protocol (S11).

## Current truth

- Vivo variant is a separate shell (S02). `androidApp/build.gradle.kts` declares the packaging excludes; native packaging was removed with the retired engine.
- Shared KMP Compose UI hoists playback timeline collection to leaf composables (`PlayerTimelineLeaf.kt`, `SharedViewModel.currentLyricLineIndex`), provides `LocalAppWindowSizeClass` (`Compact`/`Medium`/`Expanded`) from `App.kt`, and attaches `contentDescription` / `Role.Button` semantics on shared interactive controls.

## Interfaces

- UI → Koin graph → S06/S08/S11; system → service → notifications.

## Decisions and specs

- Spec: [11_playback_ui.md](../architecture/engines/11_playback_ui.md)

## Known limits / debt

- Manifest hygiene has prior audit findings (audit doc `docs/nano/PHASE_1_AUDIO_DSP_AUDIT.md`, historical).

## Refinement backlog

- Re-verify manifest permissions against current targetSdk.

<!-- doc-verified-against: 03d70dac6c9ad016aace5c9688c0690013fb22d8 -->
