# Universal Multiplatform Migration Execution Ledger

**Document ID:** `LEDGER-UNIV-MP-001`  
**Governing Plan:** `docs/universal_migration/MASTER_PLAN.md`  
**Integration Branch:** `integration/universal-multiplatform`  
**Last Updated:** 2026-11-18  

---

## 📊 Phase Overview & Status

| Phase | Description | Status | Target |
|---|---|---|---|
| **Phase 0** | **Branch & Preservation**: Branch setup, `/app` archival to `legacy/app/`, documentation initialization. | 🟢 Completed | Day 1 |
| **Phase 1** | **CMP Foundation & Skeleton**: Import SimpMusic CMP modules into tree, verify Gradle multi-module sync. | 🟢 Completed | Day 2 |
| **Phase 2** | **Native DSP & HAL Seam**: Wire C++20 LUFS/Limiter, SPSC ringbuffer, and `DacPresentationClock` into playback port. | 🟢 Completed | Day 3 |
| **Phase 3** | **Dolby Atmos & SLYR Lyrics**: Port `DolbySpatialManager`, integrate SLYR bytecode compiler into lyrics pipeline. | 🟢 Completed | Day 4 |
| **Phase 4** | **Supabase & Admin Console**: Wire PostgREST, Realtime, Admin Command Center in Settings, and Wrapped backup. | 🟢 Completed | Day 5 |
| **Phase 5** | **Social (Jam/Blend) & Hybrid Home**: Replace P2P UDP Jam with WSS/Supabase Jam, add Blend engine & dynamic Home shelves. | 🟢 Completed | Day 6 |
| **Phase 6** | **CI Streamlining & Final Verification**: Implement path-filtered $<4$ min CI PR lane, end-to-end verification, merge to `main`. | 🟡 In Progress | Day 7 |

---

## 🛠️ Detailed Checklist & Acceptance Criteria

### Phase 0: Branch & Preservation
- [x] Create integration branch `integration/universal-multiplatform`.
- [x] Create dedicated documentation directory `docs/universal_migration/`.
- [x] Write `MASTER_PLAN.md` and `MIGRATION_EXECUTION_LEDGER.md`.
- [x] Move `/app` to `legacy/app` via git move to preserve history:
  ```bash
  git mv app legacy/app
  ```
- [x] Update `settings.gradle.kts` to point `:app` to `legacy/app`.
- [x] Ensure existing native C++ (`native/`) and Rust (`rust/`) directories remain intact at the repository root.

**Acceptance Criteria**:
* Clean `git status` on branch `integration/universal-multiplatform`.
* Legacy Android code intact under `legacy/app/`.

---

### Phase 1: CMP Foundation & Skeleton Setup
- [x] Establish `upstream-tracking` branch tracking `https://github.com/maxrave-dev/SimpMusic.git`.
- [x] Merge upstream SimpMusic foundation into `integration/universal-multiplatform`.
- [x] Initialize `core` git submodule at commit `8413a257`.
- [x] Set up `streamify-modules/` directory structure for custom downstream extensions.
- [x] Configure `settings.gradle.kts` to conditionally include `streamify-modules/` and preserve `legacy/app`.

**Acceptance Criteria**:
* `:androidApp:assembleDebug` succeeds.
* `:desktopApp:run` boots a basic Compose Multiplatform window.

---

### Phase 2: Native Audio, C++20 DSP & HAL Seam
- [x] Create `:streamify-modules:core-bridge` with preserved JNI ABI contracts (`NativeBridge` and `NativeAudioSink`).
- [x] Create `:streamify-modules:media-hal` module.
- [x] Implement `StreamifyAudioProcessor` binding Media3 to the native DSP pipeline:
  - ITU-R BS.1770-4 LUFS Normalizer (`LufsNormalizer`).
  - True-Peak Soft-Knee Limiter (`SoftKneeLimiter`).
  - Mono downmix and stereo balance.
