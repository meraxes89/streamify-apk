---
id: S24
title: Sovereign GPU Engine
tier: client
status: active
owner: graphics
sources: [streamify-modules/streamify-graphics/, docs/gpu/]
tests: [streamify-modules/streamify-graphics/src/desktopTest/]
---

# S24 — Sovereign GPU Engine: Apple Silicon & iOS/macOS GPU Spectrum Matrix

* System: `S24` — Sovereign GPU Engine (`:streamify-modules:streamify-graphics`)
* Status: **Active** (Apple matrix landed on `feat/gpu-universal-apple-matrix` → `integration/sovereign-graphics`)
* Owner: graphics subsystem (`docs/gpu/`)
* Decision record: [ADR-010](../adr/ADR-010-sovereign-gpu-engine.md)
* Specification: [docs/gpu/ARCHITECTURE.md](../gpu/ARCHITECTURE.md) · [docs/gpu/CI_TESTING_SPEC.md](../gpu/CI_TESTING_SPEC.md)
* Module root: `streamify-modules/streamify-graphics/`

<!-- doc-verified-against: b1477154b010bd45c2dc65295f879bf98f9c8152 -->

---

## 1. Purpose & Scope

The Sovereign GPU Engine renders Liquid Glass through Skia on every Apple
target — `iosArm64`, `iosSimulatorArm64`, and the desktop macOS JVM — via the
shared `skikoCommonMain` adapter (`ShaderProvider.skiko.kt`), where SkSL is
lowered to **Metal Shading Language** by Skia's Metal backend.

This document is the **contract home for the Apple capability matrix**: how
the engine adapts to the four Apple-ecosystem constraints that otherwise cause
dropped frames, battery drain, or thermal throttling:

1. **TBDR tile cache flushes** — Apple Silicon renders through small on-chip
   tile memories; full-resolution backdrop capture + multi-pass refraction at
   Retina 3x (1290 × 2796 on iPhone Pro Max, over 3.6M pixels) repeatedly
   flushes tile memory to system RAM, choking older A-series parts (A10–A13).
2. **ProMotion variable refresh (10–120 Hz)** — at 120 Hz the frame budget
   drops from 16.6 ms to 8.33 ms.
3. **Thermal & Low Power Mode throttling** — `NSProcessInfo.thermalState`
   (`serious`/`critical`) and `isLowPowerModeEnabled` demand instant,
   synchronous demotion.
4. **Simulator vs. device parity** — headless macOS CI runners and iOS
   Simulators must never crash on uninitialized Metal layers or headless
   framebuffers.

The engine's two invariants hold throughout: **zero recomposition and zero
steady-state allocation on the draw path**, and the **2-axis capability
matrix** — no marketing-name whitelists, only API/driver compilation
capability and rasterizer/thermal state.

## 2. Track A — Native Apple Thermal & Low Power Mode Bridge (`iosMain`)

**Files:** `src/iosMain/kotlin/com/streamify/graphics/di/GraphicsEnvironmentBridge.ios.kt`,
`src/iosMain/kotlin/com/streamify/graphics/governor/AppleThermalStateMapping.ios.kt`,
`src/commonMain/kotlin/com/streamify/graphics/governor/AppleThermalStateMapping.kt`,
`src/commonMain/kotlin/com/streamify/graphics/di/GraphicsEnvironmentBridge.kt`

`NSProcessInfo.thermalState` welds onto the multiplatform `ThermalStatus`
ladder (the common table is pure and desktop-testable; the iosMain weld is
pinned by `iosTest`):

| `NSProcessInfoThermalState` | raw | `ThermalStatus` | Governor consequence |
|---|---|---|---|
| `Nominal`  | 0 | `NOMINAL`  | promotion gate open |
| `Fair`     | 1 | `LIGHT`    | below demotion threshold |
| `Serious`  | 2 | `MODERATE` | instant synchronous demotion to **Tier 3** (scrim) |
| `Critical` | 3 | `CRITICAL` | instant synchronous demotion to **Tier 4** (safe tint) |

Unknown raw values fail conservative (→ `CRITICAL`).

