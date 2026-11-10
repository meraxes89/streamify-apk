# `rust/` — the Sovereign 7-Crate Workspace

All business logic, audio, crypto-consensus and network transport live
here. Compiled into the artifact every platform shell loads:

* Android/Linux: `libstreamify_core_rs.so` (cdylib, built via cargo-ndk,
  packaged into the APK by `app/build.gradle.kts`)
* Windows: `streamify_core_rs.dll` (cdylib)
* iOS/macOS: the `StreamifyCore.xcframework` static-lib slices
  (built by `scripts/build_ios_xcframework.sh`)

The monolith layout (`rust/src/*.rs`) was modularized into the workspace
below (PR #126, Discussion #120). `git log` retains the pre-split history.

## Layout (`crates/`)

| Crate | What it is |
|---|---|
| `streamify-core` (~1.3 kLOC) | Leaf types: contract records, the `DacPresentationClock`, the `EngineError` taxonomy, `MediaCommand`, atomic primitives. Dependency-free by design. |
| `streamify-audio` (~3.9 kLOC) | Hard real-time DSP: Rubato sinc SRC, LUFS/true-peak limiting, the HAL render-core models. **RT-safety invariant enforced by `scripts/verify_audio_rt_boundary.sh`** — no tokio/rusqlite/reqwest on the render path. |
| `streamify-jam` (~20.9 kLOC) | Distributed jam: Ed25519-signed governance, PlumTree gossip with lazy-push healing, CmRDT queue with BLAKE3 state hashing, PTP clock math. 32-node loopback-UDP chaos harness under test. |
| `streamify-resolver` (~6.2 kLOC) | Stream extraction: pure-Rust RustyPipe engine (SQLite deobf cache), Innertube parsing, fuzzy search + ISRC fallback, radio/blend scorers, lyrics providers. |
| `streamify-storage` (~2.0 kLOC) | Device-local encrypted storage: SQLite pool, schema migrations, repository layer, AEAD cache vault (SEC-06: ChaCha20-Poly1305 with a read-only XOR migration reader). |
| `streamify-contract` (~8.5 kLOC) | The UniFFI 0.28 contract: `streamify.udl` scaffolding, the `StreamifyEngine` object, OS capability adapters (SMTC / MPRIS / Apple remote-command), observability. |
| `streamify-ffi` (~3.8 kLOC) | The umbrella artifact crate: cdylib/staticlib + the JNI bridges (`Java_com_streamify_app_data_NativeBridge_*`), the AEAD chunk cipher, thermal/affinity orchestration. Also hosts the crate-local `uniffi-bindgen` CLI (`cargo run --bin uniffi-bindgen`). |

The audio RT boundary, JNI panic discipline (`catch_unwind` at every
export — never let a panic cross the FFI boundary) and UniFFI contract
freeze are load-bearing invariants; see `docs/architecture/ARCHITECTURE.md`.

## Tests

CI runs the workspace matrix as the `Core Engine & C++ DSP` spoke
(`.github/workflows/_ci-engine.yml` via the `ci.yml` hub): `cargo
nextest` split shards on every PR, plus the deep nightly profile (the
32-peer mesh chaos suite) in `nightly-deep-matrix.yml`. Clippy runs
workspace-wide at `-D warnings`.

## Panic safety

`catch_unwind` isolates every FFI crossing; JNI exports return negative
sentinels instead of panicking (verified by
`scripts/jni_panic_boundary_check.py` in preflight).
