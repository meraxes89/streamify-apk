---
id: S04
title: Web/wasm shell
tier: shell
status: active
owner: platform-web
sources: [composeApp/src/wasmJsMain, room-wasm-stub/]
tests: []
---

# S04 — Web/wasm shell

> Part of the [subsystem index](../SYSTEMS.md). Documentation rules: [../README.md](../README.md).

## Owns

- Browser target, wasm platform actuals, and the Room wasm stub used to keep common code compiling.

## Does not own

- Real Room persistence (S10 is Android/JVM); server bundle delivery (S21).

## Current truth

- wasmJs browser target is configured in `composeApp/build.gradle.kts`; local DB is intentionally stubbed.

## Interfaces

- Browser → server bundle gate → Compose wasm UI.

## Decisions and specs

- None recorded.

## Known limits / debt

- Audio DSP is not implemented in the browser target.

## Refinement backlog

- State explicitly which features are browser-only no-ops.

<!-- doc-verified-against: 1d387b7ed4d5f3d4cb13f5f2681eb2bcbf1bb05e -->
