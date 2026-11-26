---
id: ADR-010
title: KMP spatial navigation on a single NavHost
status: Accepted
date: 2026-11-26
verified: 25fce5ccb006d3f719c5bd10f97ab46776379f61
---

# ADR-010: KMP spatial navigation on a single NavHost

Implements [RFC-003](../rfc/RFC-003-kmp-spatial-navigation.md) (tracking issue #355).

## Context

The app had one global `NavHost` transition (a 100% full-width slide for every
route), imperative `hideNavBar`/`showNavBar` callbacks threaded from screens
(which desync when a back gesture is cancelled mid-flight), an intercepting
`PlatformBackHandler` that disabled system predictive back on overlays, and no
element-level morphing. Any fix had to work across Android/iOS/Desktop/Wasm from
`commonMain`, add zero dependencies, and never reset the back stack when the
rollout flag flips.

## Decision

1. **One `ScreenPolicy` registry** (`TransitionKind` + `ChromePolicy` per
   destination, `ScreenPolicyRegistry.policyFor`) drives both the NavHost
   transition lambdas (via one `appComposable<T>` helper used by all four graphs)
   and the shell chrome. Chrome is DERIVED in `App.kt` from
   `currentBackStackEntryAsState()` through `DestinationClassIndex` — screens no
   longer hold chrome callbacks, so a cancelled predictive back cannot desync it.
2. **Beta flag, stable tree**: `enableSpatialNavigation` (DataStore via
   `core.patch`, default FALSE) only changes transition lambdas and hero wiring.
   The `NavHost`, its graph and the root container are identical either way.
3. **Shared elements**: `SharedKeys.hero(origin, id)` builds disambiguated keys;
   `Modifier.sharedHero(key)` no-ops when `LocalSharedTransitionScope` or
   `LocalNavAnimatedVisibilityScope` is absent, so hero-wired call sites are safe
   in every mount (phone bar, tablet pad, desktop capsule) and under the Tier 4 /
   reduced-motion gate (`SpatialCapability`).
4. **Predictive back**: `PlatformPredictiveBackHandler` expect/actual — Android
   `androidx.activity.compose.PredictiveBackHandler` (linear `progress`),
   iOS edge-swipe registry, Desktop/Web commit-only (Esc / popstate). Overlays
   settle linearly to rest or collapse on release (`settlePredictiveRelease`).
5. **Zero extra CPU**: transitions use only graphics-layer effects (alpha,
   translation, scale) — draw phase, no composition/layout during scrubbing;
   Analytics charts and entry cards get `CompositingStrategy.Offscreen`; no
   per-row animations were added to any list.

## Consequences

- Adding a destination now means adding a `ScreenPolicy` entry (the exhaustive
  `ScreenPolicyTest` fails the build otherwise) and a `DestinationClassIndex` row.
- `core.patch` grows the two-flag surface (`spatial_navigation` key); the sync
  allowlist deliberately does NOT carry it (it is a per-device look-and-feel beta,
  same as `liquid_glass`).

## Alternatives rejected

- **Decompose migration** (RFC-003 option B): new dependency + rewrite of all
  destinations, ViewModels and Koin scoping.
- **Swapping NavHost trees per flag state**: resets the back stack — the exact
  breakage the beta gate exists to avoid.
- **A second chrome state holder**: reintroduces the desync the reactive
  derivation removed.
