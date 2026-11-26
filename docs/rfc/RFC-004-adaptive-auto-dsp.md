# RFC 004: Adaptive Auto-DSP Reference Chain

**Status**: Draft. See the [RFC index](README.md).  
**RFC ID**: 004  
**Tracking Issue**: [#357](https://github.com/meraxes89/streamify-apk/issues/357)  
**Authors**: Core engineering / audio  
**Scope**: [S08](../systems/S08-dsp-and-acoustics.md) (DSP & acoustics), S06 (playback), S07 (desktop playback), S09 (audio output / HAL)  
**Target Modules**: a new downstream module under `streamify-modules/` plus wiring in `androidApp/`, `composeApp/` and `desktopApp/`; **no `core/` edits and no native DSP core**

---

## 1. Executive Summary

This RFC proposes an **adaptive auto-DSP reference chain**: a control layer that measures the track and the playback context, then chooses DSP parameters automatically so that playback is consistent, clear and effortless without the user touching an EQ slider. The chain is built from established, conservative algorithms — BS.1770-4/EBU R128 loudness and true peak, ISO 226-derived level compensation, constrained EQ fitting to a known target, dynamic EQ, crossfeed, and (optionally) measurement-based FIR correction — composed around the Kotlin DSP primitives Streamify already ships.

The goal is **transparency, not coloration**: the chain should deliver the mastering intent at the listener's actual transducer and volume. It explicitly does not re-introduce the retired native DSP core ([ADR-009](../adr/ADR-009-kotlin-only-audio-pipeline.md)).

---

## 2. Motivation & Current Limitations

### 2.1 What Streamify already has

A read-only survey of the shipped pipeline (authoritative page: [S08](../systems/S08-dsp-and-acoustics.md)) found strong primitives:

- 10-band RBJ peaking EQ (Q = 1.41) with preamp, identity-at-0 dB and history-preserving coefficient updates.
- f64 **partitioned FFT convolution** reverb (4096/8192, Hermitian half-spectrum) with ffmpeg `afir` parity to ~1e-7.
- `aecho`-bit-exact feedforward tapped delay, and an equal-power crossfade.
- A Brown & Duda **spherical-head 8D binaural** effect with Catmull–Rom fractional delay.
- AutoEq curve parsing, waveform/RMS extraction and offline tempo/section analysis (`TrackStructureAnalyzer`).

### 2.2 What is missing

Those stages are *user-configurable*; nothing chooses their parameters from the signal or the playback context. S08 also explicitly does **not** own loudness metering, dynamics, limiting, sample-rate conversion or headphone calibration. The historical native layer (BS.1770-4 LUFS, 4×-oversampled true-peak limiter, KissFFT STFT) is lineage only and is not shipped.

A comparison against Felicity's native NEON engine showed the complementary set: a real compressor/limiter, PEQ with shelves, saturation, stereo widening and M/S panning — and, like Streamify, **no automatic control layer**. Neither project currently turns "elite algorithms" into an automatic, measurement-driven experience.

### 2.3 Why an RFC and not an ADR

This spans multiple subsystems, has materially different implementation options (Section 5), and needs an explicit rejection on the record before any dependency, profile-storage or native-core decision is made. Per the [documentation contract](../README.md), an accepted RFC must be followed by a new ADR; no accepted ADR is edited.

---

## 3. Goals and Non-Goals

### Goals

- **G1** — Consistent perceived level across tracks and devices without crushing dynamics.
- **G2** — Output that cannot clip at the DAC or after lossy encoding (true peak).
- **G3** — Spectral balance that stays correct as playback volume changes.
- **G4** — An opt-in, short calibration flow that adapts correction to the listener's headphones/speakers.
- **G5** — Every automatic decision is explainable, bounded, and reversible via a blind A/B against bypass.
- **G6** — Android/desktop parity within each tier, using the existing ffmpeg-graph path on desktop where appropriate.

### Non-goals

- N1 — Re-introducing `rust/` or the C++20 DSP layer (`ADR-009` stands).
- N2 — Editing the upstream `core/` submodule from this repository.
- N3 — End-to-end neural audio generation on the real-time playback path.
- N4 — Paywalls, ads, or any change that weakens accessibility, platform parity or CI gating.
- N5 — Re-mastering the track: no aggressive "exciter" or loudness-war behavior.

---

## 4. Proposed Architecture

### 4.1 Control loop (the actual proposal)

```
measure → target → solve (constrained) → smooth/apply → verify → adapt
```

1. **Measure** — per-track: integrated/short-term LUFS, true peak, crest factor, spectral tilt, low-band energy, presence of a center vocal. Per-context: output device class (headphone / speaker / BT / USB DAC) and actual playback volume.
2. **Target** — a base curve (Harman Over-Ear 2018 / diffuse-field / house curve) plus gentle track adaptation and an ISO 226-derived level compensation curve.
3. **Solve** — fit a small set of biquads and/or an FIR under hard constraints (Section 4.3). Never fill a deep null; cap boost; cap Q; keep latency within budget.
4. **Smooth/apply** — coefficient interpolation across callbacks (no zipper noise, no clicks), reusing the existing history-preserving EQ updates.
5. **Verify** — predict the post-filter response and loudness; back off if it overshoots the target. Offer blind A/B against bypass.
6. **Adapt** — slow, bounded updates from playback telemetry (e.g. repeated manual volume-down events), never open-ended self-tuning.

### 4.2 Layer stack

| Layer | Algorithm (established reference) | Role | Constraint |
|---|---|---|---|
| L0 Analysis | ITU-R BS.1770-4 / EBU R128 (K-weighting, gated LUFS, 4×-oversampled true peak); ReplayGain 2 as fallback metadata | Measure level, peaks, crest factor | Meter conformance vectors; no per-frame allocation |
| L1 Level & dynamics | True-peak limiter (4× oversampling, lookahead, soft knee, adaptive release); gentle gain-riding; transient-aware detector | Consistent loudness, no clipping | −1 dBTP ceiling; preserve dynamics; no stacked limiters |
| L2 Tonal | AutoEq / Harman target; REW-style ⅓-octave / psychoacoustic smoothing; **dynamic EQ** per band; adaptive loudness shelf from ISO 226 equal-loudness contours | Correct tonal balance and level-dependent bass loss | ≤ +6 dB boost; Q ≤ 4 above Schroeder; no deep-null boosting |
| L3 Spatial | Crossfeed (Bauer / AMB / Linkwitz / Resh); headphone-compensated HRTF using the existing Brown & Duda 8D kernel; constant-power M/S width | Stable, non-fatiguing soundstage | Headphones only; maintain center coherence |
| L4 Bass / room | Linkwitz–Riley crossovers, delay/phase alignment, mixed-phase FIR with regularized inversion (Dirac Live class) for measured rigs | Tight, phase-aligned low end | Pre-ring budget; latency budget TBD in Phase 0 |
| L5 Source-aware *(later)* | BS-RoFormer / Mel-Band RoFormer / Demucs stems; RNNoise-class denoise; ML parameter selection over a conventional chain (SONE class) | Vocal clarity, restoration, auto-mastering | Offline/prefetched only; licensed weights; separate RFC if a dependency is added |

### 4.3 Safety constraints (non-negotiable)

- Never boost a measured cancellation/null: detect it (low inter-position coherence or very high Q notch) and leave it untouched.
- All corrections are **minimum-phase by default** above the Schroeder frequency; any mixed-phase/FIR correction in the bass is regularized and checked for pre-ring.
- Fixed ceilings on per-band gain, Q, total gain, limiter ceiling (−1 dBTP) and adaptive-loudness excursion.
- Coefficient changes are interpolated; no hard switch mid-buffer.
- A bypass path always exists, and measurement/profile data never leaves the device without explicit opt-in.

### 4.4 Tier 0 and Tier 1

- **Tier 0 — content-adaptive, zero input (default):** L0–L3 with conservative fixed targets. Applies to every user, no setup step.
- **Tier 1 — listener-calibrated, opt-in:** a ~60-second swept-sine/tone measurement produces a device-local response profile; a constrained fit produces target EQ and, where the latency budget allows, an FIR for the existing `PartitionedConvolver`. Profiles are keyed by output device and measurement conditions.
- L4–L5 are follow-on work, each gated by its own RFC/dependency rationale.

### 4.5 Module placement and boundaries

- New code lives in a downstream module (provisional name `streamify-modules/auto-dsp`) following the existing non-invasive module pattern in `settings.gradle.kts`; no `core/` edits.
- The module exposes: an analysis API (features + loudness/true peak), a bounded `DspPlan` model (target, filters, constraints), a profile store interface, and Media3 `AudioProcessor` implementations for the apply stages.
- `androidApp`/`composeApp` own the settings UI, calibration flow and output-device detection; `desktopApp` maps the same `DspPlan` to ffmpeg filter graphs (extending `MpvEffectGraphs`) where parity is achievable.
- Existing upstream primitives are *composed*, not forked. If an upstream API is genuinely missing, that addition is a separate upstream change — not a `core/` edit in this repo.

### 4.6 Data model (sketch)

```kotlin
data class Measurement(
    val deviceKey: String,
    val sampleRateHz: Int,
    val responseDb: FloatArray,   // smoothed, 1/3-octave or psychoacoustic
    val coherence: FloatArray,    // null / reliability estimate
    val capturedAt: Long,
)

data class DspPlan(
    val targetCurveId: String,
    val bands: List<BandFilter>,  // gainHz, gainDb, q, type
    val fir: FloatArray?,         // optional, measured correction
    val loudnessTargetLufs: Float,
    val truePeakCeilingDbtp: Float,
    val adaptiveLoudnessDb: Float,
    val crossfeedEnabled: Boolean,
    val constraints: Constraints,
)
```

Profiles are device-local by default. Cloud sync of a response curve would be a new contract and requires its own ADR plus an explicit privacy decision.

---

## 5. Options Considered

| Option | Summary | Pros | Cons / Decision |
|---|---|---|---|
| **A (proposed)** | Kotlin/downstream auto-DSP control layer composing existing S08 primitives, Tier 0 first | No `core/` edits; respects ADR-009; testable; incremental; ffmpeg parity path | Needs careful constraint tuning; no measured calibration until Tier 1 |
| **B** | Re-introduce the native Rust/C++ DSP core for "elite" quality | NEON speed; access to historical limiter/LUFS code | Directly reverses ADR-009; JNI parity debt; needs its own RFC + ADR; rejected for now |
| **C** | Static presets / AutoEq import only | Almost no work; user already has 10-band EQ | Not "auto"; does not address level, dynamics, spatial or calibration |
| **D** | End-to-end neural enhancement model on the playback path | Potentially dramatic cleanup | Latency/battery/weights/licensing; opaque and untestable; rejected for real time, deferred to L5 offline |
| **E** | Do nothing beyond the current customisable EQ | No risk | Leaves the largest perceptual wins on the table; does not meet the issue's goal |

---

## 6. Migration Plan

1. **Phase 0 — decision & harness.** Accept/reject this RFC; define latency/battery/CPU budgets and measurement method; add conformance fixtures (EBU R128 test signals, true-peak oversampled vectors, sample response curves). No runtime changes.
2. **Phase 1 — Tier 0.** Implement L0–L2 analysis + level/loudness/limiter + dynamic EQ + crossfeed behind a feature flag; wire settings and output-device detection; update S08 page + pin; add golden-vector and A/B tests.
3. **Phase 2 — Tier 1.** Calibration UI (sweep/tone measurement), profile store, constrained target fit, optional FIR through the existing convolver; new ADR for profile storage/privacy if any sync is proposed; desktop graph parity.
4. **Phase 3 — optional L4/L5.** Bass/phase management and offline source-aware processing, each with its own RFC and dependency rationale.

Per repo rules, behaviour changes update the Sxx page and pin in the same PR; shipped behaviour gets a `CHANGELOG.md` entry; tests ship with every logic change. **No build or benchmark is claimed in this RFC** — nothing has been implemented or run.

---

## 7. Verification & Acceptance Gates

- LUFS meter conforms to EBU R128 / BS.1770-4 conformance vectors; true peak measured at 4× oversampling stays ≤ −1 dBTP on all fixtures.
- Filter-fit error below an agreed threshold on measured response fixtures, with zero boosts into declared nulls and no Q/latency budget violation.
- Blind A/B against bypass: listeners show no preference regression and no fatigue increase; explicitly test well-mastered tracks to catch over-processing.
- Latency and CPU/battery budgets met on a **physical low-tier ARM device** (NEON paths are not available under x86 emulation per `CONTRIBUTING.md`).
- No new dependency without documented rationale; no `core/` edits; docs integrity scripts pass; CI gates unchanged.

---

## 8. Risks & Mitigations

| Risk | Mitigation |
|---|---|
| Over-processing makes good masters worse | Conservative targets, hard ceilings, blind A/B against bypass, per-stage enable/disable |
| FIR pre-ring / phase smear | Minimum-phase default; regularized mixed-phase only in bass; pre-ring checks before apply |
| Phone microphone calibration inaccurate | Opt-in measurement, reliability estimates, no correction where coherence is low; local-only profiles |
| Streaming services already normalize | Gentle gain-riding with dynamic-range preservation; no stacked limiters; respect lossless/normalized paths |
| Battery/CPU cost on low-tier devices | Tier budgets, analysis amortized over buffers, no per-frame allocation, benchmark gate before merge |
| Native-core temptation | ADR-009 stands; Option B explicitly rejected here and needs a new RFC + ADR |

---

## 9. Open Questions

1. Default Tier 1 target: Harman Over-Ear 2018, diffuse-field, or a user-selectable house curve — and how do we handle speakers vs headphones?
2. Should response profiles stay strictly device-local, or is opt-in sync acceptable? (If synced: new contract + ADR.)
3. What is the exact FIR latency budget on Android and desktop, and does Tier 1 fall back to PEQ when exceeded?
4. Can the existing upstream `core/media/media3` primitives be consumed as-is from a downstream module, or is a small public API addition needed upstream?
5. How do we detect output device class reliably across Android, desktop, iOS and Wasm (S09 capability probes vs platform APIs)?
6. Should dynamics compression be reintroduced at all in Kotlin after ADR-009 removed it, and if so with what transparency gate?

---

## 10. References (background, not commitments)

- ITU-R BS.1770-4 / EBU R128 — gated LUFS and true-peak measurement; streaming targets commonly −14/−16 LUFS at −1 dBTP.
- ISO 226:2003/2023 equal-loudness contours; ISO 532-2 / Moore–Glasberg loudness; Aalto adaptive-loudness-compensation work and 2024 AES personalized in-ear EQ papers.
- AutoEq (`jaakkopasanen/AutoEq`), REW AutoEQ, miniDSP / Dirac Live mixed-phase correction and Bass Control.
- Bauer / AMB / Linkwitz crossfeed; Brown & Duda spherical-head model (already used by S08's 8D effect).
- BS-RoFormer / Mel-Band RoFormer (ByteDance), Demucs, RNNoise — for the deferred offline/source-aware tier only.
