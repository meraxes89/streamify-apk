---
id: S05
title: iOS framework target
tier: shell
status: active
owner: platform-ios
sources: [composeApp/src/iosMain]
tests: []
---

# S05 — iOS framework target

> Part of the [subsystem index](../SYSTEMS.md). Documentation rules: [../README.md](../README.md).

## Owns

- iOS framework binary configuration (`iosArm64`, `iosSimulatorArm64`) and platform actuals.

## Does not own

- Apple audio session implementation; DSP (S08) is Android/desktop only.

## Current truth

- Framework is produced from composeApp; no separate iOS app shell lives in this repo.

## Interfaces

- Framework → host app → platform APIs.

## Decisions and specs

- Spec: [MULTIPLATFORM_PLAN_27_10.MD](../architecture/MULTIPLATFORM_PLAN_27_10.MD)

## Known limits / debt

- Several iOS capability stubs report hardcoded capability values (see Phase-1 audit).

## Refinement backlog

- Document the real iOS capability surface once an app shell exists.

<!-- doc-verified-against: 1961dc73cadbe7a283e12416cef86651ea21f034 -->
