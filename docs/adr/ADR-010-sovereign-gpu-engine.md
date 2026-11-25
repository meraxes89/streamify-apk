# ADR-010: Sovereign GPU Engine capability matrix over Skia

* Status: Accepted
* Date: 2026-11-25
* Verified against: a0d66af2075840431e939de1f286a4cf16f0b07b (see the staleness protocol in [README.md](README.md))

## Context

The Compose Multiplatform app must render Liquid Glass (blur + refraction +
dispersion) across an extreme hardware spectrum: an iPhone 7-class A10 and an
M-series MacBook Pro both run the same `skikoCommonMain` Skia pipeline. On
Apple Silicon four measured forces dominate: tile-based deferred rendering
makes full-resolution Retina 3x backdrop passes flush on-chip tile memory to
RAM (~3.6M pixels per pass); ProMotion shifts the display link 10–120 Hz so a
static 16.6 ms budget is wrong in both directions; passively cooled iPhones
throttle (`NSProcessInfo.thermalState`) and users enable Low Power Mode;
headless macOS CI and iOS Simulators crash on assumptions of live GPU
framebuffers.

Alternatives rejected: raw out-of-band Metal contexts blitted back into the
Compose tree (memory barriers, CPU readback stalls, driver crashes — the
foundational principle of [docs/gpu/ARCHITECTURE.md](../gpu/ARCHITECTURE.md)
§1); GPU marketing-name whitelists ("A13 or better gets Tier 1") — brittle,
unenforceable in CI, and blind to thermal/runtime state; a static
per-platform tier table — cannot react to ProMotion cadence or throttling.

## Decision

Ride Skia's Metal backend, and govern it with a reactive 4-tier state
machine evaluated strictly on two axes — **API/driver compilation capability**
(Skia `RuntimeEffect` validation on Apple targets) and **rasterizer/thermal
state** — never on marketing names:

1. **Thermal ladder (synchronous demotion):** `NSProcessInfo.thermalState`
   `serious` → `MODERATE` → Tier 3; `critical` → `CRITICAL` → Tier 4; the
   same ladder serves Android `PowerManager` statuses. Delivered through an
   `NSNotificationCenter` bridge attached from the Koin graph (S24 Track A).
2. **Low Power Mode:** caps the promotion ceiling at Tier 2, demotes a live
   Tier 1 immediately, pins budgets to the 60 Hz lane.
3. **ProMotion budgeting:** the governor calibrates fast/P95 budgets to the
   real display cadence at cold start (`UIScreen.maximumFramesPerSecond`)
   and recalibrates on cadence shifts; P95 violations demote **one tier at a
   time** so a 120→90 FPS drop lands on the single-tap shader, not the scrim
   (S24 Track B).
4. **TBDR-friendly DRS:** the Tier 2 engine downsamples its backdrop
   capture/refraction pass to logical 1x on Retina-class densities
   (0.5×/0.33×) and upscales with hardware bilinear filtering — ~75% less
   tile-flush traffic; Tier 1 keeps earned full resolution (S24 Track C).
5. **Construction-time pipeline validation + environment detection:** the
   program materializes one shader (child-bound) at construction so Metal
   pipeline-state errors degrade to the acrylic scrim lane before any draw;
   headless/simulator environments are detected as diagnostics, not
   (incorrectly) as software rasterizers (S24 Track D).

The full contract, decision tables and file map live in
[docs/systems/S24-sovereign-graphics.md](../systems/S24-sovereign-graphics.md).

## Consequences

* Positive: one governor contract spans Android + Apple + desktop + wasm;
  battery/thermal behavior is native on iOS (real `NSProcessInfo`
  observation, synchronous demotion); visual continuity improves (stepped
  P95 demotion instead of hard scrim drops); Retina 3x passes cost ~1/9th
  the bandwidth at Tier 2.
* Negative: the common `ThermalStatus` enum is wider than Apple's four
  states (SEVERE/EMERGENCY serve Android); the DRS pass's per-frame
  snapshot is a bounded native allocation at 1/9 area (documented cost
  model — no Kotlin-heap churn, no recomposition); live backdrop snapshots
  still flow through the documented suspend seam (the DRS machinery is the
  consumer, the facade wiring is follow-up work).
* Required follow-up: the facade-level offscreen snapshot pipeline that
  feeds `bindBackdrop` on device; an Android `DisplayManager` reporter for
  real cadence; wasm `devicePixelRatio` density reporting.

## Compliance

* `docs/systems/S24-sovereign-graphics.md` is the contract home; any
  contract change must update it and pass `scripts/docs_ledger_check.py`
  and `scripts/verify_docs.py` (the Streamify documentation contract).
* CI: `docs/gpu/CI_TESTING_SPEC.md` Job 1 (governor state machine) pins the
  transition contract on the desktop JVM lane; the graphics spoke compiles
  `iosArm64`/`iosSimulatorArm64` klibs on every PR and the macOS lane runs
  `iosSimulatorArm64Test` (thermal-constant weld + bridge smoke).
* The 2-axis invariant (no marketing names) is enforced by review:
  `CapabilityProbe`/`BackdropResolutionPolicy` are pure classifiers whose
  inputs are compile capability, raster kind, live tier, density and
  thermal state.
