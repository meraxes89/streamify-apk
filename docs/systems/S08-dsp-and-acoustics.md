---
id: S08
title: DSP & acoustics
tier: client
status: active
owner: audio
sources: [core/media/media3/src/main/java/com/maxrave/media3/audio/, core/domain/src/commonMain/kotlin/com/maxrave/domain/data/player/, core/service/autoEqService/]
tests: [core/media/media3/src/test/java/com/maxrave/media3/audio/, core/domain/src/commonTest/kotlin/com/maxrave/domain/data/player/]
---

# S08 — DSP & acoustics

> Part of the [subsystem index](../SYSTEMS.md). Documentation rules: [../README.md](../README.md).

## Owns

- Ten-band peaking EQ (RBJ cookbook, Q 1.41), DJ LPF/HPF crossfade filters, delay taps matching ffmpeg `aecho`, four synthesized convolution-reverb presets, sleep fade, and the 8D spherical-head binaural orbit.

## Does not own

- Dynamics compression, multiband, de-essing, saturation, true-peak limiting, LUFS measurement and sample-rate conversion — none ship here.

## Current truth

- > **HISTORICAL:** the Rust/C++ DSP core (BS.1770-4 LUFS, 4x-oversampled true-peak limiter, master chain) was removed at commit `50b937f7`. It is described in engine spec 02 and ADR-001/002 as lineage, not current state.
- Android kernels are custom Kotlin over PCM 16-bit (8D also accepts PCM_FLOAT); desktop uses equivalent ffmpeg filters generated in `MpvEffectGraphs.kt`.

## Interfaces

- Settings snapshot → per-buffer sampling → Android `AudioProcessor` or desktop ffmpeg filter; IR generated once and shared with desktop `afir`.

## Decisions and specs

- Spec: [02_dsp_acoustic.md](../architecture/engines/02_dsp_acoustic.md)
- Decision: [ADR-009-kotlin-only-audio-pipeline](../adr/ADR-009-kotlin-only-audio-pipeline.md)

## Known limits / debt

- Hard clip at PCM16 bounds with no true-peak ceiling; 85 ms first-block reverb latency; no oversampling or anti-aliasing; no EQ golden-vector test.

## Refinement backlog

- Add a lookahead limiter, an EQ frequency-response test, and an oversampled quality path if a native core is reconsidered.

<!-- doc-verified-against: 1961dc73cadbe7a283e12416cef86651ea21f034 -->
