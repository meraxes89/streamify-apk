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
uniform shader backdrop;          // Captured, pre-blurred backdrop layer
uniform float2 resolution;
uniform float  lensRadius;        // Edge curvature boundary
uniform float  refractionIndex;   // IOR (e.g. 1.25)
uniform float  chromaticSpread;   // Dispersion delta (Tier 1 only)
uniform half4  tintColor;

half4 main(float2 coord) {
    // Geometry: highp — normals and tap offsets must stay sub-pixel.
    float2 uv = coord / resolution;
    float2 p  = uv - 0.5;
    float  d  = length(p);
    float3 n  = float3(0.0, 0.0, 1.0);

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
    return mix(half4(c, a), tintColor, tintColor.a) + half4(1.0) * fr * 0.35;
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
2. **Raster Kind**:
   - If GL vendor or renderer contains `SwiftShader`, `llvmpipe`, or `Software`, immediately pin to **Tier 4 (Safe)**.
3. **Runtime Governor & Thermals**:
   - Measures rolling $P_{95}$ frame render times over 60-frame windows.
   - Android `PowerManager.getThermalHeadroom()` / `addThermalStatusListener`.
   - Cold starts at **Tier 2 (Balanced)**. Promotes to **Tier 1** only after 120 consecutive frames at $<8.3\text{ ms}$ (120 Hz). Demotes immediately to **Tier 3** if thermal status reaches `MODERATE` or frame spikes exceed budget. Hysteresis (300 frames) prevents visual bouncing.
4. **Known Driver Quirks (Track D)** — a surgical denylist of *proven* driver compiler defects, keyed to renderer markers that identify the broken driver revision:
   - Emulator software-GLES renderers with broken half-float division / NaN handling (`SwiftShader (Software)`, `ANGLE (Android Emulator, Vulkan)` markers) $\to$ pinned to **Tier 4 (Safe)**.
   - The table **only demotes**: a quirk hit can pin Tier 4 but can never lift a device above its probed capability, and a compliant GPU — which simply does not carry the marker — is never penalized. A healthy Adreno 5xx / Mali-G52 still classifies `RUNTIME_SHADER` at Tier 1; the denylist never tiers whole marketing families.

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
