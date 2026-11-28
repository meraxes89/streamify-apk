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

## Current truth additions (RFC-003, #355)

- The shared Compose UI now derives navigation transitions per destination from the `ScreenPolicyRegistry` (`composeApp/.../ui/navigation/ScreenPolicy.kt`), and shell chrome (bottom bar / rail / mini player) reactively from `currentBackStackEntryAsState()` — the imperative `hideNavBar`/`showNavBar` callbacks are gone. All of it is behind the `enableSpatialNavigation` beta toggle (Settings → Appearance), off by default; the single `NavHost` tree is never swapped when the flag flips, so the back stack survives toggling.
- Predictive back runs through `PlatformPredictiveBackHandler` (expect/actual in `composeApp`), with overlays settling linearly on release; hero morphing (`Modifier.sharedHero`) is gated for Tier 4 software rasterizers and reduced motion.

## Known limits / debt

- Core playback (`ExpectMediaHandler.ios.kt`) and several core crypto/scraper actuals in `core/` remain `TODO()` until an iOS host shell + AVPlayer bridge is implemented.
- `DolbySpatialManager.ios.kt` and `UnmeteredNetwork.ios.kt` fail safe (`false`) rather than fabricating Atmos/head-tracking or unmetered network support.
- `withSyncApplying()` on iOS is `EmptyCoroutineContext` until a common `SyncApplying` context key is exposed outside `core/`.

## Refinement backlog

- Document the real iOS capability surface once an app shell exists.

<!-- doc-verified-against: 10f009ccdfbe820499ddeb50f2cf55426307747b -->