`NSProcessInfoThermalStateDidChangeNotification` and
`NSProcessInfoPowerStateDidChangeNotification` are observed via
`NSNotificationCenter.defaultCenter` with `queue = null` — the observer runs
**synchronously on the posting thread**, and `TierGovernor` absorbs it from
any thread (CAS-transitioned state). When `isLowPowerModeEnabled` turns on,
the governor caps the promotion ceiling at `Tier2_Balanced`, demotes a live
Tier 1 immediately, and pins frame budgets to the 60 Hz lane. The bridge is
attached exactly once from the Koin wiring (`di/GraphicsModule.kt`) and is
idempotent (attach → detach-then-attach).

Android/desktop/wasm actuals are documented no-ops: Android thermal stays
with the app-layer `ThermalGovernorManager` (zero `PowerManager` references
in-module, so API 27 processes never class-link through the bridge).

## 3. Track B — ProMotion Adaptive Frame Budgeting

**Files:** `src/commonMain/.../governor/TierGovernor.kt` (+ `forRefreshRate`,
`onDisplayRefreshRateChanged`, `onLowPowerModeEnabled`),
`src/commonMain/.../governor/PlatformDisplayMetadata.kt` (expect),
`src/iosMain/.../governor/AppleDisplayMetadata.ios.kt` (`UIScreen
.mainScreen.maximumFramesPerSecond`), desktop/Android/wasm actuals.

Cold start budgets come from the platform's real display cadence —
`TierGovernor.forRefreshRate(probe, platformDisplayRefreshRateHz())`:

| Display link | Fast-frame budget | P95 budget |
|---|---|---|
| 120 Hz ProMotion | 4.16 ms | 8.33 ms |
| 60 Hz standard | 8.3 ms | 16.66 ms |
| Low Power Mode (any) | 8.3 ms | 16.6 ms |

Runtime cadence shifts (ProMotion 10–120 Hz downshifts) recalibrate the live
budgets through `TierGovernor.onDisplayRefreshRateChanged(hz)` — reported by
the host display-link owner (`CADisplayLink.targetTimestamp - timestamp` on
iOS, AWT display mode on desktop).

**Micro-stutter prevention:** P95 violations now demote **one tier at a
time** (Tier 1 → Tier 2 single-tap → Tier 3). An intense liquid-glass pass
dropping from 120 FPS to 90 FPS on an iPhone 13 Pro lands on the cheaper
single-tap shader and keeps rendering glass instead of bouncing visually to
the scrim; a further violated window steps to Tier 3. Thermal demotions stay
instant and absolute.

## 4. Track C — TBDR-Friendly Dynamic Resolution Scaling (Retina 3x)

**Files:** `src/commonMain/.../governor/BackdropResolutionPolicy.kt`,
`src/skikoCommonMain/.../engine/DynamicResolutionOffscreenPass.kt`,
`src/skikoCommonMain/.../engine/ShaderProvider.skiko.kt`,
`src/skikoCommonMain/.../engine/SkikoPlatformSignals.kt` (density expect),
`src/iosMain/.../engine/AppleSkikoSignals.ios.kt` (`UIScreen.mainScreen.scale`).

The 2-axis decision table (no chipset names — older A-series devices
converge on the Tier 2 + Retina row *through the governor*):

| Live tier | Density scale | Capture scale | Rationale |
|---|---|---|---|
| Tier 1 | any | 1.0 | earned full resolution |
| Tier 2 | ≥ 2.0 | 1/density (≥ 1/3) | logical 1x: 0.5 at 2x, 0.33 at 3x |
| Tier 2 | < 2.0 | 1.0 | logical 1x is full resolution |
| Tier 3 / 4 | any | 1.0 | scrim lanes: no blur/refraction pass |

When the Tier 2 engine runs on a Retina-class display and the program's
`backdrop` child is bound (the facade offscreen snapshot seam), the
`DynamicResolutionOffscreenPass` renders the refraction composite at the
reduced capture resolution into a cached raster surface and the engine
upscales it into the destination outline with hardware bilinear filtering
(`FilterQuality.Low`). At 3x that is 1/9th the pixels and ~75% less TBDR
tile-flush traffic during rapid sheet/modal scrolling. The pass surface is
allocated per capture size (steady draw loops reuse it); the Tier 1 and
scrim lanes construct no pass.

