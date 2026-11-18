# Universal Multiplatform Migration Execution Ledger

**Document ID:** `LEDGER-UNIV-MP-001`  
**Governing Plan:** `docs/universal_migration/MASTER_PLAN.md`  
**Integration Branch:** `integration/universal-multiplatform`  
**Last Updated:** 2026-11-18  

---

## 📊 Phase Overview & Status

| Phase | Description | Status | Target |
|---|---|---|---|
| **Phase 0** | **Branch & Preservation**: Branch setup, `/app` archival to `legacy/app/`, documentation initialization. | 🟡 In Progress | Day 1 |
| **Phase 1** | **CMP Foundation & Skeleton**: Import SimpMusic CMP modules into tree, verify Gradle multi-module sync. | ⚪ Pending | Day 2 |
| **Phase 2** | **Native DSP & HAL Seam**: Wire C++20 LUFS/Limiter, SPSC ringbuffer, and `DacPresentationClock` into playback port. | ⚪ Pending | Day 3 |
| **Phase 3** | **Dolby Atmos & SLYR Lyrics**: Port `DolbySpatialManager`, integrate SLYR bytecode compiler into lyrics pipeline. | ⚪ Pending | Day 4 |
| **Phase 4** | **Supabase & Admin Console**: Wire PostgREST, Realtime, Admin Command Center in Settings, and Wrapped backup. | ⚪ Pending | Day 5 |
| **Phase 5** | **Social (Jam/Blend) & Hybrid Home**: Replace P2P UDP Jam with WSS/Supabase Jam, add Blend engine & dynamic Home shelves. | ⚪ Pending | Day 6 |
| **Phase 6** | **CI Streamlining & Final Verification**: Implement path-filtered $<4$ min CI PR lane, end-to-end verification, merge to `main`. | ⚪ Pending | Day 7 |

---

## 🛠️ Detailed Checklist & Acceptance Criteria

### Phase 0: Branch & Preservation
- [x] Create integration branch `integration/universal-multiplatform`.
- [x] Create dedicated documentation directory `docs/universal_migration/`.
- [x] Write `MASTER_PLAN.md` and `MIGRATION_EXECUTION_LEDGER.md`.
- [ ] Move `/app` to `legacy/app` via git move to preserve history:
  ```bash
  git mv app legacy/app
  ```
- [ ] Ensure existing native C++ (`native/`) and Rust (`rust/`) directories remain intact at the repository root.

**Acceptance Criteria**:
* Clean `git status` on branch `integration/universal-multiplatform`.
* Legacy Android code intact under `legacy/app/`.

---

### Phase 1: CMP Foundation & Skeleton Setup
- [ ] Establish `upstream-tracking` branch tracking `https://github.com/maxrave-dev/SimpMusic.git`.
- [ ] Set up `streamify-modules/` directory structure for custom downstream extensions.
- [ ] Import SimpMusic multi-module Gradle layout (`:composeApp`, `:desktopApp`, `:androidApp`, `:core:*`).
- [ ] Configure `settings.gradle.kts` to conditionally include `streamify-modules/`.
- [ ] Verify `./gradlew tasks` resolves cleanly on Android and Desktop JVM.

**Acceptance Criteria**:
* `:androidApp:assembleDebug` succeeds.
* `:desktopApp:run` boots a basic Compose Multiplatform window.

---

### Phase 2: Native Audio, C++20 DSP & HAL Seam
- [ ] Create `:streamify-modules:media-hal` module.
- [ ] Implement `StreamifyAudioProcessor` binding ExoPlayer to `libstreamify_native_core.so`:
  - ITU-R BS.1770-4 LUFS Normalizer (`LufsNormalizer`).
  - True-Peak Soft-Knee Limiter (`SoftKneeLimiter`).
- [ ] Wire C++ SPSC cacheline-aligned ringbuffer for direct `f32` stereo audio drain.
- [ ] Hook `DacPresentationClock` into SimpMusic playback seekbars and progress states ($<5\text{ ms}$ jitter).
- [ ] Implement Desktop HAL adapter over `streamify_desktop_hal` (WASAPI / PipeWire) replacing libmpv.
- [ ] Verify album-boundary crossfade rules (`player.albumTrackIds`).

**Acceptance Criteria**:
* C++20 DSP test suites (`native-dsp`, `native-lufs-parity`) pass.
* Playback on Android and Desktop outputs normalized 48 kHz `f32` audio without audio thread allocations.

---

### Phase 3: Dolby Atmos & SLYR Syllable Lyrics
- [ ] Port `DolbySpatialManager.kt` into `:streamify-modules:media-hal`:
  - Query Android 13+ `Spatializer` (`isAvailable`, `isHeadTrackerAvailable`).
  - Query OEM Dolby processing UUID (`46d279d9-9be7-453d-9d7c-ef937f675587`).
- [ ] Inject Dolby Atmos status badge into SimpMusic's `EqualizerScreen` / `AutoEqPicker`.
- [ ] Create `:streamify-modules:lyrics-slyr` module.
- [ ] Implement `StreamifySlyrLyricsDecorator` wrapping SimpMusic's `SimpMusicLyricsClient`:
  - Compile raw LRC timestamps into Wiener-Khinchin phase-aligned SLYR bytecode.
  - Sub-millisecond syllable-by-syllable lyric rendering on Compose canvas.

**Acceptance Criteria**:
* Dolby Atmos hardware detection verified on supported Android devices.
* Syllable-accurate lyrics render smoothly at 60/120 FPS.

---

### Phase 4: Supabase Cloud & Admin Center
- [ ] Create `:streamify-modules:supabase` module.
- [ ] Wire Supabase PostgREST client, Realtime client, and Auth state machine.
- [ ] Port `AdminDashboardScreen.kt`, `AdminTerminalScreen.kt`, and `SupabaseAdminClient.kt`.
- [ ] Inject **"Admin Command Center"** card into SimpMusic's `SettingsScreen` (gated by `profiles.is_admin == true`).
- [ ] Hook Supabase play-event logger into `AnalyticsRepositoryImpl` for continuous listening telemetry.
- [ ] Connect annual Wrapped snapshots to Supabase cloud storage, keeping SimpMusic's 10-card UI intact.

**Acceptance Criteria**:
* Non-admin users see zero admin cards in Settings.
* Verified admins can view telemetry, server health, and live log stream.
* Wrapped listening stats persist in Supabase across devices.

---

### Phase 5: Social Listening (Jam & Blend) & Dynamic Home
- [ ] Replace fragile serverless UDP mesh with SimpMusic's WebSocket relay (`metroproto`) + Supabase Realtime channel.
- [ ] Synchronize multi-device playhead progression using `DacPresentationClock`.
- [ ] Implement Taste Profiler & Blend generator via Supabase Edge Functions.
- [ ] Create `HybridHomeRepository` decorating SimpMusic's `HomeRepository`:
  - Shelf 0: Quick Picks (YouTube Music / SimpMusic cache).
  - Shelf 1: Circadian Daypart Flow (Streamify $C^1$-continuous curves).
  - Shelf 2: Global Community Hits (Supabase telemetry).
  - Shelf 3: Shared Friend Blend (Dynamic Supabase blend).
  - Shelves 4+: Trending Songs, New Releases & Explore Albums (YouTube Music).

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
