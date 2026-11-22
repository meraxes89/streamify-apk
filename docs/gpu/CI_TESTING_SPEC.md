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
* **Target**: `OffscreenRenderGoldenTest.kt` (Desktop JVM on Ubuntu runner)
* **Invariants**:
  - Initializes headless Skia surface via `org.jetbrains.skia.Surface.makeRasterN32Premul(400, 400)`.
  - Executes `LiquidGlassCore.sksl` through `RuntimeEffect.makeForShader()`.
  - Compares the rendered buffer pixel-by-pixel against a committed golden vector PNG with a strict Root Mean Square Error (RMSE) threshold $< 0.01$.

### 4. Software-Raster Fallback Gate
* **Target**: Runner executed with `LIBGL_ALWAYS_SOFTWARE=1` (forcing Mesa LLVMpipe).
* **Invariants**:
  - `CapabilityProbe` detects software renderer string (`llvmpipe` / `SwiftShader`).
  - Forces governor to pin to **Tier 4 (Safe)**.
  - All shader compilation is bypassed; flat solid tint renders cleanly without crash.

### 5. Shader Compilation Failure Fallback Test
* **Target**: `ShaderFailureFallbackTest.kt`
* **Invariants**:
  - Simulates a corrupt or unsupported shader string.
  - Confirms the engine catches `IllegalArgumentException` / compilation error gracefully and falls back to **Tier 4** without causing an application crash.

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
