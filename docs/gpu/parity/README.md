# Kyant Optical Parity — Side-by-Side Evidence (S24)

Three-tile comparisons of the Sovereign GPU Engine's `LiquidGlassCore`
against the Kyant optical reference, one per backdrop class:

| Image | Backdrop | Left → Right tiles |
|---|---|---|
| [kyant_parity_light.png](kyant_parity_light.png) | Bright (near-white gradient) | Kyant reference → S24 upgraded → S24 pre-upgrade |
| [kyant_parity_dark.png](kyant_parity_dark.png) | Dark (near-black gradient) | Kyant reference → S24 upgraded → S24 pre-upgrade |
| [kyant_parity_saturated.png](kyant_parity_saturated.png) | Colorful (magenta → blue) | Kyant reference → S24 upgraded → S24 pre-upgrade |

## How to read them

* **Left vs middle** — the Kyant reference vs the S24 shader. These are the
  parity pair; they must be visually indistinguishable. The pre-rendered
  pair below is generated from a pure-Python port of both pipelines, so
  the tiles are literally identical here (MSE 0); the authoritative gate
  is the real-SkSL render (see below), whose budget is MSE < 5e-4.
* **Right** — the pre-S24 shader (uniform omnidirectional Fresnel rim, no
  vibrancy, no adaptive scrim). Over the bright backdrop it is the milky
  washed-out pane the upgrade eliminates (measured MSE vs Kyant: 0.225 on
  light, 0.017 on dark, 0.028 on saturated); over saturated artwork it
  loses the chroma the 1.5x vibrancy pillar now preserves.

## Provenance

* **These PNGs** are rendered by [scripts/kyant_parity_preview.py](../../../scripts/kyant_parity_preview.py)
  — a stdlib-only Python port of (a) the five-pillar math pinned by
  `composeApp/.../ui/component/LiquidGlassContainer.kt` and (b) the
  canonical `LiquidGlassCore.sksl` (plus the pre-S24 revision for the
  "before" column). Regenerate with
  `python3 scripts/kyant_parity_preview.py`.
* **The authoritative parity gate** is
  `streamify-modules/streamify-graphics/src/desktopTest/kotlin/com/streamify/graphics/engine/KyantParityGoldenTest.kt`:
  it renders the actual canonical SkSL through Skia `RuntimeEffect` on the
  headless raster backend, side-by-side with an independent straight-float
  Kotlin reference model, and asserts RGB MSE < 5e-4 (fp16/quantization
  noise budget) across all three backdrop classes, plus the exact
  `lerp(0.12, 0.50, clamp((luminance - 0.3) / 0.5))` "đục đen" ramp.
* **CI artifacts** — with `STREAMIFY_GOLDEN_DUMP=1` (set in
  `_ci-graphics.yml`), the same triptychs rendered from the REAL shader are
  uploaded from every graphics CI run under the `kyant-parity-bitmaps`
  artifact, so PR reviewers always have fresh, pipeline-true evidence.
