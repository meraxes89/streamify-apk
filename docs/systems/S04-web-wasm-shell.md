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

## Current truth additions (RFC-003, #355)

- The shared Compose UI now derives navigation transitions per destination from the `ScreenPolicyRegistry` (`composeApp/.../ui/navigation/ScreenPolicy.kt`), and shell chrome (bottom bar / rail / mini player) reactively from `currentBackStackEntryAsState()` — the imperative `hideNavBar`/`showNavBar` callbacks are gone. All of it is behind the `enableSpatialNavigation` beta toggle (Settings → Appearance), off by default; the single `NavHost` tree is never swapped when the flag flips, so the back stack survives toggling.
- Predictive back runs through `PlatformPredictiveBackHandler` (expect/actual in `composeApp`), with overlays settling linearly on release; hero morphing (`Modifier.sharedHero`) is gated for Tier 4 software rasterizers and reduced motion.

## Known limits / debt

- Audio DSP is not implemented in the browser target.

## Refinement backlog

- State explicitly which features are browser-only no-ops.

<!-- doc-verified-against: 7ee638426aa9f0594a5f0e960ca53cb39ab1220f -->
