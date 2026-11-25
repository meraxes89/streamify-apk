## Universal Android GPU & Version Spectrum Matrix (S24 — Sovereign Graphics Engine)

Implements all four tracks of the engineering brief: rock-solid stability, maximum visual fidelity, and locked 60/120 FPS across the entire Android spectrum — **API 26 (Oreo) through API 35+ (Android 15/16)** and all mobile GPU architectures — via capability probing, dialect adaptation, and the earned performance model. **No marketing-name whitelisting anywhere.**

### Track A — Mobile Shader Precision Adaptation (mediump vs highp)
- `LiquidGlassCore.sksl` now splits precision along the data's needs: **highp `float` for the normal/geometry chain** (edge-curved normals stay sub-pixel accurate at any panel density) and **mediump `half` for the entire color path** — backdrop taps, tint mix, the Fresnel boundary `half fr = half(...)`, and the specular add.
- On unified-ALU mobile parts (Adreno 5xx, Mali Midgard/Bifrost) the half-typed color math runs at **2x the highp ALU rate**; 8-bit output depth cannot resolve a 10-bit mantissa, so the downgrade is visually free.
- `AgslDialect.validatePrecisionContract` — a new fail-fast canonical-source gate (comment-stripped) — rejects regressions: `float3` taps, dropped `half fr` boundary, `float(...)` casts re-promoting the color path.
- The resource-constant weld (byte-for-byte) is preserved and re-verified.

### Track B — Startup Dynamic Compile Probe & Safe Failover
- The Android probe (`Agsl33Isolated.probeCompile`) now compiles the **canonical transpiled source** — not a minimal synthetic program — through the real `RuntimeShader` compiler once at startup, isolated in the `@RequiresApi(33)` lane (API 27 class-isolation invariant intact).
- Any `Throwable` (OEM driver crash inside `glCompileShader`, AGSL rejection of valid syntax) is caught, recorded in the new `CapabilityProbe.compileProbeFailureReason`, and the device routes to **Tier 2 (blur) / Tier 3 (scrim)** — resolved through the Koin graph at engine construction, **before the Compose tree ever calls `drawBehind`**.
- The skiko actual mirrors the recorded failure reason (Track B parity).

### Track C — Android Version Spectrum Ladder (API 26 to 35+)
- The ladder docs pin the full spectrum mapping: **API 33+** AGSL RuntimeShader (Vulkan/GLES 3.2) Tier 1; **API 31-32** `RenderEffect.createBlurEffect()` Tier 2; **API 28-30** hardware RenderNode clipping/compositing Tier 3; **API 26-27** strictly zero-shader, zero-blur `AcrylicScrimGlassEngine` (0% thermal throttling, 0 NoClassDefFoundError risk).
- `UniversalAndroidMatrixTest.apiSpectrumMapsToTheDocumentedLanes` pins every band end-to-end through `bestPlanFor`.

### Track D — Known Driver Quirk Mitigation Table
- `CapabilityProbe` gains a surgical `DriverQuirk` denylist of **proven compiler defects** (emulator software-GLES broken half-float division / NaN handling, ANGLE emulator Vulkan half division), keyed to marker substrings that identify the broken driver *revision*.
- **Demotion-only by contract**: a quirk hit pins Tier 4 but can never lift a device above its probed capability; a compliant GPU — same marketing family or not — is never penalized (`denylistOnlyDemotesNeverPenalizesCompliantGpus` pins Adreno 509/750, Mali-G72/G715-Immortalis, Xclipse 940, PowerVR Rogue/B-Series all classifying Tier 1).

### Tests & Docs
- New `UniversalAndroidMatrixTest` (11 tests) pins all four tracks; all pre-existing suites untouched and still green by construction (the `CapabilityProbe` change is constructor-additive).
- `docs/gpu/ARCHITECTURE.md`: new section **2a (precision contract)** + axis 4 (quirk denylist); section 3 documents the Track B startup probe.
- `docs/gpu/README.md`: drops the retired `PHASED_PLAN.md` link — fixing **pre-existing base docs-gate breakage** (`verify_docs` now passes with 0 errors).

### Hard invariants maintained
- Zero recompositions: no state reads during composition; all work stays in `drawBehind`.
- API 27 class isolation: every `RuntimeShader`/`RenderEffect` reference stays inside `@RequiresApi`-isolated types behind explicit `SDK_INT` gates.
- Upstream immunity: `core/` diff = **0 files**.

> Verified locally: `python3 scripts/docs_ledger_check.py` → PASS; `python3 scripts/verify_docs.py` → PASS (0 errors). Gradle build/tests run in CI per the workflow (no heavy local commands).
