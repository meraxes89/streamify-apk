# Universal Multiplatform Migration Documentation

> **Directory Purpose**: This directory contains the complete technical specification, architectural blueprints, and execution ledger for adapting SimpMusic's Compose Multiplatform architecture into Streamify while retaining our sovereign Rust core, C++20 DSP engine, Supabase cloud infrastructure, SLYR lyrics compiler, and Dolby Atmos/Spatial features.

---

## Document Index

| Document | Purpose |
|---|---|
| **[MASTER_PLAN.md](MASTER_PLAN.md)** | Comprehensive master plan: Architecture, data flow, component boundaries, and upstream sync strategy. |
| **[MIGRATION_EXECUTION_LEDGER.md](MIGRATION_EXECUTION_LEDGER.md)** | Phase-by-phase execution tracker with verifiable acceptance criteria and engineering checklists. |

---

## Architectural Principles

1. **Upstream Immunity**: All custom features (C++20 DSP, Rust core, Supabase, SLYR lyrics) live in isolated modules (`streamify-modules/`). We never modify upstream SimpMusic files directly so we can `git fetch upstream && git merge upstream/dev` anytime without merge conflicts.
2. **IoC Dependency Injection**: SimpMusic's runtime is wired via Koin. We inject our native engines, Cloud Sync, and Audio Sinks through Koin module overrides (`loadKoinModules(..., allowOverride = true)`).
3. **Hardware-Anchored Master Clock**: Audio progress in all UI screens is paced by physical DAC presentation timestamps (`DacPresentationClock < 5ms`), preventing jitter and drift.
4. **Zero-Stall Playback**: Single-tap song plays instantaneously while RDAMVM / Semantic fallback radio builds concurrently in the background.
5. **No Double-Bridge on Apple**: Android uses direct JNI; Desktop uses UniFFI JNA; iOS retains our pure SwiftUI shell (`iosApp/`) via direct Swift UniFFI bindings.
