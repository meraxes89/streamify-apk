# Sovereign GPU Engine — Architecture Specification

## 1. Foundational Architecture Principle: Ride Skia / HWUI

A critical failure mode in cross-platform GPU rendering is attempting to initialize raw out-of-band Vulkan or Metal device contexts and blit back into the Compose tree. This causes memory synchronization barriers, CPU readback stalls, and driver crashes across low-end Androids and Linux Wayland compositors.

**Compose Multiplatform (CMP) is rendered by Skia** (`Skiko` on Desktop, iOS, Web; Skia-backed `HWUI` on Android).
- Writing shaders in **SkSL / AGSL** compiled and executed by Skia's GPU backend **is** direct-to-driver execution.
- `:streamify-modules:streamify-graphics` is a **shader, effect, and capability governor abstraction**, maintaining 100% upstream immunity from `core/`.

```
Compose UI Layer (commonMain)
   │  Modifier.streamifyGlass(...)  /  Modifier.streamifyVisualizer(...)
   ▼
:streamify-modules:streamify-graphics
   ├── [Tokens]          3-Layer Tokens (Primitive → Semantic → Component)
   ├── [Governor]        TierGovernor State Machine (P95 frame-time & thermal headroom)
   ├── [Probe]           CapabilityProbe (API level, GPU backend, software raster detect)
   ├── [Facade]          SovereignGraphicsEngine Port (Hexagonal Architecture)
   └── [Adapters]        ShaderProvider (expect / actual)
           ├─ androidMain       → AGSL RuntimeShader (API 33+) / RenderEffect (31-32) / Acrylic Scrim (<31)
           └─ skikoCommonMain   → Skia RuntimeShaderBuilder (SkSL) [Desktop JVM, iOS, wasmJs Web]
   ▼
Native GPU Driver (Dispatched via Skia / HWUI)
   • Android 12+: Vulkan / OpenGL ES 3.2
   • Android 8.1 (Vivo 1712): Tier 3 Cached Acrylic Scrim (Hardware 2D Canvas)
   • macOS / iOS: Metal 2+
   • Windows: Direct3D 11 (via ANGLE)
   • Linux: OpenGL / EGL (Wayland integer scaling)
   • Web (wasmJs): WebGL 2.0 (Ganesh) / WebGPU (Graphite, feature-gated)
```

---

## 2. Universal Shader Intermediate Representation (SkSL)

SkSL is Skia's native shading language; Android's AGSL is a direct dialect. The entire refraction and liquid glass pipeline is defined in a single canonical shader (`LiquidGlassCore.sksl`):

