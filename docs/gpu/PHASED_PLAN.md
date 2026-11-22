# Sovereign GPU Engine — Phased Implementation & 2-Engineer Orchestration Plan

## 1. Overview & Branching Strategy

To guarantee rapid progress without merge conflicts, work is split between two engineers across 4 sequential phases.

- **Base Integration Branch**: `integration/sovereign-graphics` (matches CI pattern `integration/**`).
- **Feature Branches**:
  - Engineer 1 branches: `gpu/phase<N>-shaders-engine1`
  - Engineer 2 branches: `gpu/phase<N>-frontend-engine2`
- **Promotion to Main**: Only after all PRs for Phases 1–4 are merged into `integration/sovereign-graphics` and all CI checks pass will the consolidated PR be opened against `main`.

---

## 2. Engineering Role Allocations

- **Engineer 1 (Track A — Systems, Shaders & Platform Drivers)**
  - Low-level SkSL / AGSL shaders and dialect transpilation.
  - Skia `RuntimeEffect` / `RuntimeShaderBuilder` (Desktop, iOS, Web).
  - Android `RenderEffect` & hardware buffer / data texture uploads.
  - Low-level GPU driver capability probes.

- **Engineer 2 (Track B — Frontend Architecture, State Machine & CI)**
  - 3-Layer Design Token system (`GlassTokens.kt`).
  - `TierGovernor.kt` reactive state machine (cold-start, promotion, thermal demotion).
  - Frozen public API facades (`Modifier.streamifyGlass(...)`).
  - Koin dependency injection wiring (`graphicsModule`).
  - Headless CI testing matrix and GitHub Actions workflows.

---

## 3. Phased Implementation Roadmap

### Phase 1: Foundation, Tokens & Public Seam
*Goal: Establish the contracts, token hierarchy, and frozen API facade so UI can bind without waiting on platform drivers.*

| Role | Tasks | Deliverables |
|---|---|---|
| **Engineer 1** | 1. Author canonical `LiquidGlassCore.sksl` with uniform branch controls.<br>2. Write test verifying dialect transpilation to AGSL and SkSL. | `shaders/LiquidGlassCore.sksl`<br>`test/DialectTranspileTest.kt` |
| **Engineer 2** | 1. Implement 3-layer design tokens (`PrimitiveTokens`, `SemanticTokens`, `ComponentTokens`).<br>2. Define `SovereignGraphicsEngine` interface and `Modifier.streamifyGlass(...)` facade.<br>3. Register `:streamify-modules:streamify-graphics` in `settings.gradle.kts` and configure Koin module. | `tokens/GlassTokens.kt`<br>`api/Modifier.streamifyGlass.kt`<br>`di/GraphicsModule.kt` |

*Merge Gate 1*: Both PRs pass compilation and unit tests against `integration/sovereign-graphics`.

---

### Phase 2: Multi-Platform Providers & 4-Tier Hardware Governor
*Goal: Implement platform rendering adapters and runtime performance/thermal degradation.*

| Role | Tasks | Deliverables |
|---|---|---|
| **Engineer 1** | 1. Implement `ShaderProvider.android.kt` (AGSL for API 33+, `RenderEffect` blur for API 31–32, and static acrylic scrim for API 26–30 / Vivo 1712).<br>2. Implement `ShaderProvider.skiko.kt` using Skia `RuntimeShaderBuilder` for Desktop, iOS, and wasmJs. | `androidMain/.../ShaderProvider.android.kt`<br>`skikoCommonMain/.../ShaderProvider.skiko.kt` |
| **Engineer 2** | 1. Implement `CapabilityProbe.kt` (Android API level, GL vendor string, `SwiftShader`/`llvmpipe` detection).<br>2. Implement `TierGovernor.kt` state machine: Cold starts at Tier 2; promotes to Tier 1 after 120 frames at $<8.3\text{ ms}$; demotes to Tier 3 on `THERMAL_STATUS_MODERATE` with 300-frame hysteresis. | `governor/CapabilityProbe.kt`<br>`governor/TierGovernor.kt`<br>`test/TierGovernorTest.kt` |

*Merge Gate 2*: Tier Governor unit tests pass, and API 27 fallback is validated.

---

### Phase 3: Zero-Allocation Audio Visualizer & Telemetry
*Goal: Implement real-time spectrum visualization without triggering Compose recomposition.*

| Role | Tasks | Deliverables |
|---|---|---|
| **Engineer 1** | 1. Implement `FftDataTexture`: upload 1024-bin float FFT array into $1024 \times 1$ texture ($<4\text{ KB}$, sub-$5\ \mu\text{s}$).<br>2. Author `VisualizerShader.sksl` to synthesize waveforms and spectrum bars inside fragment shader. | `visualizer/FftDataTexture.kt`<br>`shaders/VisualizerShader.sksl` |
| **Engineer 2** | 1. Build `Modifier.streamifyVisualizer(fftStream)` confined strictly to Draw Phase (`Modifier.drawBehind`), guaranteeing recomposition count = 0.<br>2. Implement `GraphicsTelemetry`: exposes rolling $P_{50}, P_{90}, P_{99}$ frame times and thermal events. | `api/Modifier.streamifyVisualizer.kt`<br>`telemetry/GraphicsTelemetry.kt` |

*Merge Gate 3*: Recomposition assertion test confirms 0 recompositions during 60 seconds of simulated playback.

---

### Phase 4: CI Golden Test Suite & Final Integration PR
*Goal: Automate regression protection and merge the complete module into `main`.*

| Role | Tasks | Deliverables |
|---|---|---|
| **Engineer 1** | 1. Build offscreen headless Skia test comparing render outputs with golden raster images.<br>2. Implement shader compilation failure test asserting fallback to Tier 4 without crash. | `test/OffscreenRenderGoldenTest.kt`<br>`test/ShaderFailureFallbackTest.kt` |
| **Engineer 2** | 1. Author `.github/workflows/_ci-graphics.yml` and wire path-filters in `ci.yml`.<br>2. Verify all branches merged into `integration/sovereign-graphics`, execute full test suite, and open the consolidated PR to `main`. | `.github/workflows/_ci-graphics.yml`<br>PR to `main` |

*Final Gate*: All CI status checks green across Android, Desktop, and Web.
