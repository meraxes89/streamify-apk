# Sovereign GPU Engine — Developer & Extension Playbook

This document is the definitive guide for engineers working with the **Sovereign GPU Engine** (`:streamify-modules:streamify-graphics`). It is partitioned into two dedicated tracks:

1. **[Part 1: For UI & Feature Developers](#part-1-for-ui--feature-developers-how-to-offload-to-gpu)** — How to safely offload rendering to the GPU, eliminate recomposition thrash, and consume existing effects.
2. **[Part 2: For GPU & Systems Engineers](#part-2-for-gpu--systems-engineers-how-to-extend-the-engine)** — How to write new SkSL/AGSL shaders, register pluggable `GraphicsEffect`s, stream zero-allocation `GpuDataTexture` payloads, and calibrate multiplatform drivers.

---

```
                       ARCHITECTURE AT A GLANCE
                       
┌──────────────────────────────────────────────────────────────────┐
│ Compose UI (Feature Devs)                                        │
│   Modifier.streamifyGlass(backdrop, shape, tint)                 │
│   Modifier.streamifyVisualizer(fftSource, style)                 │
└────────────────────────────────┬─────────────────────────────────┘
                                 │ Draws strictly in Draw Phase (0 Recompositions)
                                 ▼
┌──────────────────────────────────────────────────────────────────┐
│ :streamify-modules:streamify-graphics                            │
│   ├── Tokens: PrimitiveTokens → SemanticTokens → ComponentTokens │
│   ├── SPI: GraphicsEffect & GraphicsEffectRegistry               │
│   ├── Streaming: GpuDataTexture (Offscreen Textures < 4 KB)      │
│   └── Governor: TierGovernor.forRefreshRate(probe, hz)           │
│         (Tier 1: Ultra ↔ Tier 2: Balanced ↔ Tier 3: Scrim ↔ Tier 4: Safe)
└────────────────────────────────┬─────────────────────────────────┘
                                 │ Mechanical Transpilation (SkSL ↔ AGSL)
                                 ▼
┌────────────────────────────────┴─────────────────────────────────┐
│ ShaderProvider Multiplatform Adapters                            │
│   ├─ androidMain       → AGSL RuntimeShader (33+) / RenderEffect │
│   └─ skikoCommonMain   → Skia RuntimeShaderBuilder (SkSL)        │
│                          [Desktop JVM, iOS (Metal), Web (WASM)]  │
└──────────────────────────────────────────────────────────────────┘
```

---

# Part 1: For UI & Feature Developers (How to Offload to GPU)

### 1.1 The Golden Rule of Compose GPU Offloading

> [!IMPORTANT]
> **Never read high-frequency state during Composition or Layout phases.**
> If your state updates at 60 Hz or 120 Hz (e.g. playback audio FFT, scroll offsets, gyro, animations), reading `.value` inside a Composable triggers full recomposition of the UI subtree, destroying battery life and dropping frames.

```kotlin
// ❌ WRONG: Reading audio/animation state during Composition forces recomposition every frame!
@Composable
fun BadVisualizer(fftFlow: StateFlow<FloatArray>) {
    val fft by fftFlow.collectAsState() // ⚠️ Recomposes 60-120 times/sec!
    Canvas(Modifier.fillMaxSize()) {
        drawWaveform(fft)
    }
}

// ✅ CORRECT: Capture the state provider; read ONLY inside the Draw Phase!
@Composable
fun GoodVisualizer(fftFlow: StateFlow<FloatArray>) {
    // Zero recompositions during active playback!
    Spacer(
        Modifier
            .fillMaxSize()
            .streamifyVisualizer(
                fftSource = { fftFlow.value }, // Captured lambda; read exclusively in drawBehind
                style = FftDataTexture.STYLE_SPECTRUM_BARS
            )
    )
}
```

---

### 1.2 Using `Modifier.streamifyGlass` (Liquid Glass Refraction)

To create an iOS/visionOS-style frosted glass surface with chromatic dispersion and edge curvature:

1. Capture the background content using `rememberGraphicsLayer()`.
2. Apply `Modifier.drawWithContent { layer.record { drawContent() }; ... }` to the background container.
3. Apply `Modifier.streamifyGlass(backdrop = layer, shape = ...)` to the glass overlay.

```kotlin
@Composable
fun LiquidGlassPlayerBar(
    contentBehind: @Composable () -> Unit,
    playerControls: @Composable () -> Unit,
) {
    val backdropLayer = rememberGraphicsLayer()

    Box(Modifier.fillMaxSize()) {
        // 1. Record the underlying content to an offscreen layer
        Box(
            Modifier
                .fillMaxSize()
                .drawWithContent {
                    backdropLayer.record { this@drawWithContent.drawContent() }
                    drawLayer(backdropLayer)
                }
        ) {
            contentBehind()
        }

        // 2. Render the glass surface over the recorded backdrop
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(ComponentTokens.PLAYER_BAR_HEIGHT)
                .padding(ComponentTokens.SCREEN_EDGE_MARGIN)
                .streamifyGlass(
                    backdrop = backdropLayer,
                    shape = ComponentTokens.PLAYER_BAR_SHAPE,
                    tint = SemanticTokens.TINT_SURFACE
                )
        ) {
            playerControls()
        }
    }
}
```

#### Automatic Graceful Degradation
You do **not** need to write conditional `if (isAndroid8) ... else ...` branches:
- On **Android 13+ (API 33+)** and **Desktop/iOS**: Executes full Tier 1 / Tier 2 AGSL/SkSL normal-map refraction with chromatic dispersion.
- On **Android 12 (API 31-32)**: Automatically executes hardware `RenderEffect` GPU blur passes.
- On **Android 8.1–11 (Vivo 1712 / API 26-30)**: Automatically bypasses shaders and falls back to a hardware-cached translucent acrylic scrim with 1px optical rim.
- On **Headless/Software Renderers (`llvmpipe`, `swiftshader`)**: Automatically falls back to a solid tint.

---

### 1.3 Using 3-Layer Design Tokens

Never hardcode raw RGB values, blur radii, or lens dimensions in feature screens. Always reference the 3-layer design tokens from `com.streamify.graphics.tokens`:

```
PrimitiveTokens (Raw mathematics: IOR 1.25, chromatic spread 0.015, blur sigmas)
      │
      ▼
SemanticTokens (Design intent: TINT_SURFACE, SPECULAR_HIGHLIGHT, TINT_DOCK)
      │
      ▼
ComponentTokens (Component bindings: PLAYER_BAR_SHAPE, MINI_PLAYER_CORNER_RADIUS)
```

---

# Part 2: For GPU & Systems Engineers (How to Extend the Engine)

If you are adding a new visual effect, extending support to a new operating system, or optimizing GPU buffers, follow the procedures below.

---

### 2.1 The Universal Shader Rule: SkSL is the Single Source of Truth

We **never** write separate Vulkan, Metal, Direct3D, or AGSL shaders. 
- **SkSL (Skia Shading Language)** is our universal intermediate representation.
- Skia compiles SkSL directly to Vulkan SPIR-V, Metal MSL, Direct3D HLSL, or WebGL GLSL at runtime.
- For Android HWUI, our `AgslDialect` transpiler converts SkSL to AGSL automatically.

#### AGSL Dialect Constraints (Strict Invariants)
When writing canonical SkSL, you must adhere to these rules so `AgslDialect` can transpile it for Android:
1. **Entry Point Signature:** Must strictly be `half4 main(float2 coord)`.
2. **No Fragment Coordinates Built-in:** Never use `sk_FragCoord`. Use the entry point `coord` parameter instead.
3. **No GLSL Built-ins:** Never use `gl_*` identifiers.
4. **Color Uniforms:** Declare color uniforms as `uniform half4 tintColor;` (the transpiler promotes them to `float4` for Android's `setFloatUniform`).

---

### 2.2 Adding a New Visual Effect (Pluggable `GraphicsEffect` SPI)

To add a new shader (e.g. `VinylReflectionEffect`, `LyricsGlowEffect`):

#### Step 1: Create the Effect Definition
Implement `GraphicsEffect`:

```kotlin
package com.streamify.graphics.effect

import com.streamify.graphics.governor.RenderTier

object VinylReflectionEffect : GraphicsEffect {
    override val id: String = "com.streamify.effect.vinyl_reflection"
    override val name: String = "Vinyl Disc Anisotropic Reflection"

    // Canonical SkSL program
    override val canonicalSkSl: String = """
        uniform float2 uResolution;
        uniform float  uAngle;
        uniform half4  uSpecularColor;

        half4 main(float2 coord) {
            float2 uv = (coord - 0.5 * uResolution) / min(uResolution.x, uResolution.y);
            float r = length(uv);
            float theta = atan(uv.y, uv.x) + uAngle;
            
            // Anisotropic metallic groove reflection
            float highlight = pow(abs(sin(theta * 2.0)), 16.0) * smoothstep(0.9, 0.2, r);
            return uSpecularColor * highlight;
        }
    """.trimIndent()

    override val minTier: RenderTier = RenderTier.Tier2_Balanced

    override val uniforms: List<EffectUniform> = listOf(
        EffectUniform("uResolution", UniformType.FLOAT2, "Surface resolution"),
        EffectUniform("uAngle", UniformType.FLOAT, "Rotation angle in radians"),
        EffectUniform("uSpecularColor", UniformType.FLOAT4, "Specular sheen tint")
    )
}
```

#### Step 2: Register in `GraphicsEffectRegistry`
```kotlin
GraphicsEffectRegistry.register(VinylReflectionEffect)
```

The effect is now queryable by any platform adapter, validated for AGSL compliance, and verified against the dialect transpile test suite automatically.

---

### 2.3 Streaming Data to the GPU (`GpuDataTexture`)

When an effect requires streaming dynamic data from C++, Rust, or DSP into a fragment shader:

> [!CAUTION]
> Never allocate arrays inside the render loop! Pre-allocate the staging payload once at construction.

1. Implement `GpuDataTexture`:
   - Keep `payload: ByteArray` fixed-size ($< 4\text{ KB}$ for 1D streams).
   - Use row stride: `width * 4` (RGBA_8888).
2. Pack data into the `R` channel (quantized $0..255 \leftrightarrow 0.0..1.0$).
3. Increment `frameStamp` so the platform adapter only uploads dirty frames.

```kotlin
class WaveformDataTexture(val sampleCount: Int = 512) : GpuDataTexture {
    override val textureWidth: Int = sampleCount
    override val textureHeight: Int = 1
    override val rowBytes: Int = sampleCount * 4
    override val payload: ByteArray = ByteArray(rowBytes)
    override var frameStamp: Long = 0L
        private set

    fun update(samples: FloatArray) {
        var offset = 0
        for (i in 0 until sampleCount) {
            val q = ((samples[i].coerceIn(-1f, 1f) * 0.5f + 0.5f) * 255f).toInt()
            payload[offset] = q.toByte()     // R: normalized audio amplitude
            payload[offset + 1] = 0           // G: reserved
            payload[offset + 2] = 0           // B: reserved
            payload[offset + 3] = 255.toByte()// A: opaque
            offset += 4
        }
        frameStamp++
    }

    override fun clear() {
        payload.fill(0)
        frameStamp++
    }
}
```

In the fragment shader, sample the texel center:
```glsl
uniform shader uWaveformTexture;

float sampleAmplitude(float normalizedX, float sampleCount) {
    // Texel center sampling: (index + 0.5, 0.5)
    float px = (normalizedX * sampleCount) + 0.5;
    return uWaveformTexture.eval(float2(px, 0.5)).r * 2.0 - 1.0;
}
```

---

### 2.4 Calibrating the `TierGovernor` for New Hardware & Displays

The `TierGovernor` dynamically protects frame rates and prevents thermal throttling. When introducing a target with a different display panel:

```kotlin
// Instantiate with display-calibrated VSync parameters:
val governor = TierGovernor.forRefreshRate(
    probe = ShaderProvider.probe(),
    refreshRateHz = 120.0 // Automatically derives fastBudget = 4.16ms, P95 budget = 8.33ms
)
```

#### Governor Tuning Invariants
- **Cold Start:** Starts conservatively at Tier 2 (`Tier2_Balanced`).
- **Promotion to Tier 1:** Requires **120 consecutive frames** rendered strictly under `fastFrameBudgetMs` while thermal status is `NOMINAL`.
- **Demotion to Tier 3:**
  - Synchronous on `ThermalStatus.MODERATE` or worse.
  - Automatic if the rolling 60-frame window P95 exceeds `p95FrameBudgetMs` by more than 3 spikes.
- **Hysteresis:** Any tier transition enforces a **300-frame cooldown** before any promotion is considered, preventing visual flickering.

---

### 2.5 Adding Driver Quirk Workarounds

Different GPU drivers (ANGLE on Windows, Mali on budget Androids, Mesa WebGL) exhibit vendor-specific edge cases. Handle quirks by inspecting `CapabilityProbe`:

```kotlin
val isAngleD3D = probe.rendererDescription.contains("ANGLE", ignoreCase = true)
val isAdrenoLegacy = probe.rendererDescription.contains("Adreno (TM) 5", ignoreCase = true)

if (isAngleD3D) {
    // Ensure uniform buffers align to 16-byte boundaries for Direct3D constant buffers
}
```

---

### 2.6 Golden Test Verification Suite

Whenever you add or touch a shader or adapter:

1. **Verify Dialect Compatibility:**
   `./gradlew :streamify-modules:streamify-graphics:testDesktopUnitTest`
2. **Headless Offscreen Golden Test (`OffscreenRenderGoldenTest.kt`):**
   Renders the shader to a headless Skia raster surface (`Surface.makeRasterN32Premul`), asserts non-zero pixel evaluation, and checks that chromatic dispersion produces measurable channel divergence.
3. **Shader Failure Fallback Test (`ShaderFailureFallbackTest.kt`):**
   Asserts that syntax errors or unsupported driver probes drop gracefully to Tier 4 without throwing or crashing.

---

## 3. Summary Checklist for Pull Requests

Before opening a PR on `integration/sovereign-graphics`:

- [ ] SkSL shader compiles through Skia `RuntimeEffect.makeForShader()`.
- [ ] Shader passes `AgslDialect.validateAgslCompatible()`.
- [ ] No state read during Compose Composition phase (only inside `drawBehind`).
- [ ] Dynamic textures implement `GpuDataTexture` with 0 allocations in `update()`.
- [ ] Unit tests added in `desktopTest/.../`.
- [ ] All tiers (Tier 1 through Tier 4) have verified fallback execution plans.
