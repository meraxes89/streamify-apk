# Sovereign GPU Engine (`docs/gpu/`)

## Executive Summary

The Sovereign GPU Engine (`:streamify-modules:streamify-graphics`) is an upstream-isolated, hardware-adaptive graphics subsystem for Compose Multiplatform (CMP). It offloads heavy rendering tasks—specifically **Liquid Glass / Glassmorphism**, normal-map refraction shaders, and real-time audio visualizers—directly to native GPU drivers (**Vulkan, Metal, Direct3D 11 via ANGLE, WebGL 2.0**) by riding Skia and Android HWUI's internal compilation pipeline.

Crucially, it guarantees rock-solid stability and locked 60 FPS on low-end hardware (such as the **Vivo 1712 / Android 8.1 API 27**) through an automatic **4-Tier Hardware Capability Matrix**, and covers the full universal Android spectrum — API 26 (Oreo) through API 35+ (Android 15/16) and every mobile GPU architecture (Adreno 3xx–8xx, Mali Midgard through Immortalis, Xclipse, Dimensity/Helio, PowerVR Rogue/B-Series, and software rasters) — through capability probing, dialect adaptation, and the earned performance model, never marketing-name whitelisting.

---

## Architecture Navigation

| Document | Description |
|---|---|
| [DEVELOPER_GUIDE.md](DEVELOPER_GUIDE.md) | **Developer & Extension Playbook**: Practical guide for UI devs to offload rendering to the GPU and GPU devs to extend shaders, hardware, and SPI effects. |
| [ARCHITECTURE.md](ARCHITECTURE.md) | Full technical specification: Skia/HWUI pipeline, SkSL universal IR (with the precision contract), 4-tier capability matrix, and zero-allocation FFT data-texture visualizer. |
| [CI_TESTING_SPEC.md](CI_TESTING_SPEC.md) | Automated testing specification triggered when GPU files change: software-raster tests (Tier 4), headless Skia tests, API 27 emulation, and shader-failover gates. |
| [parity/](parity/) | **S24 Kyant optical parity evidence**: side-by-side golden renders (Kyant reference / S24 / pre-upgrade) across light, dark and saturated backdrops. |

The Phase 1–4 roadmap (`PHASED_PLAN.md`) was retired with the plan's completion; the phase history lives in the integration branch's merged PRs.

---

## 4-Tier Hardware Capability Matrix

| Tier | Name | Target Environments | Shader / Render Strategy | Performance Target |
|---|---|---|---|---|
| **Tier 1** | **Ultra** | Modern Android (API 33+, Vulkan), Apple Metal 2+, Desktop dGPU | Single-pass Dual-Kawase blur + 3-tap chromatic dispersion + normal-map refraction + directional 45° specular rim + 1.5× vibrancy + adaptive "đục đen" scrim (Kyant parity, S24) | 120 FPS ($P_{95} < 8.3\text{ ms}$) |
| **Tier 2** | **Balanced** | Android 12–13 (API 31–32), Windows Intel HD 520+, WebGL2 | Skia 2-pass Gaussian blur + single-tap lens refraction (no chromatic spread) | 60 FPS ($P_{95} < 16.6\text{ ms}$) |
| **Tier 3** | **Legacy** | **Vivo 1712 (Android 8.1 / API 27)**, legacy `armeabi-v7a`, weak iGPUs | **Zero blur passes**: Hardware-cached translucent acrylic scrim + dual-stop directional 45° rim stroke + adaptive scrim floor (S24 Kyant-parity stand-ins) | 60 FPS (0% thermal throttling) |
| **Tier 4** | **Safe** | Software rasters (SwiftShader, LLVMpipe, headless CI), shader compile fail | Solid translucent tint, all shader pipelines bypassed | Rock-solid failover |

---

## Branching & PR Workflow

```
[ Engineer 1 ] (Systems/Shaders)   ─── PR (Phase X-A) ──┐
                                                         ├──> [ integration/sovereign-graphics ] ─── Final PR ───> [ main ]
[ Engineer 2 ] (CMP UI/Tokens/CI)  ─── PR (Phase X-B) ──┘
```

1. **Integration Branch**: All feature branches target `integration/sovereign-graphics` (matching `.github/workflows/ci.yml` `integration/**` pattern).
2. **Two-Engineer Partition**: Work in each phase is decoupled cleanly into Track A (Engineer 1) and Track B (Engineer 2) to eliminate merge collisions.
3. **Promotion to Main**: Only after all 4 phases pass CI tests on `integration/sovereign-graphics` is the final consolidated PR opened against `main`.
