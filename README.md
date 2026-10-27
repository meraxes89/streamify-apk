# Streamify — High-Fidelity Multiplatform Music Engine

[![Extreme Test Matrix & Chaos Suite](https://github.com/meraxes89/streamify-apk/actions/workflows/extreme-test-matrix.yml/badge.svg)](https://github.com/meraxes89/streamify-apk/actions/workflows/extreme-test-matrix.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-green.svg)](LICENSE)
[![Platform: Android](https://img.shields.io/badge/Platform-Android%208.0%2B%20(API%2026%2B)-brightgreen.svg)](https://android.com)
[![Multiplatform Target](https://img.shields.io/badge/Multiplatform-Android%20%7C%20iOS%20%7C%20Desktop-blue.svg)](docs/architecture/MULTIPLATFORM_PLAN_27_10.MD)
[![Rust](https://img.shields.io/badge/Rust-1.93%20%7C%20Sovereign%20Engine-red.svg)](https://www.rust-lang.org)
[![C++](https://img.shields.io/badge/C%2B%2B-20%20%7C%20ARM%20NEON%20SIMD-orange.svg)](https://isocpp.org)
[![Kotlin](https://img.shields.io/badge/Kotlin-1.9.22%20%7C%20Compose-purple.svg)](https://kotlinlang.org)

**Streamify is a free, high-fidelity music streaming engine with every flagship feature unlocked by default** — zero paywalls, studio-grade DSP volume normalization, gapless playback, synchronized syllable-accurate lyrics, distributed lockstep jam sessions, and offline playback. Read our founding principles in [PHILOSOPHY.md](PHILOSOPHY.md).

Technically, Streamify is engineered on a **Sovereign Multiplatform Core**: all business logic, audio decoding, network transport, cryptographic consensus, and DSP processing live in **Rust** and **C++20**, bound directly to native platform shells via zero-overhead UniFFI contracts and hardware-aligned lock-free ringbuffers.

---

## 🏛️ Architecture Highlights

```
┌────────────────────────────────────────────────────────────────────────┐
│                   NATIVE CLIENT UI & APP SHELLS                        │
│   Android (Compose)  │  iOS (SwiftUI)  │  Desktop (Compose Multiplat)  │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │ Direct Push Events (EngineEventListener)
                                    ▼
┌────────────────────────────────────────────────────────────────────────┐
│                    SOVEREIGN RUST ENGINE CORE                          │
│   • Tokio / Reqwest HTTP/2 Streamer & Circuit Breaker                  │
│   • YouTube Innertube & Multi-Tier Stream Resolvers                    │
│   • Rubato SincFixedOut 48kHz Polyphase Resampler                      │
│   • Lofty Pure Native Audio Metadata Tagging                           │
│   • SLYR Bytecode Binary Lyrics Compiler (Sub-millisecond)             │
│   • IEEE 1588 PTP Distributed Clock & Jam Session Consensus           │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │ Lock-Free Generational Flush
                                    ▼
┌────────────────────────────────────────────────────────────────────────┐
│             C++20 HARDENED SPSC RINGBUFFER (Float f32)                 │
│   • Target-aligned (64-byte x86 / 128-byte Apple Silicon)              │
│   • Zero-allocation audio thread (C++17 std::align_val_t)              │
│   • Soft-Knee Limiter & ITU-R BS.1770-4 LUFS Normalizer                │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │ Hardware Pull Callbacks
                                    ▼
┌────────────────────────────────────────────────────────────────────────┐
│                  PLATFORM AUDIO HARDWARE OUTPUT (HAL)                  │
│   Android: AAudio/Oboe  │  Apple: CoreAudio  │  Desktop: WASAPI/PipeWire│
│   Master Hardware Presentation Clock (DacPresentationClock < 5ms error)│
└────────────────────────────────────────────────────────────────────────┘
```

* **Zero-Allocation Audio Thread**: Real-time platform pull callbacks drain `f32` stereo audio directly from a cacheline-aligned SPSC ringbuffer without JVM upcalls or heap allocations.
* **Master Presentation Clock**: Audio progression is driven by the physical DAC presentation timestamp (`DacPresentationClock`), providing sub-5ms position accuracy even across Bluetooth and hardware buffers.
* **Pure Reactive UI**: UI polling loops have been completely replaced by asynchronous monotonic event snapshots (`EngineEventListener`).
* **Lean Footprint**: Stripped dead dependencies to achieve a clean **~77.8 MB universal 4-ABI APK** (down from 217 MB).

---

## 📂 Repository Organization

```
streamify-apk/
├── app/                  # Android application (Kotlin, Jetpack Compose, ExoPlayer/AAudio bridge)
│   ├── src/main/java/    # UI screens, ViewModels, MediaSession, Dependency Graph
│   └── src/test/         # Android unit, contract, and dispatcher tests
├── native/               # C++20 real-time DSP, fluid ballistics & lock-free ringbuffers
│   ├── include/          # audio_sink_ringbuffer.h, dsp_engine.h
│   ├── src/              # SPSC ringbuffer, SIMD neon physics, LUFS normalization
│   └── tests/            # C++ LibFuzzer and ringbuffer concurrency test suites
├── rust/                 # Sovereign Rust Core (streamify_core_rs)
│   ├── src/              # Resolver, crypto, audio resampler (rubato), consensus, UniFFI
│   ├── examples/         # Diagnostic probes and network verification utilities
│   └── tests/            # Contract ABI tests and golden vector suites
├── docs/                 # Systems manuals, architectural plans, playbooks & guides
│   ├── architecture/     # Master multiplatform plans, execution ledgers, Jam specs
│   ├── playbooks/        # Developer playbooks, codebase guides, CI runbooks
│   └── nano/             # RFC architectural review records
├── legacy/               # Frozen baseline backups & preservation archives (Pre-Multiplatform 95773a8)
└── scripts/              # CI test harness, ABI gates, release probes, and developer tools
```

---

## 📚 Documentation Index

All in-depth systems documentation, engineering specifications, and architectural plans live in the [`docs/`](docs/) directory:

| Document | Description |
|---|---|
| **[Detailed Systems Manual](docs/README_DETAILED.MD)** | **Full technical manual (2,600+ lines)**: 64-feature technical specs, memory invariants, FMEA fault analysis, and deep-dive architectural diagrams. |
| **[Multiplatform Master Plan](docs/architecture/MULTIPLATFORM_PLAN_27_10.MD)** | **The governing multiplatform roadmap**: 15 architectural decisions covering Android, iOS (CoreAudio/SwiftUI), and Desktop (WASAPI/PipeWire). |
| **[Execution Ledger & Tracker](docs/architecture/MULTIPLATFORM_CHECKBOX.MD)** | **Real-time implementation ledger**: Track status of all phases (Phase 3 Contract Freeze, Phase 4a Mobile Parity, Phase 4b Desktop). |
| **[System Architecture Map](docs/architecture/ARCHITECTURE.md)** | **Master architecture overview**: Data flow, dependency rules, end-to-end song playback lifecycle, and FFI boundaries. |
| **[Codebase Encyclopedia](docs/playbooks/CODEBASE_GUIDE.md)** | **Folder-by-folder guide**: File-by-file encyclopedia of the Android and native modules. |
| **[Feature Playbook](docs/playbooks/ADD_A_FEATURE.md)** | **Step-by-step developer recipes**: Standard playbooks for adding screens, modifying DSP, and expanding network resolvers. |
| **[CI & Chaos Testing Guide](docs/playbooks/CI_TESTING_GUIDE.md)** | **Automated quality verification**: 18-shard CI matrix, C++20 LibFuzzer, Native ABI verification, and gatekeeper CDN tests. |
| **[Distributed Jam Specification](docs/architecture/JAM_ENGINE_SPEC.md)** | **Byzantine consensus & PTP audio sync**: Mathematical and architectural design of multi-device synchronized listening. |
| **[Project Philosophy](PHILOSOPHY.md)** | **Mission statement**: Our manifesto on open software, uncompromised audio quality, and zero-paywall music. |

---

## 🛠️ Quick Start & Build Instructions

### Prerequisites
* **JDK:** 17+ (e.g. OpenJDK / Eclipse Temurin)
* **Android SDK:** API 34 + Build Tools 34.0.0
* **Android NDK:** r26d (`26.3.11579264`)
* **Rust:** 1.75+ with Android targets (`aarch64-linux-android`, `armv7-linux-androideabi`, `x86_64-linux-android`, `i686-linux-android`)
* **CMake & Ninja:** CMake 3.22.1+

### Building the Android APK
```bash
# Clone the repository
git clone https://github.com/meraxes89/streamify-apk.git
cd streamify-apk

# Assemble universal debug APK
./gradlew :app:assembleDebug

# Verify output
ls -lh app/build/outputs/apk/debug/app-debug.apk
```

### Running Native & Rust Test Suites
```bash
# Run Rust unit, resolver, and UniFFI contract tests
cd rust
cargo test --all-targets
cargo clippy --all-targets -- -D warnings
cd ..

# Run C++ Ringbuffer & DSP Unit Tests
cd native
cmake -B build -S . -DENABLE_TESTS=ON
cmake --build build --target test_audio_sink_ringbuffer
ctest --test-dir build --output-on-failure
cd ..

# Run JVM Unit Tests
./gradlew testDebugUnitTest
```

---

## 📦 Verified Releases & Artifacts

All releases are verified through our 18-shard CI pipeline and published under [GitHub Releases](https://github.com/meraxes89/streamify-apk/releases):

* **[Build 650](https://github.com/meraxes89/streamify-apk/releases/tag/build-650)** — Phase 3 Beta Client Bridge (Reactive `EngineEventListener`, debounced `MediaCommandDispatcher`, 77.86 MB).
* **[Build 648](https://github.com/meraxes89/streamify-apk/releases/tag/build-648)** — Phase 3 Alpha Core Contracts (SPSC hardening, Rubato 48kHz SRC, Cargo matrix, 77.84 MB).
* **[Build 642](https://github.com/meraxes89/streamify-apk/releases/tag/build-642)** — Lean Baseline (Stripped onnxruntime bloat: 217 MB → 82.96 MB).
* **[Pre-Multiplatform Base Archive](legacy/streamify-base-head-95773a8.zip)** — Immutable preservation head at commit `95773a8` (SHA256: `8193cecab358279dbfb87438dd356e2e0565f597f799cf6e983b91964f9144f8`).

---

## 📄 License

Streamify is licensed under the [MIT License](LICENSE).
Contributions follow the [Code of Conduct](CODE_OF_CONDUCT.md) and our [Architecture Decision Records](docs/architecture/).
