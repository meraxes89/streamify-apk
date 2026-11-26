# ADR-011: Sovereign GPU Engine Capability Matrix & Draw-Phase SPI (In Beta)

* Status: Proposed (In Beta on `integration/sovereign-graphics`; not yet merged to `main`)
* Date: 2026-11-26
* Verified against: `origin/main` (`a4a7e0abb36f38ba5b6b8d6dcaa4b1529070a3dc`) & `integration/sovereign-graphics` (`086de6b70`)

## Context

Streamify's Compose Multiplatform UI must render Liquid Glass surfaces and real-time audio visualizations across a wide hardware spectrum — from Android 8.1 / API 27 devices (e.g., Vivo 1712) and budget GPUs to Android 13+ AGSL, Desktop Skia, WebAssembly, and Apple Silicon (`iosArm64`, `iosSimulatorArm64`, macOS).

Four measured forces govern this subsystem (see [RFC-001](../rfc/RFC-001-sovereign-gpu-offload.md)):

1. **Zero recomposition on the render path**: driving shader uniforms (time, backdrop luminance, FFT spectrum) through Compose state triggers per-frame tree recomposition.
2. **Tile-based deferred rendering (TBDR) & Retina 3x bandwidth**: full-resolution multi-tap refraction at 3x density (~3.6M pixels per pass) flushes on-chip tile memory to system RAM on mobile GPUs.
3. **Variable refresh & thermal throttling**: ProMotion (10–120 Hz) shifts frame budgets from 16.6 ms to 8.33 ms, while `NSProcessInfo.thermalState` / `PowerManager` thermal events and Low Power Mode demand immediate demotion.
4. **API 27 class-isolation & fallback safety**: older Android ART processes must never class-link `android.graphics.RuntimeShader` (API 33+) or `RenderEffect` (API 31+), and shader compilation failures or software rasterizers (`SwiftShader`, `llvmpipe`) must never crash the draw thread.

Alternatives rejected: raw out-of-band Metal/OpenGL contexts blitted back into Compose (memory barriers, CPU readback stalls); marketing-name GPU whitelists (brittle and blind to runtime thermal state); static per-platform tier tables.

## Decision

Adopt a modular Sovereign GPU Engine (`:streamify-modules:streamify-graphics`, currently in **beta** on `integration/sovereign-graphics`) built on four invariants:

1. **Hexagonal draw-phase port (`SovereignGraphicsEngine`)**: UI composables call `Modifier.streamifyGlass(...)`, `Modifier.streamifyInteractiveGlass(...)`, `Modifier.streamifyVisualizer(...)`, or `Modifier.streamifyEffect(...)` confined strictly to `drawBehind` / `graphicsLayer` with composition-scoped request objects and zero per-frame allocations.
2. **2-axis capability matrix & race-free `TierGovernor`**: `Tier1_Ultra` (AGSL / Skia `RuntimeShader` with dispersion) → `Tier2_Balanced` (`RenderEffect` blur or single-tap / 0.5×–0.33× TBDR DRS offscreen pass) → `Tier3_Legacy` (acrylic scrim + 1px specular rim, zero shader classes) → `Tier4_Safe` (solid tint failover). State transitions use a single `AtomicReference` CAS loop; thermal/LPM demotions are synchronous, while P95 frame-budget misses step down one tier at a time with hysteresis cooldowns.
3. **Canonical SkSL + mechanical AGSL transpilation**: shaders are authored once in SkSL (`LiquidGlassCore.sksl`, `VisualizerShader.sksl`, or `GraphicsEffect.canonicalSkSl`) and mechanically transpiled/validated for Android AGSL via `AgslDialect`, with `GpuDataTexture` / `FftDataTexture` (1024×1 `RGBA_8888`, `frameStamp`-gated) for zero-allocation streaming textures.
4. **API-gated isolation**: all API 31+ and API 33+ Android classes live behind `@RequiresApi` isolation boundaries and `SDK_INT` checks.

## Consequences

* **Positive**: one multiplatform contract spans Android, Desktop, iOS (Metal via Skia), and Wasm without platform types leaking into `composeApp/`; draw-phase confinement preserves 0 recompositions during active playback and glass interaction.
* **Negative / Beta status**: the full `:streamify-modules:streamify-graphics` engine and `S24` system docs remain staged on `integration/sovereign-graphics` while app-layer adoption and CI validation mature, rather than shipping on `main` immediately.
* **Follow-up**: when `integration/sovereign-graphics` is ready for promotion to `main`, update this ADR's status to `Accepted` and land `docs/systems/S24-sovereign-graphics.md` alongside the module.

## Compliance

* Enforced on `integration/sovereign-graphics` by `.github/workflows/_ci-graphics.yml` (headless Skia golden/parity tests, AGSL dialect equivalence tests, `TierGovernorTest`, and API 27 isolation checks) and `scripts/verify_docs.py`.
