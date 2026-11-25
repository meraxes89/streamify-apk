# Sovereign GPU Engine — CI/CD Testing Specification

## 1. Trigger Criteria & Path Filters

To optimize CI runner resource usage while preventing regressions, GPU CI tests are triggered automatically via `dorny/paths-filter` in `.github/workflows/ci.yml` whenever any of the following paths are modified in a Pull Request:

```yaml
graphics:
  - 'streamify-modules/streamify-graphics/**'
  - 'docs/gpu/**'
  - '.github/workflows/_ci-graphics.yml'
```

---

## 2. Test Suite Matrix

```
CI Preflight (dorny/paths-filter)
   │  triggers when `graphics` paths match
   ▼
Tier 2 Reusable Workflow: _ci-graphics.yml
   ├── [Job 1] TierGovernor State-Machine Unit Tests (JVM)
   ├── [Job 2] Android API 27 (Vivo 1712) Emulation Test (Robolectric)
   ├── [Job 3] Headless Desktop Skia Offscreen Golden Test (Linux Runner)
   ├── [Job 4] Software-Raster Fallback Gate (SwiftShader / LLVMpipe)
   └── [Job 5] Shader Compilation Failure & Safe Fallback Test
   ▼
Tier 3 Gate: CI Gate (Blocking)
```

### Detailed Test Invariants

### 1. TierGovernor State-Machine Test (Unit)
* **Target**: `TierGovernorTest.kt`
* **Invariants**:
  - Starts cold at **Tier 2 (Balanced)**.
  - Promotes to **Tier 1** only after 120 consecutive simulated frames with render time $< 8.3\text{ ms}$.
  - Demotes immediately to **Tier 3** upon receiving `PowerManager.THERMAL_STATUS_MODERATE` or $P_{95} > 16.6\text{ ms}$.
  - Enforces a 300-frame hysteresis delay before any subsequent re-promotion.

### 2. Android 8.1 / API 27 (Vivo 1712 Profile) Test
* **Target**: Robolectric test configured with `@Config(sdk = 27)`
* **Invariants**:
  - `ShaderProvider.android.kt` must return `Tier 3 (Legacy)` without throwing `NoClassDefFoundError` or `ClassNotFoundException` for `RuntimeShader` (API 33+) or `RenderEffect` (API 31+).
  - Asserts that an acrylic scrim with optical 1px highlight stroke is rendered.
  - Allocates 0 GPU shader programs.

### 3. Headless Desktop Skia Offscreen Golden Vector Test
* **Target**: `OffscreenRenderGoldenTest.kt` + `KyantParityGoldenTest.kt` (Desktop JVM on Ubuntu runner)
* **Invariants**:
  - Initializes headless Skia surface via `org.jetbrains.skia.Surface.makeRasterN32Premul`.
  - Executes `LiquidGlassCore.sksl` through `RuntimeEffect.makeForShader()`.
  - **Kyant parity (S24)**: renders the upgraded shader side-by-side with the independent straight-float Kyant reference model (the five-pillar math of `LiquidGlassContainer.kt`) across light, dark and saturated backdrops; RGB Mean Squared Error must confirm optical equivalence (MSE < 5e-4, quantization-noise budget), and the "đục đen" ramp must match `lerp(0.12, 0.50, clamp((luminance - 0.3) / 0.5))` exactly.
  - With `STREAMIFY_GOLDEN_DUMP=1` (CI sets it), the Kyant-reference | S24 | amplified-delta triptychs are written to `build/kyant-parity/` and uploaded as workflow artifacts — the visual comparison evidence for PRs.

### 4. Software-Raster Fallback Gate
* **Target**: `SoftwareRasterFallbackGateTest.kt`, executed by the dedicated Gradle task
  `./gradlew :streamify-modules:streamify-graphics:testSoftwareRasterFallback` (a separate Test JVM fork) with `LIBGL_ALWAYS_SOFTWARE=1` (forcing Mesa LLVMpipe).
* **Invariants**:
  - `CapabilityProbe` detects the software renderer marker — the full desktop spectrum (`llvmpipe` / `softpipe` / `swrast` / `basic render driver` / `WARP` / `rdpbus` / `SwiftShader` / `Software`) is pinned by `SkikoShaderProviderTest`, while this gate proves the **live** seam resolves the forced environment.
  - Forces governor to pin to **Tier 4 (Safe)** — axis 2 (raster kind) dominates even a successful CPU-side `makeForShader()` compile, and no promotion can escape the raster ceiling.
  - All shader compilation is bypassed (0-program execution plan, flat solid tint engine); rendering never crashes.
  - The unit suite also carries the deterministic injection lane: `streamify.graphics.renderer` system property overrides the renderer string without a GPU.

### 5. Shader Compilation Failure Fallback Test
* **Target**: `ShaderFailureFallbackTest.kt`
* **Invariants**:
  - Simulates a corrupt or unsupported shader string.
  - Confirms the engine catches `IllegalArgumentException` / compilation error gracefully and falls back to **Tier 4** without causing an application crash.

### 6. Desktop Spectrum Suite (S24)
* **Target**: `TierGovernorTest.kt`, `OffscreenRenderGoldenTest.kt` (both under `testDesktopUnitTest`)
* **Invariants**:
  - `TierGovernor.forRefreshRate` budget matrix: 60 Hz → 8.3/16.66 ms, 120 Hz → 4.16/8.33 ms, 144 Hz → 3.47/6.94 ms, 240 Hz → 2.08/4.16 ms; a struggling iGPU on a 144 Hz panel demotes promptly (7 ms frames are over the 6.94 ms P95 bar), a discrete GPU sustaining 2 ms frames promotes to Tier 1 at 240 Hz.
  - Tier 2 DRS golden gate: the offscreen pass renders at 0.5× width/height and upscales bilinearly to a clean full-size composite; unchanged backdrops return the cached result by identity (zero steady-state allocation).
  - Fractional pixel density gate: at 1.25 (500×400 physical) and 1.5 (450×300 physical) densities the `resolution` uniform receives the **physical pixel buffer dimensions** — pass output matches a hand-driven direct render at the same physical extent.

---

## 3. GitHub Actions Reusable Workflow Template (`_ci-graphics.yml`)

```yaml
name: Graphics Engine Verification

on:
  workflow_call:

jobs:
  test-graphics:
    name: "Graphics: Unit & Offscreen Suite"
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v7
      - uses: actions/setup-java@v4
        with:
          distribution: 'temurin'
          java-version: '21'

      - name: "Run Graphics State-Machine & Desktop Tests"
        run: ./gradlew :streamify-modules:streamify-graphics:testDesktopUnitTest

      - name: "Run Android Low-End (API 27) Emulation Tests"
        run: ./gradlew :streamify-modules:streamify-graphics:testDebugUnitTest

      - name: "Run Software-Raster Fallback Gate"
        env:
          LIBGL_ALWAYS_SOFTWARE: "1"
        run: ./gradlew :streamify-modules:streamify-graphics:testSoftwareRasterFallback
```