## 5. Track D — Metal Resilience & Simulator/Headless Safety

**Files:** `src/skikoCommonMain/.../engine/ShaderProvider.skiko.kt`
(`SkikoLiquidGlassProgram` init), platform signal actuals.

* `SkikoLiquidGlassProgram`'s constructor validates the **full** pipeline —
  `RuntimeEffect.makeForShader` **plus** one child-bound `makeShader()`
  round-trip — so unexpected Metal pipeline-state creation errors surface at
  construction, the lazy holder converts them to `null`, and every lane
  degrades to `AcrylicScrimGlassEngine`. A draw never sees them.
* Per-draw, the DRS pass is wrapped in `runCatching`; any failure falls back
  to the stable composite (clipped backdrop + tint + rim).
* Headless/CI/simulator detection feeds the probe's renderer description
  (`skikoRendererDescription()`): the strings are deliberately free of the
  `llvmpipe`/`SwiftShader`/`Software` marker vocabulary — headless JVM and
  the iOS Simulator (host Metal, paravirtualized) still validate
  `RuntimeEffect` and stay on the full capability matrix; markers are
  reserved for genuinely forced software rasterization
  (CI_TESTING_SPEC.md Job 4).

## 6. Verification Matrix (Track E)

| Gate | Where | What it pins |
|---|---|---|
| Thermal mapping table | `desktopTest/.../governor/AppleThermalStateMappingTest.kt` | nominal/fair/serious/critical → status + demotion consequence; conservative unknowns |
| Foundation-constant weld | `iosTest/.../governor/AppleThermalStateWeldTest.kt` | iosMain `mapThermalState` == common table |
| Governor transitions | `desktopTest/.../governor/TierGovernorTest.kt` | thermal ladder (T3/T4), stepped P95, LPM cap + 60 Hz pin, 120 Hz vs 60 Hz budgets, cadence recalibration, hysteresis, race-freedom |
| DRS policy | `desktopTest/.../governor/BackdropResolutionPolicyTest.kt` | the full (tier × density) decision table |
| DRS offscreen pass | `desktopTest/.../engine/DynamicResolutionOffscreenPassTest.kt` | 1/3-raster dimensions, spatially varying alpha-preserving output, surface reuse, engine wiring |
| Bridge smoke | `iosTest/.../di/GraphicsEnvironmentBridgeTest.kt` | attach/detach/idempotency on the simulator, live thermal read |
| Kotlin/Native compile | `.github/workflows/_ci-graphics.yml` | `compileKotlinIosArm64` + `compileKotlinIosSimulatorArm64` (ubuntu, klib) and `iosSimulatorArm64Test` (macOS runner) — zero warnings, zero linker failures |

Pre-push local gates (mirrored in CI):

```bash
./gradlew :streamify-modules:streamify-graphics:compileKotlinIosArm64
./gradlew :streamify-modules:streamify-graphics:compileKotlinIosSimulatorArm64
./gradlew :streamify-modules:streamify-graphics:testDesktopUnitTest
./gradlew :streamify-modules:streamify-graphics:desktopTest
python3 scripts/docs_ledger_check.py
python3 scripts/verify_docs.py
```

## 7. Architecture Boundaries

* The governor is pure common code; every platform input enters through
  expect/actual seams (`platformDisplayRefreshRateHz`,
  `GraphicsEnvironmentBridge`, `skikoRendererDescription`,
  `skikoDisplayDensityScale`) — no platform type leaks into `commonMain`.
* The frozen public facade (`Modifier.streamifyGlass`) is untouched; all
  changes are additive behind the `SovereignGraphicsEngine` port and the
  Koin graph.
* The refraction composite's live backdrop *snapshot* remains the documented
  facade-level offscreen seam (`GraphicsLayer.toImageBitmap()` is suspend);
  the DRS pass is the machinery that seam feeds, exercised end-to-end by the
  CI offscreen gates.
