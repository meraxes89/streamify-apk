# Universal Multiplatform Master Plan: SimpMusic Adoption & Sovereign Core Fusion

**Document ID:** `UNIV-MP-001`  
**Status:** Approved for Execution  
**Integration Branch:** `integration/universal-multiplatform`  

---

## 1. Executive Summary

This document defines the architectural blueprint to fuse **SimpMusic’s rich Compose Multiplatform (CMP) UI ecosystem** with **Streamify’s sovereign triple-native core** (Rust 1.93, C++20 NEON, Supabase Cloud).

### Core Goals:
1. **Adopt SimpMusic's CMP Foundation**: Leverage its multi-platform UI (Android & Desktop CMP), Room KMP data layer, AutoEQ headphone database, SoundCloud streaming, Spotify Canvas scraper, and native OS integrations (Android Widgets, Android Auto, Quick Settings Tile, Conveyor desktop packaging).
2. **Inject Streamify Sovereign Systems**:
   * **C++20 & Rust Real-Time Audio DSP**: ITU-R BS.1770-4 LUFS normalizer, true-peak soft-knee limiter, lock-free SPSC ringbuffers, and hardware HALs (AAudio, CoreAudio, WASAPI, PipeWire).
   * **Master Presentation Clock**: Physical hardware DAC presentation timestamps (`DacPresentationClock < 5ms`).
   * **SLYR Synchronized Lyrics**: Wiener-Khinchin phase correlation and sub-millisecond syllable bytecode compiler.
   * **Supabase Cloud Infrastructure**: PostgREST, Realtime channels, auth, user profiles, telemetry, and continuous cloud backup for Wrapped analytics.
   * **Dolby Atmos & Spatial Audio**: Multiplatform hardware capability probe and spatial matching engine.
   * **Admin Command Center**: Server-verified dashboard and live diagnostic terminal accessible via Settings.
   * **Reliable Social Listening (Jam & Blend)**: WebSocket relay (`metroproto`) + Supabase Realtime fallback, integrated with AI Taste profiling and multi-user Blend generation.
   * **Dynamic Hybrid Home Screen**: Interleaved fusion of YouTube Music shelves with Supabase Community Top 50, Circadian Daypart curves, and Friend Blends.
3. **Upstream Immunity**: Isolate all custom logic in dedicated extension modules (`streamify-modules/`) and inject them via **Koin Inversion-of-Control (IoC)**, ensuring seamless upstream updates (`git fetch upstream && git merge upstream/dev`) without merge conflicts.
4. **Preserve Current Android Codebase**: Move `app/` $\to$ `legacy/app/` so zero existing features or historical code are lost.
5. **Lean CI Pipeline**: Streamline CI from an unwieldy 30-minute matrix down to a $< 4$-minute PR lane with path-filtered native caching.

---

## 2. High-Level System Architecture