```glsl
// LiquidGlassCore — canonical Sovereign GPU Engine refraction shader.
//
// Kyant optical parity (S24): the five optical pillars of the
// io.github.kyant0:backdrop catalog look —
//   1. Directional 45° specular rim (top-left light, cosine falloff,
//      subtle ambient rim on the opposite side)
//   2. Vibrancy: luminance-preserving saturation (1.5x) before the tint
//   3. Edge-only curvature (refraction strictly inside the lensRadius band)
//   4. Adaptive luminance scrim ("đục đen" ramp bound as darkenAmount)
//   5. Fluid spring & touch glow (facade-level, not in this shader)
uniform shader backdrop;          // Captured, pre-blurred backdrop layer
uniform float2 resolution;
uniform float  lensRadius;        // Edge curvature boundary
uniform float  refractionIndex;   // IOR (e.g. 1.25)
uniform float  chromaticSpread;   // Dispersion delta (Tier 1 only)
uniform half4  tintColor;
uniform float  vibrancy;          // Luminance-preserving saturation multiplier (Kyant parity: 1.5)
uniform float  darkenAmount;      // Adaptive luminance scrim, 0..1 ("đục đen" ramp)
uniform float  specularIntensity; // Directional 45° rim strength (Kyant parity: 0.35)

half4 main(float2 coord) {
    // Geometry: highp — normals and tap offsets must stay sub-pixel.
    float2 uv = coord / resolution;
    float2 p  = uv - 0.5;
    float  d  = length(p);
    float3 n  = float3(0.0, 0.0, 1.0);

    // Pillar 3 — edge-only curvature: the lens normal bends strictly inside
    // the lensRadius band along the boundary (d > 0.5 - lensRadius). The
    // interior core keeps the flat (0, 0, 1) normal, so its refraction
    // offset is zero and the content behind it samples undisplaced.
    if (d > 0.5 - lensRadius) {
        float e = (d - (0.5 - lensRadius)) / lensRadius;
        n = normalize(float3(p * e, 1.0 - e));
    }

    float2 off = n.xy * (refractionIndex - 1.0) * 0.05 * resolution;

    // Color path: mediump — the sampled channels are 8-bit targets.
    // Tier 1: 3-tap chromatic dispersion
    // Tier 2: Single-tap refraction (chromaticSpread set to 0.0)
    half3 c = half3(
        backdrop.eval(coord + off * (1.0 + chromaticSpread)).r,
        backdrop.eval(coord + off).g,
        backdrop.eval(coord + off * (1.0 - chromaticSpread)).b
    );
    half a = backdrop.eval(coord + off).a;

    // Schlick Fresnel approximation — the highp -> mediump boundary.
    half fr = half(pow(1.0 - max(n.z, 0.0), 3.0));

    // Pillar 2 — vibrancy: the Kyant colorControls saturation pass, applied
    // before the tint mix. c' = luma + (c - luma) * vibrancy preserves
    // luminance while deepening chroma, so bright artwork reads through the
    // glass instead of collapsing into milky white fog.
    half  luma = dot(c, half3(0.2126, 0.7152, 0.0722));
    half3 vibrant = clamp(mix(half3(luma), c, half3(vibrancy)), half3(0.0), half3(1.0));

    // Pillar 1 — directional 45° specular rim. Light travels from the
    // top-left: the boundary whose outward radial direction faces the light
    // receives the full highlight with a cosine falloff (Kyant
    // HighlightStyle.Default: angle 45°, falloff 1.0), while the opposite
    // boundary keeps a subtle ambient rim (25% of the lit term). The Schlick
    // Fresnel term stays as the grazing-angle envelope, so the rim still
    // peaks exactly at the edge and never bleeds into the flat core.
    float2 lightDir = normalize(float2(-1.0, -1.0));
    float2 radial = p / max(d, 1e-6);
    float  lit = clamp(dot(radial, lightDir), 0.0, 1.0);
    half   rim = fr * half(lit + 0.25 * (1.0 - lit)) * half(specularIntensity);

    half3 composite = mix(vibrant, tintColor.rgb, tintColor.a);

    // Pillar 4 — adaptive luminance scrim: the "đục đen" darkening ramp,
    // bound by the platform adapters from the backdrop luminance. Applied
    // after the tint and the rim exactly like Kyant's onDrawSurface scrim
    // rect, so over bright album art the surface deepens instead of washing
    // out and overlaying typography stays legible.
    half3 darkened = clamp((composite + half3(rim)) * half3(1.0 - darkenAmount), half3(0.0), half3(1.0));
    return half4(darkened, a);
}
```

The small dialect variances between AGSL (`setInputBuffer`) and SkSL (`child("backdrop", ...)`) are handled inside the platform-specific `ShaderProvider` implementations.

### 2a. Precision Contract (Track A — mediump / highp)

Older Mali (Midgard/Bifrost) and Adreno 5xx unified-ALU parts run 16-bit (`half`/mediump) color arithmetic at **2× the 32-bit (highp) rate**; forcing highp across the fragment shader halves arithmetic instruction throughput on exactly the devices the universal matrix targets. The canonical shader therefore splits precision along the data's actual needs:

| Path | Type | Why |
|---|---|---|
| Geometry: `uv`, `p`, `d`, `e`, `off`, normals | `float` (highp) | 32-bit mantissas keep edge-curved normals smooth and the refraction offset sub-pixel accurate at any panel density. On a 2560px panel a mediump `uv` would quantize to ~1.25px steps — visible banding. |
| Color path: backdrop taps `c`/`a`, tint mix, Fresnel `fr`, specular add | `half` (mediump) | 8-bit output depth cannot resolve a 10-bit mantissa, so the downgrade is visually free. On modern GPUs `half` compiles to the same SASS with zero cost. |
| The highp → mediump boundary | `half fr = half(...)` | One deliberate precision gate — the Schlick exponent is evaluated on the proven float overload (one highp scalar op) and stored onto the half path; everything downstream is half arithmetic. |

`AgslDialect.validatePrecisionContract` is the fail-fast gate over this table: it lints the canonical source (comments stripped) and rejects any regression — `float3`/`float` taps, a dropped `half fr` boundary, or a `float(...)` cast re-promoting the color path — so the throughput win cannot be silently reverted. The gate runs in the desktop CI suite (`UniversalAndroidMatrixTest`) and the Android startup probe lane.

On AGSL both `half` and `float` are 32-bit aliases (`half` is a GPU-targeting hint), so the same source compiles identically on the Android and Skia lanes — pinned by the `AgslDialectEquivalenceTest` pixel gate.

