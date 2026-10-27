# Streamify Multiplatform Execution Ledger & Checkbox Tracker
**Tracking Document:** `MULTIPLATFORM_CHECKBOX.MD`  
**Governing Master Plan:** [`docs/architecture/MULTIPLATFORM_PLAN_27_10.MD`](file:///data/data/com.termux/files/home/streamify-apk/docs/architecture/MULTIPLATFORM_PLAN_27_10.MD)  
**Governing Consensus:** [RFC Discussion #104](https://github.com/meraxes89/streamify-apk/discussions/104#discussioncomment-19212616)  
**Date:** October 27, 2026  

---

## 🏛️ Ledger of Architectural Consensus

| Ref ID | Architectural Decision | Status | Consensus Note |
|---|---|---|---|
| **MP-DEC-01** | **Sovereign Rust State Engine** | **APPROVED** | 100% of state transitions, playback queue CRDT, network, and DSP live in Rust. No state logic duplicated in UI shells. |
| **MP-DEC-02** | **Direct Dual UniFFI Codegen** | **APPROVED** | UniFFI generates Kotlin/JVM for Android/Desktop and Swift for iOS. Absolute prohibition of double-bridge (no Kotlin/Native cinterop on Apple). |
| **MP-DEC-03** | **Hardware HAL as Clock Master** | **APPROVED** | Playback position derives strictly from hardware presentation timestamps (`RenderClock`). Zero wall-clock (`currentTimeMillis`) interpolation. |
| **MP-DEC-04** | **Push Event-Driven State Contract** | **APPROVED** | Abolish UI polling (`get_playback_state`). Rust pushes coalesced snapshots with monotonic `rev: u64` via `EngineEventListener`. |
| **MP-DEC-05** | **Pure 32-Bit Float & Native Pull** | **APPROVED** | AudioSink preserves IEEE 754 `f32`. Real-time audio thread never crosses FFI boundaries; platform drivers pull directly from C++ ring buffer. |
| **MP-DEC-06** | **Capability Ports over Monolithic Managers** | **APPROVED** | Decouple OS media integrations (SMTC, MPRIS, Media3, MPRemoteCommandCenter) behind generic Rust capability traits. |

---

## 📋 Execution Ledger & Task Checklist

### ⚙️ Phase 3: Contract Freeze, SPSC Hardening & Cargo Matrix
* **Lead Engineer:** Alpha (Rust/Core) & Beta (Bridge/Consumer)

- [ ] **MP-C01**: Expose `RenderClock` struct (`frames_consumed: u64`, `sample_rate: u32`, `monotonic_ns: u64`) in `rust/src/streamify.udl`.
- [ ] **MP-C02**: Expose `EngineEventListener` interface in `streamify.udl` and wire coalesced push dispatcher in `streamify_core_rs`.
- [ ] **MP-C03**: Expose `MediaCommandSink` and `MediaCapabilities` traits in `streamify.udl`.
- [ ] **MP-C04**: Harden `native/include/audio_sink_ringbuffer.h` with `alignas(128)` on Apple Silicon to eliminate L1 false sharing.
- [ ] **MP-C05**: Port `audio_sink_ringbuffer.h` dynamic allocations to standard C++17 `::operator new[](..., std::align_val_t{64})` for MSVC Windows portability.
- [ ] **MP-C06**: Gate `jni = "0.21"` in `rust/Cargo.toml` strictly under `[target.'cfg(target_os = "android")'.dependencies]`.
- [ ] **MP-C07**: Configure split artifact outputs: `cdylib` for Android/Desktop and `staticlib` for iOS XCFramework packaging.
- [ ] **MP-C08**: Implement `EngineEventListener` in `NativeEngineBackend.kt`, binding Rust push events directly into Compose `StateFlow`.
- [ ] **MP-C09**: Eliminate `get_playback_state` and `get_buffer_stats` polling loops in Android UI viewmodels.

---

### 🧪 Phase 4: Virtual HAL, Golden Vectors & Native Pull Adapters
* **Lead Engineer:** Alpha (Native Pull / Audio) & Beta (Test Double Integration)

- [ ] **MP-HAL01**: Implement `VirtualHAL` in C++/Rust: deterministic in-memory null sink with simulated hardware DAC clock.
- [ ] **MP-HAL02**: Build CI headless integration tests running `VirtualHAL` on Linux, macOS, and Windows runners without physical soundcards.
- [ ] **MP-HAL03**: Establish golden-vector test suite verifying bit-exact DSP, LUFS normalizer, and EQ output across x86_64 and ARM64.
- [ ] **MP-HAL04**: Implement Apple CoreAudio native pull adapter (`AURenderCallback`) pulling `f32` from `AudioSink`.
- [ ] **MP-HAL05**: Implement Windows WASAPI exclusive/shared event-driven pull adapter (`IAudioClient3`).
- [ ] **MP-HAL06**: Implement Linux PipeWire native process callback adapter (`pw_stream_events`).
- [ ] **MP-HAL07**: Refactor Android AAudio/Oboe native callback to pull directly from the hardened float `AudioSink`.

---

### 🖥️ Phase 5: Shell Assembly & OS Capability Adapters
* **Lead Engineer:** Beta (UI / Shell) & Alpha (Platform Integration)

- [ ] **MP-UI01**: Extract platform-agnostic Compose components, theme tokens, and ViewModels into `shared/src/commonMain`.
- [ ] **MP-UI02**: Build Desktop Compose Multiplatform shell with responsive sidebar navigation rail and window control integration.
- [ ] **MP-UI03**: Build native SwiftUI shell for iOS wrapping direct Swift UniFFI bindings.
- [ ] **MP-CAP01**: Implement Windows System Media Transport Controls (SMTC) adapter for taskbar and media keys.
- [ ] **MP-CAP02**: Implement Linux MPRIS D-Bus adapter for system media player integration.
- [ ] **MP-CAP03**: Implement Apple `MPRemoteCommandCenter` and `MPNowPlayingInfoCenter` for iOS / macOS lockscreen controls.
- [ ] **MP-CAP04**: Implement secure storage provider delegation (Windows DPAPI, Linux Secret Service, iOS Keychain, Android KeyStore).
