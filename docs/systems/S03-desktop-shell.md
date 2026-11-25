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

## Known limits / debt

- Desktop device hotplug/sleep handling has known gaps in the Phase-1 audit (historical).

## Refinement backlog

- Add a single runbook for installer troubleshooting.

<!-- doc-verified-against: 1d387b7ed4d5f3d4cb13f5f2681eb2bcbf1bb05e -->
