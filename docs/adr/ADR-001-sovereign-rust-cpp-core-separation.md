# ADR-001: Sovereign Rust/C++ core separation

* Status: Accepted
* Date: 2026-11-22
* Verified against: 8b858b1c (staleness protocol in docs/adr/README.md)

## Context

Streamify descends from a Kotlin Android app (SimpMusic lineage) that briefly
carried an embedded Python runtime for solver work (removed; rationale in
`docs/archive/why_we_removed_python.md`). By late 2026 the codebase was a
tri-language system whose business logic, network stack, and audio math all
ran on the JVM or in Python glue. The observed problems, recorded in the
A+ tier plan (`docs/architecture/A_TIER_PLAN_26_10.MD`):

- GC pauses landed on the audio path; real-time guarantees were impossible.
- The embedded Python runtime inflated the APK and complicated ABI.
- Decode, resampling, and network resolution each paid JVM overhead with no
  control over allocation or memory layout.

Constraints: YouTube resolution requires constant maintenance against client
changes (favoring a fast-iteration language with strong typing); real-time
DSP requires deterministic memory behavior (favoring manual control); the UI
must remain Compose Multiplatform for Android/desktop/web.

## Decision

Business logic, network transport, decoding, resampling, crypto, and jam
consensus live in a sovereign Rust core (`rust/`, ten crates, ~45.7 kLOC,
single artifact `libstreamify_core_rs`). Real-time sample math and platform
audio HALs live in C++20 (`native/`). Platform shells stay Kotlin Compose and
bind through exactly two frozen contracts: the UniFFI contract v2
(`streamify.udl`, `contract_version()` pinned to 2, Kotlin package
`uniffi.streamify`) and the JNI surface with panic shields at every export
(63 `catch_unwind` sites in the Rust JNI bridge; `JNI_SHIELD` in every C++
export). The release profile uses `panic = "abort"`, LTO, and one codegen
unit. The JVM is never on the sample path: decoded audio crosses into the
C++ SPSC ringbuffer (ADR-002) and the HAL pulls from there.

## Alternatives considered

- **All Kotlin (JVM) core.** Rejected: GC pauses on the audio path are
  structural, not tunable; the original problem.
- **Embedded Python retained.** Rejected: runtime weight, GIL, and ABI churn
  (see the archived removal decision).
- **All C++ including networking.** Rejected: resolver maintenance against
  YouTube client churn favors Rust's ecosystem (rustypipe) and safety; C++
  iteration speed on protocol code is measurably worse for this team.
- **Platform-per-platform cores (Kotlin + Swift + C++).** Rejected: triples
  the resolver maintenance surface; the multiplatform plan chose one
  sovereign core with per-platform bindings.

## Consequences

**Positive.** One core serves Android, desktop, and iOS lanes (cdylib,
staticlib, xcframework); the audio thread is GC-free by construction; the
contract is frozen and CI-enforced (UniFFI bindings drift diff, JNI parity
gates, golden vectors pinning DSP and jam-op encodings byte-for-byte).

**Negative.** Two native toolchains in CI (cargo-ndk + CMake/NDK); a dual ABI
surface (UniFFI and JNI) that must be kept coherent; platform engineers need
Rust literacy. The second Tokio runtime in the P2P mesh lane is a documented
exception to the one-runtime doctrine, not a precedent.

## Compliance

CI runs cargo clippy `-D warnings` over the workspace, the UniFFK bindings
drift diff, `scripts/jni_parity_check.py` in `--source` and `--nm` modes,
and `scripts/jni_panic_boundary_check.py` (every `Java_*` export must shield
panics). `docs/architecture/MULTIPLATFORM_PLAN_27_10.MD` records the
governing invariants.

---
<!-- doc-verified-against: 8b858b1c89b91f643cd7159180706fbd931f925e -->
