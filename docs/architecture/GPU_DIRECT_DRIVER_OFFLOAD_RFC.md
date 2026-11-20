# RFC 001: Sovereign Direct GPU Offloading Architecture
**Status**: Proposal / Community RFC  
**Authors**: Streamify Core Engineering  
**Scope**: Android (AGSL / Vulkan), macOS (Metal), Linux (Vulkan / Mesa), Windows (Direct3D 12 / Vulkan), Web (WebGL 2 / WebGPU)  
**Target Module**: `:streamify-modules:streamify-graphics`

---

## 1. Executive Summary
This RFC proposes a sovereign, cross-platform graphics pipeline to offload heavy rendering tasks—specifically **Liquid Glass / Glassmorphism**, real-time audio visualizers, and refraction shaders—directly to native graphics drivers (**Vulkan, Metal, Direct3D 12, WebGL 2.0**) while guaranteeing:
1. **120Hz Fluidity**: Elimination of multi-pass CPU readbacks and composite layer thrashing.
2. **Graceful Degradation**: 4-Tier Hardware Capability Matrix supporting 32-bit `armeabi-v7a` legacy devices, Intel iGPUs, Linux LLVMpipe, and resource-constrained browsers.
3. **Upstream Immunity**: 100% decoupling from upstream SimpMusic (`core/` submodule untouched).

---

## 2. Motivation & Architectural Challenges

### Current State
Compose Multiplatform (CMP) currently relies on `io.github.kyant0:backdrop` and standard Compose `Modifier.blur()`. On low-tier devices or systems with integrated GPUs:
- Android prior to API 31 lacks `RenderEffect` and silently drops blur modifiers.
- Desktop blurs incur offscreen surface allocations, causing frame drops on Intel iGPUs.
- Fragment shaders rewritten per-platform fragment the codebase and break easily during upstream rebases.

### Goals
- Direct-to-driver execution without breaking Compose's layout and draw phases.
- Single universal shader Intermediate Representation (IR) targeting SPIR-V, MSL, and GLSL.
- Zero-copy native texture sharing between C++20 / Rust DSP and Compose UI.

---

## 3. The 4-Tier Hardware Capability Matrix

| Tier | Target Hardware | Graphics Backend | Visual Pipeline |
| :--- | :--- | :--- | :--- |
| **Tier 1 (Ultra)** | Android 12+ (64-bit), macOS Apple Silicon, Linux discrete GPU (AMD RADV/NVIDIA), Windows discrete GPU | **Metal 2+ / Vulkan 1.2+** | Full Single-Pass Liquid Glass: Dual-Kawase sampling + Normal-map lens refraction + Chromatic aberration + Fresnel highlights (120Hz locked). |
| **Tier 2 (Balanced)** | Windows Intel iGPUs (HD 520+ / UHD), Android 12 GLES, Linux integrated GPUs | **Direct3D 12 (Windows) / GLES 3.1** | Dual-Kawase 2-pass blur + simplified luminance tone-mapping (60Hz locked). Eliminates Intel Windows Vulkan driver instabilities. |
| **Tier 3 (Legacy)** | Older Android phones (`armeabi-v7a`, API 26–30), Intel HD 3000/4000, Raspberry Pi / Panfrost | **OpenGL ES 2.0/3.0 / D3D11** | Hardware-cached flat translucent acrylic scrim + 1px elevation stroke. **Zero real-time blur passes**, 0% thermal throttling, 60 FPS guaranteed. |
| **Tier 4 (Safe)** | Linux LLVMpipe (VMs/containers), SwiftShader (headless/browser fallback) | **Software / CPU Raster** | Solid / semi-transparent surface tint. All custom shaders bypassed. |

---

## 4. Universal Shader IR: Single-Pass Liquid Glass

Instead of 32-tap Gaussian passes, we deploy a unified **SkSL / AGSL** fragment shader:

```glsl
uniform shader backdrop;
uniform float2 resolution;
uniform float lensRadius;
uniform float refractionIndex; // Acrylic: 1.25
uniform float chromaticSpread; // 0.02
uniform float4 tintColor;

half4 main(float2 coord) {
    float2 uv = coord / resolution;
    float2 p = uv - float2(0.5, 0.5);
    float dist = length(p);

    // Dynamic dome normal approximation
    float3 normal = float3(0.0, 0.0, 1.0);
    if (dist > (0.5 - lensRadius)) {
        float edge = (dist - (0.5 - lensRadius)) / lensRadius;
        normal = normalize(float3(p * edge, 1.0 - edge));
    }

    float2 refractOffset = normal.xy * (refractionIndex - 1.0) * 0.05;

    // Chromatic dispersion sampling
    half r = backdrop.eval(coord + (refractOffset * (1.0 + chromaticSpread)) * resolution).r;
    half g = backdrop.eval(coord + refractOffset * resolution).g;
    half b = backdrop.eval(coord + (refractOffset * (1.0 - chromaticSpread)) * resolution).b;
    half a = backdrop.eval(coord + refractOffset * resolution).a;

    // Schlick Fresnel approximation
    float fresnel = pow(1.0 - max(dot(normal, float3(0.0, 0.0, 1.0)), 0.0), 3.0);
    half4 specular = half4(1.0, 1.0, 1.0, 1.0) * fresnel * 0.35;

    return mix(half4(r, g, b, a), tintColor, tintColor.a) + specular;
}
```

---

## 5. Zero-Copy Driver Interop (Native Compute Bridge)

For high-throughput visualizers (e.g. real-time audio FFT waterfalls, fluid dynamics):
1. **Producer**: Native C++20 / Rust allocates and updates an offscreen `VkImage` (Vulkan) or `id<MTLTexture>` (Metal).
2. **Consumer**: Skia wraps the native handle into `org.jetbrains.skia.BackendTexture` without memory duplication.
3. **Renderer**: Compose draws the texture directly inside a `Canvas` pass.

---

## 6. Implementation Strategy & Upstream Protection

1. **Isolation**: All logic lives in `:streamify-modules:streamify-graphics`.
2. **API Contract**:
   ```kotlin
   fun Modifier.sovereignLiquidGlass(
       backdrop: GraphicsLayer,
       blurRadius: Dp = 16.dp,
       tint: Color = Color.Unspecified
   ): Modifier
   ```
3. **Upstream Safety**: [composeApp/src/commonMain/.../LiquidGlass.kt](file:///data/data/com.termux/files/home/streamify-apk/composeApp/src/commonMain/kotlin/com/maxrave/simpmusic/expect/ui/LiquidGlass.kt) redirects cleanly to `sovereignLiquidGlass`, leaving upstream submodules untouched.

---

## 7. Open Questions & Community Review
- What are recommended heuristics for probing driver stability on Android Vulkan vs GLES on legacy chipsets (Mali vs Adreno vs PowerVR)?
- In WebAssembly (Wasm-JS), should WebGPU be preferred automatically over WebGL 2.0 when `navigator.gpu` is detected, or gated behind feature flags?
- How should Skiko window transparency interact with Wayland fractional scaling under Vulkan surfaces?