- [x] Wire C++ SPSC cacheline-aligned ringbuffer (`AudioSinkRingbufferBridge`) for direct `f32` stereo audio drain.
- [x] Hook `DacPresentationClock` (`DacClockBridge`) for sub-5ms playhead progression without wall-clock drift.
- [x] Implement Desktop HAL adapter (`DesktopHalAudioBridge`) over `streamify_desktop_hal` (WASAPI / PipeWire).

**Acceptance Criteria**:
* C++20 DSP test suites (`native-dsp`, `native-lufs-parity`) pass.
* Playback on Android and Desktop outputs normalized 48 kHz `f32` audio without audio thread allocations.

---

### Phase 3: Dolby Atmos & SLYR Syllable Lyrics
- [x] Port `DolbySpatialManager.kt` into `:streamify-modules:media-hal`:
  - Query Android 13+ `Spatializer` (`isAvailable`, `isHeadTrackerAvailable`).
  - Query OEM Dolby processing UUID (`46d279d9-9be7-453d-9d7c-ef937f675587`).
- [x] Create `DolbyAtmosCard` Composable displaying hardware stage status, spatializer mode, and head tracking.
- [x] Create `:streamify-modules:lyrics-slyr` module with `SlyrModels`.
- [x] Implement `SlyrBytecodeCompiler` parsing word-level timestamps (`<mm:ss.xx>`) into sub-millisecond syllable bytecode.
- [x] Implement `LyricsPlaybackEngine` with binary search line lookups and smooth syllable fill interpolation.

**Acceptance Criteria**:
* Dolby Atmos hardware detection verified on supported Android devices.
* Syllable-accurate lyrics render smoothly at 60/120 FPS.

---

### Phase 4: Supabase Cloud & Admin Center
- [x] Create `:streamify-modules:supabase` module with `SupabaseModels`.
- [x] Wire `SupabaseClient` with auth session management and verified server claim `isAdmin` (SEC-09).
- [x] Implement `SupabaseAdminClient` with privileged telemetry, session termination, and moderation hooks.
- [x] Implement `AdminCommandCenterCard` Composable for SettingsScreen (renders only if server `isAdmin == true`).
- [x] Implement `SupabaseWrappedSync` for continuous listening telemetry backup and annual Wrapped snapshots.

**Acceptance Criteria**:
* Non-admin users see zero admin cards in Settings.
* Verified admins can view telemetry, server health, and live log stream.
* Wrapped listening stats persist in Supabase across devices.

---

### Phase 5: Social Listening (Jam & Blend) & Dynamic Home
- [x] Replace fragile serverless UDP mesh with `StreamifyJamCoordinator` combining WebSocket room consensus with Supabase Realtime rooms.
- [x] Synchronize multi-device transport commands using `DacPresentationClock` monotonic anchors.
- [x] Implement `BlendGenerator` calculating multi-user Jaccard artist affinity and interleaved Blend playlists.
- [x] Implement `HybridHomeRepository` interleaving Quick Picks, Circadian Daypart curves, Global Community Hits, and Friend Blends into Home shelves.

**Acceptance Criteria**:
* Listen Together sessions connect reliably over 4G/5G mobile networks and restricted Wi-Fi.
* Dynamic home screen loads smoothly with hybrid cloud and YouTube shelves.

---

### Phase 6: CI Streamlining & Final Verification
- [ ] Reconfigure `.github/workflows/ci.yml` with path filtering (`dorny/paths-filter`).
- [ ] Skip native compilation on PRs if `rust/` and `native/` are unchanged; pull cached native binaries.
- [ ] Establish fast PR lane ($< 4\text{ min}$) running KMP unit tests + `assembleDebug`.
- [ ] Move LibFuzzer, KVM emulator tests, and full 4-ABI matrix to nightly / release workflows.
- [ ] Run full end-to-end verification suite across Android and Desktop.
- [ ] Submit final pull request from `integration/universal-multiplatform` to `main`.

**Acceptance Criteria**:
* PR CI gate completes in under 4 minutes on code-only changes.
* Full release matrix passes without regressions.
