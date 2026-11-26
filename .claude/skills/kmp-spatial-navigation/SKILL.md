---
name: kmp-spatial-navigation
description: Design and audit Compose Multiplatform spatial navigation, reactive chrome policy, predictive back, and SharedTransitionLayout hero morphing in composeApp without per-frame recomposition.
---

# KMP Spatial Navigation & Shared-Element Skill

Use this skill when working on navigation graphs, screen transitions, predictive back, or shared-element morphing in `composeApp/` (Shells S01–S05, RFC-003).

## Core Architecture Rules

1. **Zero Extra Dependencies & Zero `core/` Edits**
   - Build strictly on `navigation-compose 2.9.2` and `compose-multiplatform 1.12.2` in `commonMain`.

2. **Zero-Recomposition Transition Discipline**
   - Never trigger Composition or Layout on navigation/predictive-back frames.
   - Drive per-frame motion strictly in the draw/layer phase (`Modifier.graphicsLayer { ... }`, `Modifier.animateEnterExit(...)`, `Modifier.sharedBounds(...)`).
   - **Never** animate individual `LazyColumn` / `LazyVerticalGrid` rows (`SettingScreen`, `SearchScreen`, `AnalyticsScreen`).
   - On heavy screens (`AnalyticsScreen` with `PlatformBackdrop` and charts), keep backdrop blur static during transitions and composite chart cards as cached layers (`CompositingStrategy.Offscreen`).

3. **Single `NavHost` + Reactive `ScreenPolicy`**
   - Never split into multiple `NavHost` trees or conditionally mount/unmount `SharedTransitionLayout` (which resets the back stack). Toggle behavior via `ScreenPolicy` and `LocalSharedTransitionScope` (`null` when disabled or on Tier 4 software rasterizers).
   - Derive bottom bar, navigation rail, desktop sidebar, mini-player, and `ForceDarkContent` reactively in `App.kt` from `navController.currentBackStackEntryAsState()`—never thread imperative `hideNavBar` / `showNavBar` callbacks into screens.

4. **Deterministic `SharedKeys` & Gesture-Cancel Safety**
   - Centralize shared-element keys in a single `SharedKeys` factory with origin disambiguation so duplicate items in carousels never collide.
   - `PlatformPredictiveBackHandler` (`androidMain` `PredictiveBackHandler`, `iosMain` edge-swipe, `jvmMain`/`wasmJsMain` `Esc`/popstate) must animate linearly from the current `progress` to `0f` (cancel) or `1f` (commit) on release.