```
┌─────────────────────────────────────────────────────────────────────────────────────────┐
│                                   PRESENTATION LAYER                                    │
│   • Android (Compose)  │  Desktop (Compose Multiplatform)  │  Apple iOS (Pure SwiftUI)  │
└──────────────────────────────────────────┬──────────────────────────────────────────────┘
                                           │
                         (Koin IoC Resolution & Module Overrides)
                                           │
                                           ▼
┌─────────────────────────────────────────────────────────────────────────────────────────┐
│                    STREAMIFY DOWNSTREAM EXTENSIONS (streamify-modules/)                 │
│                                                                                         │
│   ┌─────────────────────┐ ┌────────────────────┐ ┌──────────────────┐ ┌───────────────┐ │
│   │:streamify-media-hal │ │:streamify-lyrics   │ │:streamify-cloud  │ │:streamify-jam │ │
│   │• AudioProcessor     │ │• SLYR Compiler     │ │• Supabase Client │ │• WSS Relay    │ │
│   │• C++20 Ringbuffer   │ │• Sub-ms Syllable   │ │• Admin Terminal  │ │• Supabase     │ │
│   │• Dolby Atmos Probe  │ │  Sync              │ │• Wrapped Backup  │ │  Realtime     │ │
│   │• DacClock Publisher │ │• Wiener-Khinchin   │ │• Dynamic Home    │ │• PTP Sync     │ │
│   └──────────┬──────────┘ └─────────┬──────────┘ └─────────┬────────┘ └───────┬───────┘ │
└──────────────┼──────────────────────┼──────────────────────┼──────────────────┼─────────┘
               │                      │                      │                  │
               ▼                      ▼                      ▼                  ▼
┌─────────────────────────────────────────────────────────────────────────────────────────┐
│                               SOVEREIGN NATIVE CORE ENGINE                              │
│   Rust Core (libstreamify_core_rs)          │  C++20 Core (libstreamify_native_core)    │
│   • Symphonia Decoders (AAC, MP4, OGG, WAV) │  • ITU-R BS.1770-4 LUFS Normalizer        │
│   • Rubato SincFixedOut 48kHz Resampler     │  • True-Peak Soft-Knee Limiter            │
│   • SQLCipher Encrypted Storage             │  • Lock-Free SPSC Ringbuffer (f32)        │
│   • DacPresentationClock                    │  • Hardware HALs (WASAPI, PipeWire, Oboe) │
└─────────────────────────────────────────────────────────────────────────────────────────┘
```

---

## 3. Detailed Component Invariants

### 3.1 Upstream Immunity Strategy
* Upstream SimpMusic repository is mirrored on the `upstream-tracking` branch.
* Upstream files in `composeApp/`, `core/data/`, `core/domain/`, etc., remain untouched.
* All custom features are housed in `streamify-modules/`:
  * `:streamify-core-bridge`: JNI and UniFFI JNA bindings to the Rust/C++ native libraries.
  * `:streamify-media-hal`: Audio processor interceptors, ringbuffers, Dolby Atmos manager.
  * `:streamify-lyrics-slyr`: SLYR bytecode compiler and syllable alignment renderer.
  * `:streamify-supabase`: PostgREST, Realtime, auth, admin console, Wrapped backup.
  * `:streamify-jam`: WebSocket + Supabase Realtime shared listening and Blend generator.
* In `settings.gradle.kts`, `streamify-modules/` are included conditionally.
* At runtime, Koin loads our override modules:
  ```kotlin
  loadKoinModules(listOf(
      streamifyNativeModule,
      streamifyMediaModule,
      streamifyCloudModule,
      streamifyLyricsModule
  ))
  ```

### 3.2 Audio Data Plane & C++20 DSP / Hardware HAL
* **Canonical Format**: All audio is decoded to **48 kHz 32-bit float (`f32`) stereo** via Symphonia and Rubato polyphase sinc resampling.
* **DSP Processing**: Passed through C++20 `LufsNormalizer` (ITU-R BS.1770-4 with click-free $\pm 12\text{ dB}$ EMA gain glide) and `SoftKneeLimiter` (4x oversampled true-peak sidechain, $\tanh$ knee, zero overshoot).
* **Buffer Management**: Ingested directly into target-aligned (64B x86 / 128B Apple Silicon) lock-free SPSC ringbuffers without crossing GC runtimes or triggering allocations on the audio thread.
* **Hardware Output**:
  * Android: AAudio / Oboe C++ sinks.
  * Desktop: WASAPI (Windows) and PipeWire/ALSA (Linux) via `streamify_desktop_hal`.
  * Apple: `CoreAudioRenderCore` via RemoteIO/AUHAL.

