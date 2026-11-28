---
name: sovereign-graphics
description: Author, audit, and optimize SkSL/AGSL shaders, Compose draw-phase modifiers, TierGovernor capability lanes, and headless Skia golden tests in streamify-modules/streamify-graphics.
---

# Sovereign Graphics & Shader Engineering Skill

Use this skill when modifying or adding shaders, `GraphicsEffect` plugins, `TierGovernor` rules, or Compose graphics modifiers in `streamify-modules/streamify-graphics/`.

## Hard Invariants (Never Violate)

1. **Draw-Phase Confinement (Zero Recomposition, Zero Per-Frame Allocation)**
   - Public facades (`Modifier.streamifyGlass`, `Modifier.streamifyVisualizer`, `Modifier.streamifyEffect`) must be implemented strictly in `drawBehind` / `graphicsLayer`.
   - Build request objects (`GlassRenderRequest`, `EffectRenderRequest`) once per composition.
   - Pass dynamic inputs (time, luminance, FFT arrays, pointer coordinates) as lambdas (`() -> Float`, `() -> FloatArray`, `EffectUniformSlot`) and invoke them **only inside the draw pass**.
   - Never call `RuntimeShader(...)`, `RuntimeEffect.makeForShader(...)`, `Paint()`, or `Path()` in a `@Composable` body without caching/memoization.

2. **Canonical SkSL ↔ AGSL Common Subset**
   - Author every shader once in canonical SkSL (`GraphicsEffect.canonicalSkSl` or `shaders/*.sksl`).
   - `AgslDialect.transpile` converts SkSL to Android AGSL; every new effect **must** pass `validateAgslCompatibility()` and `DialectTranspileTest`.
   - Avoid dynamic branching (`if/else` in hot pixel loops)—prefer `mix()`, `step()`, `smoothstep()`, and `clamp()` to prevent GPU thread divergence.
   - Keep texture/child `.eval()` taps minimal; respect TBDR tile-cache limits on Apple Silicon (Retina 2x/3x uses `DynamicResolutionOffscreenPass`).

3. **API 27 Class-Isolation & Graceful Degradation**
   - Never reference `android.graphics.RuntimeShader` (API 33+) or `android.graphics.RenderEffect` (API 31+) outside `@RequiresApi`-annotated isolated classes behind explicit `Build.VERSION.SDK_INT` gates.
   - Every shader path must degrade cleanly to Tier 3 (acrylic scrim + 1px specular rim) or Tier 4 (solid tint / `fallbackColor`) on compile failure, software rasterizers (`SwiftShader`, `llvmpipe`), or unsupported tiers—**never throw on the draw thread**.

4. **Pluggable Effect Workflow**
   - Implement `GraphicsEffect` (`id`, `name`, `canonicalSkSl`, `minTier`, `uniforms`), register it in `GraphicsEffectRegistry`, and invoke it via `Modifier.streamifyEffect(effectId, ...)`.
   - Update `CHANGELOG.md` and `docs/systems/S24-sovereign-graphics.md` when changing public behavior, and verify `python3 scripts/verify_docs.py`.
