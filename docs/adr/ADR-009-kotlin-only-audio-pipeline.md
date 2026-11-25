# ADR-009: Kotlin-only audio pipeline

* Status: Accepted
* Date: 2026-11-25
* Supersedes: ADR-001 and ADR-002 for shipped audio processing
* Verified against: ee2590133b8ccf610640278dca3b3374139573b4 (staleness protocol in docs/adr/README.md)

## Context

The repository historically described a sovereign Rust core (`rust/`) and a
C++20 real-time DSP layer (`native/`) carrying ITU-R BS.1770-4 LUFS
normalization, a 4x-oversampled true-peak limiter and an SPSC sink ringbuffer
(ADR-001, ADR-002). The Phase-1 audio audit recorded in project history established three facts:

1. The shipped app never packaged that native layer. The Kotlin declarations
   of 162 `external fun`s had no matching `.so` in the assembled app; calls
   degraded silently to the Kotlin path.
2. The Kotlin `AudioProcessor` chain on Android was materially better
   engineered than the native path: correct RBJ peaking biquads, a verified
   uniform partitioned convolver, and a correct spherical-head 8D model. The
   native path carried two confirmed math errors (a bass shelf that was a
   +20.9 dB resonance at 765 Hz, and an RMS normalizer off by -8 dB at typical
   buffer sizes), audio-thread mutexes and stale-handle use-after-free.
3. The audit's recommendation was to keep the Kotlin engine and delete or
   quarantine the native surface.

Commit `50b937f7` on this lineage removed `native/`, `rust/`,
`streamify-modules/core-bridge/` and the native CI lanes. The checked-out tree
now contains no compiled native DSP.

The remaining README and engine-spec prose still describe the retired stack;
that is documentation drift, not current architecture.

## Decision

Android audio processing is the Kotlin `AudioProcessor` chain in
`core/media/media3/src/main/java/com/maxrave/media3/audio/`; desktop uses
equivalent ffmpeg filters generated in `MpvEffectGraphs.kt` and driven through
libmpv via JNA. No Rust or C++ DSP ships in any artifact.

Any future native mastering core (Rust, C++, JUCE, KFR) requires a new RFC and
ADR that names the capability being added — not a re-introduction of the
retired path. Duplicating EQ, reverb, delay or 8D is explicitly out of scope.

## Alternatives considered

### Restore and package the former native core

- Pros: LUFS and true-peak limiter already existed; golden vectors existed.
- Cons: the audio path carried confirmed math errors and mutexes; two JNI
  libraries defined duplicate symbols with divergent semantics; packaging
  both would make behaviour depend on load order.
- Rejected: it would ship a worse engine and re-open the handle-safety hole.

### Integrate a new native mastering core now

- Pros: adds dynamics, saturation, true-peak limiting and oversampling that
  the Kotlin chain lacks.
- Cons: no evidence that YouTube-sourced material needs it more than the
  existing gaps in control flow and test coverage do; would add a second
  toolchain to a hobby project.
- Deferred: requires an RFC with measured evidence and a single bridge.

### Keep both engines and choose at runtime

- Rejected: doubles the state machines and recreates the dual-stack confusion
  that this decision ends.

## Consequences

- **Positive:** one engine per platform, fully in-tree and testable; no JNI
  panic/UAF surface on the audio thread; settings are shared as immutable
  snapshots between Android and desktop.
- **Negative:** no LUFS target, no true-peak ceiling (PCM16 hard clip), no
  dynamics or saturation, and no oversampling/anti-aliasing. These are tracked
  in S08's refinement backlog, not denied.
- **Required follow-up:** keep S08 current, add an EQ response test and a
  lookahead limiter before revisiting any native core, and mark historical
  descriptions in README/engine-spec 02 as lineage.

## Compliance

- `docs/SYSTEMS.md` S08 records the shipped modules and the exclusions.
- `docs/systems/S08-dsp-and-acoustics.md` carries the HISTORICAL banner for the
  retired stack.
- `scripts/verify_docs.py` and `scripts/docs_ledger_check.py` run in Tier-1
  preflight and flag present-tense claims about the retired native core.

<!-- doc-verified-against: ee2590133b8ccf610640278dca3b3374139573b4 -->