**Kyant optical parity (S24).** The three S24 uniforms are bound by both platform adapters from the frozen token layer: `vibrancy` = 1.5x (`colorControls` saturation parity), `darkenAmount` = the CPU-evaluated `lerp(0.12, 0.50, clamp((luminance - 0.3) / 0.5))` "đục đen" ramp, and `specularIntensity` = 0.35. The ramp keeps white text legible over bright album art instead of letting the glass wash out. Pixel-for-pixel equivalence with the reference model is gated in CI by `KyantParityGoldenTest` (MSE < 5e-4 across light, dark and saturated backdrops); pre-rendered comparison twins live in `docs/gpu/parity/`.

---

## 3. The 4-Tier Hardware Capability Matrix

### Tier Determination Logic
The engine **never** relies on GPU marketing names (e.g., Adreno 506 vs Mali-G71) — the one surgical exception is the Track D denylist below, which names *proven compiler defects*, never performance judgments. Tiering is evaluated along three orthogonal axes:
1. **API / Driver Capability**:
   - Android API $\ge 33$: AGSL capable $\to$ Tier 1/2 candidate.
   - Android API 31–32: `RenderEffect.createBlurEffect()` $\to$ Tier 2.
   - Android API $< 31$ (**Vivo 1712 / Android 8.1 / API 27**): Bypasses shaders $\to$ **Tier 3 (Legacy)**.
   - Skia: `RuntimeEffect.makeForShader()` validation.
   - **Startup compile probe (Track B)**: on API 33+ the probe compiles the *canonical* transpiled `LiquidGlassCore` source — not a minimal synthetic program — through the real `RuntimeShader` compiler once at process start, isolated inside the `@RequiresApi(33)` `Agsl33Isolated` lane. Any `Throwable` (OEM driver crash inside `glCompileShader`, AGSL rejection of valid syntax) is caught, recorded in `CapabilityProbe.compileProbeFailureReason`, and the device degrades to the blur (Tier 2) or scrim (Tier 3) lane **before the Compose tree ever calls `drawBehind`** — the Koin graph resolves the probe at engine construction, which precedes the first draw.
