---
id: S03
title: Desktop shell
tier: shell
status: active
owner: platform-desktop
sources: [desktopApp/]
tests: []
---

# S03 — Desktop shell

> Part of the [subsystem index](../SYSTEMS.md). Documentation rules: [../README.md](../README.md).

## Owns

- Compose Desktop entry point, Conveyor packaging, JVM memory tuning and installers.

## Does not own

- Playback internals (S07), UI feature code (composeApp).

## Current truth

- Packaged for Windows/macOS/Linux; installers are built by the release workflow.

## Interfaces

- Desktop app → S07 libmpv adapter → OS audio device.

## Decisions and specs

- Spec: [MULTIPLATFORM_PLAN_27_10.MD](../architecture/MULTIPLATFORM_PLAN_27_10.MD)

## Current truth additions (RFC-003, #355)

- The shared Compose UI now derives navigation transitions per destination from the `ScreenPolicyRegistry` (`composeApp/.../ui/navigation/ScreenPolicy.kt`), and shell chrome (bottom bar / rail / mini player) reactively from `currentBackStackEntryAsState()` — the imperative `hideNavBar`/`showNavBar` callbacks are gone. All of it is behind the `enableSpatialNavigation` beta toggle (Settings → Appearance), off by default; the single `NavHost` tree is never swapped when the flag flips, so the back stack survives toggling.
- Predictive back runs through `PlatformPredictiveBackHandler` (expect/actual in `composeApp`), with overlays settling linearly on release; hero morphing (`Modifier.sharedHero`) is gated for Tier 4 software rasterizers and reduced motion.

## Known limits / debt

- Desktop device hotplug/sleep handling has known gaps in the Phase-1 audit (historical).

## Refinement backlog

- Add a single runbook for installer troubleshooting.

<!-- doc-verified-against: dd58e0b3b60e16bed71200d58c9b204431562349 -->
