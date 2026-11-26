# RFC 003: KMP Spatial Navigation, Predictive Back & Shared-Element Architecture

**Status**: Accepted — implemented in ADR-010 (tracking issue #355). See the [RFC index](README.md).  
**RFC ID**: 003  
**Tracking Issue**: [#355](https://github.com/meraxes89/streamify-apk/issues/355)  
**Authors**: Streamify Core Engineering  
**Scope**: `composeApp/` (`commonMain`, `androidMain`, `iosMain`, `jvmMain`, `wasmJsMain`) — Shells S01–S05  
**Target Modules**: `composeApp` (no new external dependencies)

---

## 1. Executive Summary

This RFC proposes a multiplatform-native spatial navigation, predictive-back, and shared-element transition architecture for Streamify's Compose Multiplatform UI (`composeApp/`). It adapts the core architectural patterns of Felicity's seekable Fragment transition system (simultaneous overlapping axis motion, linear-to-decelerated predictive back seeking, declarative chrome policy, and anchor-morphed overlays) into idiomatic **Compose Multiplatform 1.12.2 + `navigation-compose 2.9.2`** in `commonMain` with **zero new dependencies**.

---

## 2. Motivation & Current Limitations

### Current State in `composeApp/`

1. **Single global transition** ([`AppNavigationGraph.kt`](../../composeApp/src/commonMain/kotlin/com/maxrave/simpmusic/ui/navigation/graph/AppNavigationGraph.kt)):
   `NavHost` applies one global `fadeIn() + slideInHorizontally { it }` / `slideOutHorizontally { -it }` (100% full-width slide) to every destination regardless of spatial hierarchy (peer tabs vs. drill-down lists vs. modal/player overlays).
2. **Imperative chrome coordination** ([`AppNavigationGraph.kt`](../../composeApp/src/commonMain/kotlin/com/maxrave/simpmusic/ui/navigation/graph/AppNavigationGraph.kt), [`App.kt`](../../composeApp/src/commonMain/kotlin/com/maxrave/simpmusic/App.kt)):
   `hideNavBar` and `showNavBar` lambdas are threaded into individual screens (`WrappedDestination`, `FullscreenDestination`, `loginScreenGraph`). If a back gesture is cancelled mid-flight or a route pops unexpectedly, imperative chrome state can desync from the visible destination.
3. **Intercepting back handler** ([`PlatformBackHandler.android.kt`](../../composeApp/src/androidMain/kotlin/com/maxrave/simpmusic/expect/ui/PlatformBackHandler.android.kt)):
   `PlatformBackHandler` wraps `androidx.activity.compose.BackHandler`, which consumes back events and disables Android's system predictive-back seeking on screens/overlays where it is active.
4. **No element-level morphing**:
   Navigating from the MiniPlayer to `NowPlayingScreen`, or from album/playlist cards to detail headers, animates whole screens independently rather than morphing shared bounds across the transition.

### Goals

- **Zero new dependencies**: Build entirely on `navigation-compose 2.9.2` and `compose-multiplatform 1.12.2` already pinned in `gradle/libs.versions.toml`.
- **100% `commonMain` routing & policy**: Platform-specific differences (predictive back progress, edge-swipe, keyboard Esc/browser back) isolated behind small `expect/actual` primitives.
- **Gesture-cancel safety**: Chrome visibility, system bar contrast, and transition progress must never desync when a user cancels a predictive back gesture.
- **No `core/` edits**: All changes remain strictly in `composeApp/`.

---

## 3. Proposed Architecture

### 3.1 Per-Route `ScreenPolicy` Registry (`commonMain`)

Introduce a pure-Kotlin policy model keyed by destination route/type:

```kotlin
enum class TransitionKind {
    SharedAxisX,   // ±25% horizontal drift + crossfade (peer/drill-down)
    SharedAxisZ,   // 0.85f <-> 1.15f scale + crossfade (depth / modal)
    Fade,          // Pure crossfade (top-level tab switches)
    SlideVertical, // Bottom-up sheet-like motion (fullscreen player / reel)
}

data class ChromePolicy(
    val showNavBar: Boolean = true,
    val showMiniPlayer: Boolean = true,
    val forceDarkContent: Boolean = false,
    val immersive: Boolean = false,
)

data class ScreenPolicy(
    val transition: TransitionKind = TransitionKind.SharedAxisX,
    val chrome: ChromePolicy = ChromePolicy(),
)
```

- **`AppNavigationGraph.kt`** resolves `enterTransition`, `exitTransition`, `popEnterTransition`, and `popExitTransition` from `initialState.destination` and `targetState.destination` via `resolveScreenPolicy(destination)`.
- **`SharedAxisX`** uses a subtle `0.25f` width drift + alpha crossfade so both entering and exiting screens overlap smoothly instead of sliding 100% off-screen.
- **Deceleration curve**: Normal programmatic transitions use `CubicBezierEasing(0.05f, 0.7f, 0.1f, 1.0f)`; `NavHost` automatically switches to linear seeking during Android predictive back when not blocked by `BackHandler`.

### 3.2 Reactive Chrome Coordination (`App.kt`)

Replace imperative `hideNavBar` / `showNavBar` callbacks with a reactive derivation in `App.kt`:

- Observe `navController.currentBackStackEntryAsState()` and resolve `currentPolicy = resolveScreenPolicy(entry?.destination)`.
- Drive bottom navigation bar, navigation rail, desktop sidebar, mini-player visibility, and `ForceDarkContent` directly from `currentPolicy.chrome` combined with sheet expansion state.
- **Benefit**: If a user starts a predictive back gesture out of `FullscreenDestination` or `WrappedDestination` and cancels it, `currentBackStackEntry` never commits the pop, so chrome never flickers or disappears.

### 3.3 KMP Predictive Back (`PlatformPredictiveBackHandler`)

Extend [`PlatformBackHandler.kt`](../../composeApp/src/commonMain/kotlin/com/maxrave/simpmusic/expect/ui/PlatformBackHandler.kt) with a gesture-progress-aware `expect/actual` composable for custom overlays (`NowPlayingScreen`, anchored morph dialogs, search overlays) that live outside `NavHost`:

```kotlin
@Composable
internal expect fun PlatformPredictiveBackHandler(
    enabled: Boolean,
    onProgress: (Float) -> Unit,
    onCancel: () -> Unit,
    onCommit: () -> Unit,
)
```

- **`androidMain`**: Backed by `androidx.activity.compose.PredictiveBackHandler` (`Flow<BackEventCompat>`). Emits raw linear `progress` (`0f..1f`) while dragging, `onCommit()` when the flow completes, and `onCancel()` on `CancellationException`.
- **`iosMain`**: Hooks into edge-swipe / interactive pop gesture progress (`0f..1f`).
- **`jvmMain` / `wasmJsMain`**: Maps `Esc` / browser popstate directly to `onCommit()` (`progress` stays `0f`).

To prevent visual snapping on release (the key insight from Felicity's `BaseSeekableTransition.getProgress()`), overlays driven by `PlatformPredictiveBackHandler` must animate from their current `progress` value linearly to `0f` (cancel) or `1f` (commit) rather than re-applying a non-linear `Easing` curve mid-gesture.

### 3.4 Element-Level Morphing (`SharedTransitionLayout`)

Wrap the root navigation container + persistent `MiniPlayer` / `NowPlayingScreen` in Compose `1.12`'s `SharedTransitionLayout`, exposing `LocalSharedTransitionScope` and `LocalNavAnimatedVisibilityScope` via `CompositionLocal`:

- **MiniPlayer ↔ `NowPlayingScreen`**: Artwork and track metadata share bounds (`Modifier.sharedBounds`) across collapse/expand.
- **Cards → Detail Headers**: Album, Artist, and Playlist cards opt into `Modifier.sharedElement` / `sharedBounds` when transitioning into `ListScreenGraph` destinations.
- **Anchored Dialogs**: Action menus and quick-option cards morph from their trigger button's bounds when a `sharedBounds` key is present, falling back to `SharedAxisZ` / `Fade` when detached.

---

## 4. Options Considered

| Option | Summary | Pros | Cons / Decision |
|---|---|---|---|
| **A (Proposed)** | `ScreenPolicy` + reactive chrome + `PlatformPredictiveBackHandler` + `SharedTransitionLayout` on `navigation-compose 2.9.2` | Zero new deps; preserves all 4 existing nested nav graphs; incremental rollout | Requires auditing `PlatformBackHandler` call sites so `NavHost` predictive back isn't blocked |
| **B** | Migrate to Decompose (`arkivanov/Decompose`) | Built-in KMP component tree + `PredictiveBackGestureOverlay` | New dependency; requires rewriting all destinations, ViewModels, and Koin scoping across 4 graphs |
| **C** | Status quo (global `NavHost` slide + imperative `hideNavBar`) | No work | Jarring 100% slides, chrome desync risk on gesture cancel, no shared-element morphing |

---

## 5. Migration Plan (Incremental)

1. **Phase 1 — `ScreenPolicy` & Reactive Chrome**: Add `ScreenPolicy` registry in `commonMain`, wire `resolveScreenPolicy` into `AppNavigationGraph.kt`, and migrate `App.kt` chrome visibility to react to `currentBackStackEntryAsState()`.
2. **Phase 2 — `PlatformPredictiveBackHandler`**: Add `expect/actual` `PlatformPredictiveBackHandler` and audit existing `PlatformBackHandler` usages so `NavHost` routes get native predictive back.
3. **Phase 3 — `SharedTransitionLayout`**: Wrap root shell in `SharedTransitionLayout` and opt in hero elements (MiniPlayer ↔ NowPlaying, Album/Playlist cards → Detail headers).

---

## 6. Open Questions

1. Should `TransitionKind` be user-configurable (e.g., `SharedAxisX` vs `SharedAxisZ` vs `Fade` in Appearance settings) or fixed per route hierarchy?
2. Should `SharedTransitionLayout` opt-in be gated behind a graphics capability tier check (e.g., disabled on Tier 4 software rasterizers in [RFC-001](RFC-001-sovereign-gpu-offload.md))?
3. How should `desktop` (`jvmMain`) multi-pane / sidebar layouts adapt `SharedAxisX` transitions when window width exceeds `WIDTH_DP_MEDIUM_LOWER_BOUND`?
