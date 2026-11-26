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

## Current truth additions (RFC-003, #355)

- The shared Compose UI now derives navigation transitions per destination from the `ScreenPolicyRegistry` (`composeApp/.../ui/navigation/ScreenPolicy.kt`), and shell chrome (bottom bar / rail / mini player) reactively from `currentBackStackEntryAsState()` — the imperative `hideNavBar`/`showNavBar` callbacks are gone. All of it is behind the `enableSpatialNavigation` beta toggle (Settings → Appearance), off by default; the single `NavHost` tree is never swapped when the flag flips, so the back stack survives toggling.
- Predictive back runs through `PlatformPredictiveBackHandler` (expect/actual in `composeApp`), with overlays settling linearly on release; hero morphing (`Modifier.sharedHero`) is gated for Tier 4 software rasterizers and reduced motion.

## Known limits / debt

- Manifest hygiene has prior audit findings (audit doc `docs/nano/PHASE_1_AUDIO_DSP_AUDIT.md`, historical).

## Refinement backlog

- Re-verify manifest permissions against current targetSdk.

<!-- doc-verified-against: dd58e0b3b60e16bed71200d58c9b204431562349 -->
