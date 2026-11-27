---
id: S02
title: Vivo store variant
tier: shell
status: active
owner: platform-android
sources: [vivoAndroidApp/]
tests: []
---

# S02 — Vivo store variant

> Part of the [subsystem index](../SYSTEMS.md). Documentation rules: [../README.md](../README.md).

## Owns

- Vivo package identity, store-specific manifest and OAuth callback resolution.

## Does not own

- Main Android behaviour (S01); update resolver policy (S20).

## Current truth

- Exists because the Vivo store requires a distinct package identity; see the ADR for the callback decision.
- Origin Island and notification-shade thumbnails rely on `ArtworkEnrichingPlayer` in `SimpleMediaService` (`core.patch`) emitting both `onMediaMetadataChanged` and `onEvents(EVENT_MEDIA_METADATA_CHANGED)` plus `getCurrentMediaItem()` artwork bytes via `CoilBitmapLoader`.

## Interfaces

- Store build → S20 update resolver → signed artifact.

## Decisions and specs

- Decision: [ADR-005-vivo-package-identity-and-oauth-resolution](../adr/ADR-005-vivo-package-identity-and-oauth-resolution.md)

## Current truth additions (RFC-003, #355)

- The shared Compose UI now derives navigation transitions per destination from the `ScreenPolicyRegistry` (`composeApp/.../ui/navigation/ScreenPolicy.kt`), and shell chrome (bottom bar / rail / mini player) reactively from `currentBackStackEntryAsState()` — the imperative `hideNavBar`/`showNavBar` callbacks are gone. All of it is behind the `enableSpatialNavigation` beta toggle (Settings → Appearance), off by default; the single `NavHost` tree is never swapped when the flag flips, so the back stack survives toggling.
- Predictive back runs through `PlatformPredictiveBackHandler` (expect/actual in `composeApp`), with overlays settling linearly on release; hero morphing (`Modifier.sharedHero`) is gated for Tier 4 software rasterizers and reduced motion.

## Known limits / debt

- Store-specific QA is manual and not in the deep matrix.

## Refinement backlog

- Document the Vivo release checklist in one place.

<!-- doc-verified-against: 9eb2963e7e99bf7e78343a239ee4f82af4dc97be -->