### 3.3 Dolby Atmos & Spatial Audio System
* `DolbySpatialManager` probes Android 13+ `Spatializer` (`isAvailable`, `isHeadTrackerAvailable`) and queries `AudioEffect.queryEffects()` for OEM DSP hardware descriptors (`name`/`implementor` containing `"dolby"`, `"atmos"`, `"dap"`, or UUID `46d279d9-9be7-453d-9d7c-ef937f675587`).
* Integrated into the Equalizer screen (`AutoEqPicker` / `EqualizerScreen`) displaying hardware spatializer status and driving `SmartAcousticEngine` mastering curves.

### 3.4 Admin Command Center in Settings
* Gated by verified server-side claims (`profiles.is_admin == true`).
* Injected into SimpMusic’s `SettingsScreen` as an **"Admin Command Center"** card.
* Routes to `AdminDashboardScreen` (telemetry, active streams, server status) and `AdminTerminalScreen` (live correlation ID logs, session termination, `NuclearResetManager`).

### 3.5 Wrapped Analytics & Supabase Cloud Backup
* SimpMusic's 10-card Compose Wrapped UI (`WrappedScreen.kt`) is fully retained.
* Decorates `AnalyticsRepositoryImpl` so that every qualifying play event ($\ge 30\text{s}$) synchronizes to Supabase in the background.
* Annual Wrapped snapshots upload to `user_wrapped_snapshots`, guaranteeing zero data loss across devices or app re-installs.

### 3.6 Social Listening (Jam & Blend)
* **Replaces Unreliable UDP**: The fragile serverless P2P UDP mesh is replaced with a **WebSocket relay (`metroproto`)** backed by **Supabase Realtime Channels**.
* **Master Clock Lock**: Connected devices coordinate playback positions paced by `DacPresentationClock`.
* **Blend Engine**: Merges two users' `TasteInput` listening distributions (genres, top artists, dayparts) via a Supabase Edge Function to generate dynamically updating shared Blend playlists on the Home screen.

### 3.7 Dynamic Hybrid Home Screen
* Decorates SimpMusic's `HomeRepository` to interleave YouTube Music shelves with Supabase cloud shelves:
  * Shelf 0: **Quick Picks** (SimpMusic local history / YouTube Music)
  * Shelf 1: **Circadian Daypart Flow** (Streamify $C^1$-continuous energy/valence curves)
  * Shelf 2: **Global Community Hits** (Supabase top streams)
  * Shelf 3: **Friend Blend** (Shared dynamic blend)
  * Shelves 4+: **Trending Songs, New Releases & Explore Albums** (YouTube Music)

### 3.8 Queue Lifecycle & JIT Lookahead
* **0 ms Playback**: Tapping any song immediately plays track #1 while `asyncBuildRadio` fetches RDAMVM radio in the background.
* **Dual-Tier Fallback**: If RDAMVM returns empty, automatically short-circuits to `OnlineRadioEngine` (semantic/harmonic candidate mix).
* **Lookahead Pre-Buffering**: Tracks $N+1$ and $N+2$ pre-buffer into the C++ ringbuffer for gapless playback.
* **Room Recovery**: Queue state is persisted in Room (`QueueEntity`), fully restoring playback state across app restarts.
* **Album Crossfade Rule**: Crossfade is suppressed during album play and auto-engages when transitioning to endless radio.

---

## 4. Lean CI Strategy

* **Tier 1 (Fast PR Gate, $< 4$ min)**:
  * Uses Dorny's `paths-filter` to detect if native files (`rust/`, `native/`) changed.
  * If native code is unchanged, downloads pre-built native libraries (`.so`, `.dll`, `.xcframework`) from GitHub Release / cache.
  * Runs Kotlin unit tests (`testDebugUnitTest`, desktop JVM tests) and builds debug APK / desktop jar.
* **Tier 2 (Nightly / Tagged Release)**:
  * Compiles full 4-ABI Android NDK artifacts and multi-OS desktop binaries.
  * Runs C++20 LibFuzzer hunt and AddressSanitizer (ASan) / UndefinedBehaviorSanitizer (UBSan) suites.
  * Executes KVM Android emulator connected test lanes.