2. **Raster Kind** (dominates axis 1 — a software rasterizer pins Tier 4 even when SkSL compiles successfully on the CPU):
   - Linux / Mesa: `llvmpipe`, `softpipe`, `swrast` (headless CI, Linux VMs, WSL2 — frequently pinned via `LIBGL_ALWAYS_SOFTWARE=1`).
   - Windows / DirectX: `basic render driver`, `WARP`, `rdpbus` (Microsoft's CPU rasterizer used by driver-less installs and RDP sessions).
   - Headless / generic: `SwiftShader`, `Software`.
   - Any of these markers in the renderer description immediately pins **Tier 4 (Safe)**. The desktop adapter sources the live string through a layered seam (`streamify.graphics.renderer` override property → forced-software environment → Skiko `renderApi` graphics backend properties → conservative empty default); a contextless `glGetString` is deliberately **not** attempted (undefined behavior without a current GL context — skiko's JNI wrapper does not null-check the driver's return).
3. **Runtime Governor & Thermals**:
   - Measures rolling $P_{95}$ frame render times over 60-frame windows.
   - Android `PowerManager.getThermalHeadroom()` / `addThermalStatusListener`; Apple `NSProcessInfo.thermalState` via the iosMain environment bridge (`serious` → MODERATE, `critical` → CRITICAL — see [docs/systems/S24-sovereign-graphics.md](../systems/S24-sovereign-graphics.md)).
   - Cold starts at **Tier 2 (Balanced)**. Promotes to **Tier 1** only after 120 consecutive frames under the live fast-frame budget (display-cadence calibrated: 4.16 ms at 120 Hz ProMotion, 8.3 ms at 60 Hz) with NOMINAL thermal status. Budgets recalibrate on display-link changes; Low Power Mode pins them to the 60 Hz lane and caps the ceiling at Tier 2.
   - Thermal demotion is immediate and synchronous: MODERATE/SEVERE → **Tier 3**; CRITICAL/EMERGENCY → **Tier 4**. P95 violations demote **one tier at a time** (Tier 1 → Tier 2 single-tap → Tier 3), so a ProMotion device dropping from 120 to 90 FPS keeps rendering glass instead of bouncing to the scrim. Hysteresis (300 frames) prevents visual bouncing.
   - **Monitor refresh rate budgeting (S24)**: `TierGovernor.forRefreshRate(probe, displayHz)` scales both budgets proportionally with the live panel rate (120 Hz → 4.16/8.33 ms, 144 Hz → 3.47/6.94 ms, 240 Hz → 2.08/4.16 ms) so struggling iGPUs demote promptly against their real panel target while discrete GPUs sustain Tier 1. The desktop adapter feeds the default screen device's AWT display mode; non-desktop targets keep the 60 Hz calibration.
   - **TBDR-friendly dynamic resolution scaling:** the Tier 2 lane on Retina-class densities (≥ 2x) downsamples its backdrop capture/refraction pass to logical 1x (0.5×/0.33×) and upscales with hardware bilinear filtering — ~75% less Apple-Silicon tile-flush traffic; Tier 1 earns full resolution.
4. **Known Driver Quirks (Track D)** — a surgical denylist of *proven* driver compiler defects, keyed to renderer markers that identify the broken driver revision:
   - Emulator software-GLES renderers with broken half-float division / NaN handling (`SwiftShader (Software)`, `ANGLE (Android Emulator, Vulkan)` markers) $\to$ pinned to **Tier 4 (Safe)**.
   - The table **only demotes**: a quirk hit can pin Tier 4 but can never lift a device above its probed capability, and a compliant GPU — which simply does not carry the marker — is never penalized. A healthy Adreno 5xx / Mali-G52 still classifies `RUNTIME_SHADER` at Tier 1; the denylist never tiers whole marketing families.

### Desktop GPU Spectrum (S24): DRS & Backend Safety

**Dynamic Resolution Scaling (Tier 2 UMA lane).** On the skiko targets the Tier 1 / Tier 2 engines run the canonical refraction through `SkikoOffscreenGlassPass`: an offscreen raster surface whose pixel dimensions are the draw scope's **physical buffer dimensions × pass-scale** — 1.0× (dispersion) on Tier 1, **0.5× width/height on Tier 2**. The backdrop child samples the full-resolution captured snapshot (LINEAR-filtered local matrix = the downsampling step), the pass evaluates at ~25% of the pixel count, and the composite upscales the result bilinearly — cutting UMA sampling traffic by ~75% so Intel HD 2000–630 / Iris / AMD Vega iGPUs hold 60 FPS inside Tier 2 instead of dropping to the Tier 3 scrim. The facade feeds the pass through `Modifier.streamifyGlass(rememberGlassBackdrop(layer), shape, tint)`: `GraphicsLayer.toImageBitmap()` is a suspend API, so the capture (and all pass reallocation) runs on the composition frame clock — **never inside the draw phase** — while a steady-state frame is a single cached `drawImage` plus the specular rim (zero allocation, zero querying).

**Physical-pixel resolution uniform.** `uniform float2 resolution` binds to the device pixel dimensions of the surface the shader actually evaluates in. Compose Desktop records its picture at `componentWidth × contentScale` (skiko `SkiaLayer.awt.kt`), so a draw scope's pixel size already *is* the physical buffer size on fractional-scale Wayland / X11 / Windows displays — multiplying by density on top of that would double-scale and warp the lens falloff at 125%/150%. The golden gate pins this by rendering at 1.25/1.5 densities and matching against a hand-driven direct render at the same physical extent.

**Windows Direct3D 11 priority.** `SkikoDesktopBackendConfig.install()` (called from the desktop `main()`) pins `skiko.renderApi=DIRECT3D` on Windows unless the user explicitly forced an API (`skiko.renderApi` / `SKIKO_RENDER_API`). D3D11 is skiko's Windows default and the lane that bypasses the broken legacy Intel OpenGL driver stack (`ig4icd64.dll`); the pin makes the default robust against inherited environment overrides. The config is idempotent and non-throwing on every OS.

---

## 4. Audio Visualizer Zero-Allocation Draw Pipeline

Passing FFT audio frequencies through Compose state forces recompositions on every frame, which destroys low-end battery life.

**The Solution:**
1. **DSP Producer**: Rust/C++ DSP writes 1024-bin FFT frames into a shared ringbuffer.
2. **Data-Texture Upload**: Upload the $1024 \times 1$ float array as an offscreen texture ($< 4\text{ KB}$, $< 5\ \mu\text{s}$ transfer time).
3. **Draw-Phase Isolation**: Visualizer draws exclusively via `Modifier.drawBehind { drawRect(...) }`. The fragment shader samples the data texture to synthesize waveforms, spectrum bars, and fluid particles.
4. **Result**: **Recomposition count remains strictly 0 during active playback.**

---

## 5. Design Token Integration & Upstream Decoupling

- **3-Layer Token Architecture**:
  - `PrimitiveTokens`: Raw color floats, blur sigma, refraction scales.
  - `SemanticTokens`: `glassSurfaceActive`, `glassSurfaceDisabled`, `lensBorderSpecular`.
  - `ComponentTokens`: Specific paddings and radii for player bars and cards.
- **Upstream Immunity**: All composables consume `Modifier.streamifyGlass(backdrop, shape, tint)`. Upstream `core/` submodule remains unmodified.
