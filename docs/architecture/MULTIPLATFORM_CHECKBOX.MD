# Streamify Multiplatform Execution Ledger & Checkbox Tracker
**Tracking Document:** `MULTIPLATFORM_CHECKBOX.MD`  
**Governing Master Plan:** [`docs/architecture/MULTIPLATFORM_PLAN_27_10.MD`](file:///data/data/com.termux/files/home/streamify-apk/docs/architecture/MULTIPLATFORM_PLAN_27_10.MD)  
**Governing Consensus:** [RFC Discussion #104](https://github.com/meraxes89/streamify-apk/discussions/104#discussioncomment-19212616) & Architectural Review (`docs/nano/resp5.md`)  
**Date:** October 27, 2026  

---

## 🏛️ Ledger of Architectural Consensus

| Ref ID | Architectural Decision | Status | Consensus Note |
|---|---|---|---|
| **MP-DEC-01** | **Sovereign Rust State Engine** | **APPROVED** | 100% of state transitions, playback queue CRDT, network, and DSP live in Rust. No state logic duplicated in UI shells. |
| **MP-DEC-02** | **Direct Dual UniFFI Codegen** | **APPROVED** | Android uses direct JNI mode (`streamify-android.aar`). Desktop JVM uses JNA mode (`streamify-jvm.jar`). iOS uses direct Swift mode. No Kotlin/Native double-bridge on Apple. |
| **MP-DEC-03** | **Hardware HAL as Clock Master** | **APPROVED** | Derive progress strictly from `DacPresentationClock` (`dac_frame_position`, `sample_rate`, `monotonic_ns`). Target: `<5ms` latency estimation error. |
| **MP-DEC-04** | **Push Event-Driven State Contract** | **APPROVED** | Abolish UI polling (`get_playback_state`). Rust pushes coalesced snapshots with monotonic `rev: u64` via `EngineEventListener`. |
| **MP-DEC-05** | **Canonical 48kHz & Pure f32 Audio** | **APPROVED** | AudioSink preserves IEEE 754 `f32`. Producer resamples to 48kHz via `rubato::SincFixedOut`. Output SRC only in HAL callback. |
| **MP-DEC-06** | **FFI Panic Safety Guarantee** | **APPROVED** | `panic = "abort"` in release profile + `catch_unwind` converting Rust panics into `EngineError::Internal { message, backtrace }`. |
| **MP-DEC-07** | **Bi-directional Media Command Flow** | **APPROVED** | Add `MediaCommandSource` input enum with 50–150ms hardware debounce for headset toggles. |
| **MP-DEC-08** | **Mobile-First Phased Scope (4a/4b)** | **APPROVED** | 2-engineer scope split: Phase 4a delivers Android (AAudio) + iOS (CoreAudio) + VirtualHAL; Phase 4b delivers Desktop (WASAPI + PipeWire). |

---

## 📋 Execution Ledger & Task Checklist

### ⚙️ Phase 3: Contract Freeze, SPSC Hardening, Panic Safety & Cargo Matrix
* **Lead Engineer:** Alpha (Rust/Core) & Beta (Bridge/Consumer)
* **Status:** **COMPLETED & MERGED** (PR #110 [Alpha / Core] & PR #111 [Beta / App] landed on `main`; Builds 648 & 650 verified 100% green)

- [x] **MP-C01**: Expose `DacPresentationClock` struct (`dac_frame_position: u64`, `sample_rate: u32`, `monotonic_ns: u64`) in `rust/src/streamify.udl`.
- [x] **MP-C02**: Expose `EngineEventListener` callback interface with monotonic `rev: u64` snapshot dispatching.
- [x] **MP-C03**: Expose `MediaCommandSource` enum (`Play`, `Pause`, `TogglePlayPause`, `Next`, `Previous`, `Seek`, `SetVolume`, `Stop`) with 50–150ms debounce.
- [x] **MP-C04**: Define structured, nested `EngineError` enum (`Network`, `Audio`, `Storage`, `Crypto`, `Internal`) with `#[non_exhaustive]` and stable error codes.
- [x] **MP-C05**: Embed `contract_version: u32` in FFI contract for fail-fast startup ABI compatibility check.
- [x] **MP-C06**: Wrap all UniFFI entrypoints in `std::panic::catch_unwind`, converting panics to `EngineError::Internal`.
- [x] **MP-C07**: Add `panic = "abort"` to release profiles in `rust/Cargo.toml`.
- [x] **MP-C08**: Harden `native/include/audio_sink_ringbuffer.h` with target-dependent alignment (`alignas(128)` on Apple Silicon, `alignas(64)` on x86/Windows).
- [x] **MP-C09**: Port `audio_sink_ringbuffer.h` dynamic allocations to standard C++17 `::operator new[](..., std::align_val_t{CACHE_LINE})`.
- [x] **MP-C10**: Implement generational flush protocol in `audio_sink_ringbuffer.h` and verify under concurrency tests.
- [x] **MP-C11**: Integrate `rubato::SincFixedOut` polyphase sinc resampler on producer side for canonical 48kHz `f32` conversion.
- [x] **MP-C12**: Split build outputs: direct JNI for Android target (`streamify-android.aar`) and JNA for Desktop JVM (`streamify-jvm.jar`).
- [x] **MP-C13**: Gate `jni = "0.21"` in `rust/Cargo.toml` strictly under `[target.'cfg(target_os = "android")'.dependencies]`.
- [x] **MP-C14**: Standardize on `reqwest` (HTTP/2 + TLS 1.3) + `tokio` (single `OnceLock<Runtime>`); gate experimental HTTP/3 behind feature flag.
- [x] **MP-C15**: Add `tracing` subscribers (android_logger, os_log, ETW, journald) + `metrics` + `sentry` crash telemetry.
- [x] **MP-C16**: Implement `EngineEventListener` in `NativeEngineBackend.kt`, binding Rust push events directly into Compose `StateFlow`.
- [x] **MP-C17**: Eliminate `get_playback_state` and `get_buffer_stats` polling loops in Android UI viewmodels.

---

### 📱 Phase 4a: Mobile First Audio Parity (Android + iOS + VirtualHAL)
* **Lead Engineer:** Alpha (Native Pull / Audio) & Beta (Test Double & Android Bridge)
* **Status:** **COMPLETED & MERGED** (PR #113 [Alpha / VirtualHAL & CoreAudio] & PR #114 [Beta / AAudio & Clock Bridge] landed on `main`)

- [x] **MP-HAL01**: Implement `VirtualHAL` in C++/Rust: deterministic in-memory null sink with simulated hardware DAC clock.
- [x] **MP-HAL02**: Extend `VirtualHAL` with adversarial conditions (callback size variance 256/512/1024, ±10% timing jitter, injected underruns/overruns, rate change events).
- [x] **MP-HAL03**: Establish 48kHz golden-vector test suite verifying bit-exact DSP, LUFS normalizer, and EQ output across x86_64 and ARM64.
- [x] **MP-HAL04**: Implement Apple CoreAudio native pull adapter (`AURenderCallback`) pulling `f32` directly from `AudioSink`.
- [x] **MP-HAL05**: Refactor Android AAudio/Oboe native callback to pull directly from the hardened float `AudioSink`.
- [x] **MP-HAL06**: Verify `<5ms` latency estimation error for `DacPresentationClock` on Android and iOS hardware.
- [x] **MP-HAL07**: Scaffold Android feature flag `BuildConfig.USE_RUST_AUDIO` for parallel dogfood testing.

---

### 🖥️ Phase 4b: Desktop Audio (Windows & Linux)
* **Lead Engineer:** Alpha (Desktop HALs) & Beta (Desktop CI & Packaging)
* **Status:** **COMPLETED & MERGED** (PR #116 [Beta / Desktop CI & Size Budget] & PR #117 [Alpha / Desktop Audio HALs] landed on `main`)

- [x] **MP-HAL08**: Implement Windows WASAPI exclusive/shared event-driven pull adapter (`IAudioClient3` + `AvSetMmThreadCharacteristics`) — `native/audio/wasapi_pull_adapter.cpp` (`#if _WIN32`): `IAudioClient3` shared-low-latency engine period (fundamental-grid aligned, canonical-format fast path) → standard shared `AUTOCONVERTPCM` fallback → event-driven exclusive `WAVEFORMATEXTENSIBLE` f32; `AvSetMmThreadCharacteristicsW(L"Pro Audio")` render thread; `IAudioClock::GetPosition` → `DacPresentationClock`. RT path = host-testable `WasapiRenderCore` (`native/include/wasapi_render_core.h`); audited by `desktop_hal_render_core_test_suite` on every host + mock-consumer twin in `rust/src/desktop_hal_models.rs` (`rust/tests/test_desktop_hal_contracts.rs`). Cross-verified against real Windows SDK headers (MinGW-w64).
- [x] **MP-HAL09**: Implement Linux PipeWire native process callback adapter (`pw_stream_events`) with ALSA fallback — `native/audio/pipewire_driver.cpp` (`#if __linux__`): `pw_stream` RT process callback pulling f32 straight from `AudioSink`, `pw_time` clock ingestion (lock-free `pw_stream_get_time` snapshot), and a dlopen'd ALSA `snd_pcm_writei` fallback that triggers when `libpipewire-0.3.so.0` / the daemon is unavailable. ZERO dev-package link deps: the PipeWire/SPA ABI is embedded frozen (`native/include/linuxhal_embedded_abi.h`, static-asserted + byte-identical EnumFormat pod, cross-checked against real 0.3.65 & 1.4.2 headers) so the driver compiles on any Linux toolchain. RT paths = host-testable `PipeWireRenderCore` / `AlsaRenderCore`; audited by `desktop_hal_render_core_test_suite` + `linux_hal_driver_test_suite` (white-box: live PipeWire→ALSA fallback drive on the deviceless ALSA `null` PCM) + the Rust mock-consumer twin.
- [x] **MP-HAL10**: Establish CI size budget for packaged Desktop CMP apps (<250MB) — `.github/workflows/desktop-ci.yml` matrix lane active on Ubuntu & Windows; Compose Desktop jpackage packaging with `checkDistributionSizeBudget` task enforcing `< 250 MiB` budget; measured Linux `.deb` installer is 51.14 MiB (~20% of ceiling), stripped native core `libstreamify_core_rs.so` is 8.06 MB; JNA smoke suite (`DesktopJnaEngineSmokeTest.kt`) verified headless. (PR #116 landed).

---

### 🎨 Phase 5: Shell Assembly, iOS Adapter & Android Cutover
* **Lead Engineer:** Beta (UI / Shell) & Alpha (Platform Integration)
* **Detailed Apple Execution Ledger:** [`docs/architecture/SWIFT_MIGRATION_PLAN.MD`](file:///data/data/com.termux/files/home/streamify-apk/docs/architecture/SWIFT_MIGRATION_PLAN.MD) (4-Engineer Workstream & 398-file Kotlin-to-Swift mapping)

- [ ] **MP-UI01**: Build native iOS SwiftUI shell wrapping direct Swift UniFFI bindings.
- [ ] **MP-UI02**: Implement `IOSAudioSessionAdapter` handling `.playback` category, interruption notifications (`.began`, `.ended`), route changes (`.oldDeviceUnavailable` pause), and AirPlay teardowns.
- [ ] **MP-UI03**: Extract platform-agnostic Compose components, theme tokens, and ViewModels into `shared/src/commonMain`.
- [ ] **MP-UI04**: Build Desktop Compose Multiplatform shell with responsive sidebar navigation rail and window control integration.
- [ ] **MP-CAP01**: Implement Windows System Media Transport Controls (SMTC) adapter for taskbar and media keys.
- [ ] **MP-CAP02**: Implement Linux MPRIS D-Bus adapter for system media player integration.
- [ ] **MP-CAP03**: Implement Apple `MPRemoteCommandCenter` and `MPNowPlayingInfoCenter` (rate-limited to 1Hz).
- [ ] **MP-CAP04**: Implement secure storage provider delegation (Windows DPAPI, Linux Secret Service, iOS Keychain, Android KeyStore).
- [ ] **MP-CUT01**: Execute 2-week dogfood period on Android with `BuildConfig.USE_RUST_AUDIO = true`.
- [ ] **MP-CUT02**: Delete legacy Android audio pipeline 30 days post-cutover.
