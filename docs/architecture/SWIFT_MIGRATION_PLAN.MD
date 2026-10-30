# Streamify Apple (iOS & macOS) Multiplatform Execution Ledger & Swift Mapping Plan

**Tracking Document:** `docs/architecture/SWIFT_MIGRATION_PLAN.MD`  
**Governing Master Plan:** [`docs/architecture/MULTIPLATFORM_PLAN_27_10.MD`](file:///data/data/com.termux/files/home/streamify-apk/docs/architecture/MULTIPLATFORM_PLAN_27_10.MD)  
**Execution Checkbox Companion:** [`docs/architecture/MULTIPLATFORM_CHECKBOX.MD`](file:///data/data/com.termux/files/home/streamify-apk/docs/architecture/MULTIPLATFORM_CHECKBOX.MD)  
**Date:** October 30, 2026  
**Target Environments:** iOS 17+, iPadOS 17+, macOS 14+ (Sonoma/Sequoia), watchOS 10+, CarPlay  
**Packaging Target:** Swift Package Manager (`iosApp/Package.swift`) targeting `StreamifyApple` library + host app wrapper  

---

## 🏛️ Part I: Architectural Consensus & Invariants

| Ref ID | Architectural Decision | Status | Consensus Invariant |
|---|---|---|---|
| **SW-DEC-01** | **Direct UniFFI Swift Bindings** | **APPROVED** | Zero Kotlin/Native (`cinterop`) or Obj-C wrappers (preserving `MP-DEC-02`). `StreamifyCore.xcframework` exposes `uniffi_streamify` directly to Swift. |
| **SW-DEC-02** | **Swift 5.9+ / 6 Observation (`@Observable`)** | **APPROVED** | Ban legacy `ObservableObject` / `@Published` / `Combine`. ViewModels use `@Observable` for fine-grained dependency tracking and 120Hz ProMotion smoothness. |
| **SW-DEC-03** | **Structured Swift Concurrency** | **APPROVED** | Strictly use `async/await`, `actor`, and `AsyncStream`. No raw GCD dispatch queues or external reactive libraries. |
| **SW-DEC-04** | **Shared Multiplatform SPM Shell** | **APPROVED** | Single Swift package (`StreamifyApple`) powering both iOS and macOS. Code sharing ceiling > 90%; platform divergence strictly isolated via `#if os(macOS)` / `#if os(iOS)`. |
| **SW-DEC-05** | **Zero State Duplication** | **APPROVED** | Audio decoding, DSP, true-peak limiter, RustyPipe search/extract, PTP clock math, and SQLite caching remain sovereign in Rust. Swift models reflect Rust snapshots via `EngineEventListener`. |
| **SW-DEC-06** | **Apple HIG First-Class Citizenship** | **APPROVED** | Native SF Pro, SF Symbols, Liquid Glass (`.ultraThinMaterial`), Dynamic Island (`ActivityKit`), Lock Screen (`MPNowPlayingInfoCenter`), and Mac Menu Bar. |

---

## 🗺️ Part II: Comprehensive Kotlin-to-Swift Mapping Matrix

Every Kotlin package and file in the Android codebase (311 production files) maps directly into its designated Swift/Apple component or Rust UniFFI interface:

### 1. Presentation, Screens & UI Components (110 Kotlin Files)

| Kotlin Source (`com.streamify.app.*`) | Swift Target (`iosApp/Sources/StreamifyApple/*`) | Target Platform & Architecture Notes |
|---|---|---|
| `ui.screens.UniversalHomeScreen`<br/>`ui.screens.HomeScreen` | `Views/Home/HomeView.swift`<br/>`Views/Home/DailyMixRail.swift` | Multiplatform LazyVStack with section carousels and friend listening ticker. |
| `ui.screens.SearchScreen` | `Views/Search/SearchView.swift`<br/>`Views/Search/FilterChipsView.swift` | Native `.searchable` integrating `StreamResolver::search_tracks` with instant query debouncing. |
| `ui.screens.PlayerScreen` | `Views/Player/FullPlayerView.swift`<br/>`Views/Player/WaveformProgressSlider.swift` | Interactive spring sheet, dynamic `MeshGradient` blur, and DAC clock scrub bar. |
| `ui.screens.QueueScreen` | `Views/Queue/QueueView.swift` | Native SwiftUI `List` with `.onMove` drag-and-drop reordering and swipe-to-delete. |
| `ui.screens.LyricsScreen` | `Views/Lyrics/TimedLyricsView.swift` | Millisecond-synced scrolling karaoke glow paced by `DacPresentationClock`. |
| `ui.screens.EqualizerScreen` | `Views/Audio/EqualizerView.swift` | 10-band parametric EQ sliders bound to `streamify-dsp` TPT SVF filters. |
| `ui.screens.LibraryScreen` | `Views/Library/LibraryView.swift` | Split-view navigation on macOS; segmented picker (Playlists/Albums/Liked) on iOS. |
| `ui.screens.ArtistScreen` | `Views/Artist/ArtistDetailView.swift` | Parallax header art, top songs list, discography grid, and follow button. |
| `ui.screens.AlbumScreen` | `Views/Album/AlbumDetailView.swift` | Collapsible header, track duration totals, play/shuffle actions, offline toggle. |
| `ui.screens.JamSessionScreen` | `Views/Jam/JamSessionView.swift`<br/>`Views/Jam/ParticipantPillView.swift` | Host/join modal, QR code generation, live participant list with volume gauges. |
| `ui.screens.BlendScreen` | `Views/Social/BlendView.swift` | Taste overlap circular gauge using `Swift Charts`, shared playlist bootstrap. |
| `ui.screens.DownloadScreen` | `Views/Storage/DownloadsView.swift` | Offline storage meter breakdown (audio chunks vs artwork cache) and batch management. |
| `ui.screens.StatsWrappedScreen` | `Views/Profile/StatsWrappedView.swift` | Year-in-review interactive infographics built with `Swift Charts`. |
| `ui.screens.UserProfileScreen`<br/>`ui.screens.ProfileSelectionScreen` | `Views/Profile/UserProfileView.swift`<br/>`Views/Profile/ProfileSwitcherView.swift` | Public profile display, listening badges, and active profile switcher. |
| `ui.screens.SettingsScreen` | `Views/Settings/SettingsView.swift` | Grouped `Form` with audio quality toggles (128k/256k Opus), buffer tuners, and cache cleaner. |
| `ui.screens.UpdatesHubScreen`<br/>`ui.screens.CommunityHubScreen` | `Views/Social/UpdatesFeedView.swift`<br/>`Views/Social/CommunityHubView.swift` | Chronological release radar feed and public community playlist explorer. |
| `ui.screens.AdminDashboardScreen`<br/>`ui.screens.AdminTerminalScreen` | `Views/Diagnostics/DiagnosticsView.swift`<br/>`Views/Diagnostics/LiveTerminalView.swift` | Real-time SLog output stream, PTP drift graphs, and CoreAudio buffer latency profiler. |
| `ui.components.yt.*` (36 files) | `Views/Components/YTStyle/*` | Clean Swift component library: glassmorphic top bars, mood pills, taste wheels, sticky headers. |
| `ui.components.*` (34 files) | `Views/Components/Common/*` | Shared UI primitives: `CanvasVideoSurface`, `ArtistCircleCard`, `VolumeSlider`, `DynamicPaletteMesh`. |
| `ui.motion.*` & `ui.animations.*` (7 files) | `Motion/SpringInterpolators.swift`<br/>`Motion/ViewTransitions.swift` | Native SwiftUI interactive spring animations and fluid gesture dismissals. |
| `ui.theme.*` (6 files) | `Theme/StreamifyTheme.swift`<br/>`Theme/AppColors.swift` | Semantic color palette and asset catalog integration supporting automatic Dark/Light appearance. |

---

### 2. ViewModels & UI State Machines (14 Kotlin Files)

| Kotlin Source (`com.streamify.app.viewmodel.*`) | Swift Target (`iosApp/Sources/StreamifyApple/ViewModels/*`) | Pattern & State Architecture |
|---|---|---|
| `PlayerViewModel.kt` | `PlaybackViewModel.swift` | `@Observable @MainActor`: Consumes Rust `EngineEventListener` push events; drives UI clock interpolation. |
| `SearchViewModel.kt` | `SearchViewModel.swift` | `@Observable`: Debounced query dispatch to `StreamResolver::search_tracks`; manages filter states. |
| `HomeViewModel.kt` | `HomeViewModel.swift` | `@Observable`: Aggregates personalized feeds, daily mix carousels, and circadian recommendations. |
| `QueueViewModel.kt` | `QueueViewModel.swift` | `@Observable`: Real-time queue view model; dispatches reorder and remove mutations to Rust core. |
| `JamViewModel.kt` | `JamViewModel.swift` | `@Observable`: Coordinates PTP sync status, participant presence, and shared queue governance. |
| `LyricsViewModel.kt` | `LyricsViewModel.swift` | `@Observable`: Fetches synced LRC lines, drives smooth auto-scroll, and tracks karaoke timings. |
| `EqualizerViewModel.kt` | `EqualizerViewModel.swift` | `@Observable`: Bridges 10-band gain offsets and limiter toggles to `streamify-dsp`. |
| `BlendViewModel.kt` | `BlendViewModel.swift` | `@Observable`: Taste match calculator and shared playlist generator. |
| `CommunityViewModel.kt` | `CommunityViewModel.swift` | `@Observable`: Connects to community feeds and friend activity tickers. |
| `SettingsViewModel.kt` | `SettingsViewModel.swift` | `@Observable`: Manages stream quality, offline storage limits, and diagnostic switches. |
| `StatsWrappedViewModel.kt` | `StatsWrappedViewModel.swift` | `@Observable`: Queries historical playback metrics from SQLite for charts and telemetry. |
| `UpdatesHubViewModel.kt` | `UpdatesHubViewModel.swift` | `@Observable`: Polls artist release trackers for latest singles and albums. |
| `UserProfileViewModel.kt` | `UserProfileViewModel.swift` | `@Observable`: Public user stats, followed artists, and playlist ownership. |
| `IngestionViewModel.kt` | `IngestionViewModel.swift` | `@Observable`: Coordinates background import of external playlists (Spotify, Exportify). |

---

### 3. Audio, DSP, Sync & Hardware Drivers (33 Kotlin Files)

| Kotlin Source (`com.streamify.app.media.*`) | Swift / Apple Target (`iosApp/Sources/StreamifyApple/Platform/*`) | Platform Driver & Hardware Architecture |
|---|---|---|
| `media.audio.AudioPipeline`<br/>`media.audio.RustAudioPipeline` | `Platform/Audio/CoreAudioRenderCore.swift`<br/>`native/audio/coreaudio_pull_adapter.cpp` | Native C++/Swift `AURenderCallback` pulling 48kHz `f32` from `AudioSink` (preserving `MP-HAL04`). |
| `media.audio.AudioRoutePolicy`<br/>`media.audio.AudioDeviceManager` | `Platform/Audio/IOSAudioSessionAdapter.swift` | `AVAudioSession` configuration (`.playback`, `.longFormAudio`), route change handling (`.oldDeviceUnavailable` pause), and AirPlay routing. |
| `media.audio.CrossfadeAudioProcessor`<br/>`media.audio.PcmIngestResampler` | **Sovereign in Rust Core**<br/>(`streamify-audio` + `rubato`) | Producer-side polyphase sinc resampling (canonical 48kHz `f32`) and sample-accurate crossfading. |
| `media.audio.DacClockBridge` | `Platform/Audio/DacClockPublisher.swift` | Bridges `DacPresentationClock` monotonic hardware timestamps to Swift `CADisplayLink` at 120Hz. |
| `media.sync.PrecisionTimeProtocol`<br/>`media.sync.PhaseLockedLoopController` | **Sovereign in Rust Core**<br/>(`streamify-jam`) | IEEE 1588 PTP master/slave synchronization engine; Swift observes monotonic offsets. |
| `media.playback.MediaCommandDispatcher` | `Platform/Remote/RemoteCommandCenterAdapter.swift` | `MPRemoteCommandCenter` integration for Play, Pause, Toggle, Next, Prev, Seek, Scrobble with 100ms debounce. |
| `media.lyrics.LyricPlaybackController` | `Services/Lyrics/LyricPacerService.swift` | High-precision time sync matching lyrics offsets to CoreAudio frame presentation. |

---

### 4. Data, Discovery, Ingestion & Networking (74 Kotlin Files)

| Kotlin Source (`com.streamify.app.data.*`) | Swift / Apple Target (`iosApp/Sources/StreamifyApple/Services/*`) | Implementation Strategy |
|---|---|---|
| `data.network.YouTubeMusicSearchApi`<br/>`data.network.YouTubeStreamResolver` | **Sovereign in Rust Core**<br/>(`streamify-resolver`) | 100% pure Rust RustyPipe engine via UniFFI direct call (zero Kotlin or Java dependencies). |
| `data.network.CanvasScraperApi`<br/>`data.network.ArtistClipsApi` | `Services/Network/CanvasScraperService.swift` | Swift `actor` with `URLSession` and async/await scraping video loop URLs. |
| `data.discovery.AntiDriftScoringEngine`<br/>`data.discovery.DaylistScheduler` | `Services/Discovery/DaylistScheduleEngine.swift` | Ported pure Swift algorithmic service mapping circadian clock to genre vectors. |
| `data.download.StreamifyDownloadManager`<br/>`data.download.ResumableDownloadEngine` | `Services/Storage/BackgroundDownloadService.swift` | Native `URLSessionDownloadTask` using `URLSessionConfiguration.background` for persistent background downloads. |
| `data.spotify.SpotifyAuthManager`<br/>`data.spotify.SpotifySecureStore` | `Services/Auth/AppleKeychainStore.swift`<br/>`Services/Auth/SpotifyAuthService.swift` | Apple Keychain Services (`Security.framework`) replacing Android KeyStore. |
| `data.persistence.DatabaseInitializer`<br/>`data.persistence.SmartOfflineVaultEngine` | **Sovereign in Rust Core**<br/>(`streamify-storage`) | UniFFI access to embedded SQLite track, playlist, and audio chunk database. |

---

### 5. Jam (Listen Together) & Connect (22 Kotlin Files)

| Kotlin Source (`com.streamify.app.jam.*` / `connect.*`) | Swift / Apple Target (`iosApp/Sources/StreamifyApple/Jam/*`) | Hardware & Network Integration |
|---|---|---|
| `jam.BleJoinPayload`<br/>`jam.JamPairing` | `Jam/Bluetooth/CoreBluetoothJamBeacon.swift` | `CoreBluetooth` (`CBCentralManager` / `CBPeripheralManager`) for proximity discovery and zero-click join. |
| `jam.JamEngine`<br/>`jam.JamTopologyMachine` | **Sovereign in Rust Core**<br/>(`streamify-jam`) | PTP clock coordination, dynamic host election, and CRDT queue updates. |
| `connect.ConnectDeviceRegistry`<br/>`connect.LanConnectGateway` | `Jam/Connect/BonjourDeviceDiscovery.swift` | Native Apple `Network.framework` (`NWBrowser` / `NWListener`) for Bonjour/mDNS LAN device pairing. |

---

### 6. Platform Integrations & OS Ecosystem (9 Kotlin Files)

| Android Source (`com.streamify.app.*`) | Apple Native Replacement (`iosApp/Sources/StreamifyApple/Platform/*`) | Capability Details |
|---|---|---|
| `widget.StreamifyAppWidgetProvider` | `Platform/Widgets/StreamifyWidgetBundle.swift` | **WidgetKit**: Home Screen & Desktop widgets displaying current track and quick play controls. |
| `media.playback.PlaybackService` (Notification) | `Platform/LiveActivity/PlaybackLiveActivity.swift` | **ActivityKit**: Interactive Dynamic Island and Lock Screen Live Activity on iPhone 14 Pro+. |
| `auto.StreamifyAutoMediaBrowserService` | `Platform/CarPlay/CarPlaySceneDelegate.swift` | **CarPlay**: `CPTemplateApplicationSceneDelegate` with native audio browse and playback templates. |
| `wear.*` (5 WearOS files) | `WatchApp/StreamifyWatchApp.swift` (Future target) | **watchOS App Target**: Standalone remote control and offline playback using shared Swift models. |

---

## 👥 Part III: 4-Engineer Workstream Allocation & Checkbox Ledger

```
┌────────────────────────────────────────────────────────────────────────┐
│                        4-ENGINEER RESPONSIBILITY LEDGER                │
├─────────────────────┬──────────────────────────────────────────────────┤
│ ENGINEER 1:         │ Core FFI, Storage, Network Engines & Packaging   │
│ Foundation & Data   │ • UniFFI Codegen, XCFramework CI, SQLite & APIs  │
├─────────────────────┼──────────────────────────────────────────────────┤
│ ENGINEER 2:         │ Design System, Navigation Shell & 30 Screens     │
│ UI & Presentation   │ • Multiplatform SwiftUI Views, Layout & Motion   │
├─────────────────────┼──────────────────────────────────────────────────┤
│ ENGINEER 3:         │ Audio Engine, Hardware HAL & Apple OS Ecosystem  │
│ Audio & Platform    │ • CoreAudio, AVAudioSession, Island & CarPlay    │
├─────────────────────┼──────────────────────────────────────────────────┤
│ ENGINEER 4:         │ 14 ViewModels, Jam BLE/PTP & Social Features     │
│ State & Features    │ • Observation Models, Jam Sync, Blend & Lyrics   │
└─────────────────────┴──────────────────────────────────────────────────┘
```

---

### ⚙️ Engineer 1: Foundation, UniFFI Bridge & Data Engines
* **Lead:** Engineer 1 (Core & Data Specialist)
* **Status:** In Progress

- [ ] **SW-ENG1-01**: Configure `iosApp/Package.swift` as a universal library targeting both `.iOS(.v17)` and `.macOS(.v14)`.
- [ ] **SW-ENG1-02**: Upgrade [`scripts/build_ios_xcframework.sh`](file:///data/data/com.termux/files/home/streamify-apk/scripts/build_ios_xcframework.sh) to emit a universal fat `StreamifyCore.xcframework` bundling `aarch64-apple-ios`, `aarch64-apple-ios-sim`, `x86_64-apple-ios-sim`, and `aarch64-apple-darwin` + `x86_64-apple-darwin` (macOS).
- [ ] **SW-ENG1-03**: Expand `rust/src/streamify.udl` and UniFFI exports to expose full `Track`, `Playlist`, `QueueItem`, `SearchResult`, `EqualizerBand`, and `JamState` structs.
- [ ] **SW-ENG1-04**: Validate `EngineEventListener` callback dispatch from Rust worker threads into Swift `AsyncStream`.
- [ ] **SW-ENG1-05**: Implement `AppleKeychainStore.swift` replacing Android `EncryptedSharedPreferences` for secure session token storage.
- [ ] **SW-ENG1-06**: Port `CanvasScraperApi` and `ArtistClipsApi` into async Swift actors with HTTP/2 caching.
- [ ] **SW-ENG1-07**: Port `DaylistScheduler.kt` and `AntiDriftScoringEngine.kt` to Swift domain services.
- [ ] **SW-ENG1-08**: Implement `BackgroundDownloadService.swift` using `URLSessionConfiguration.background` for persistent audio caching.

---

### 🎨 Engineer 2: Design System, Multiplatform Shell & 30 Screens
* **Lead:** Engineer 2 (SwiftUI & HIG Specialist)
* **Status:** In Progress

- [ ] **SW-ENG2-01**: Implement `AppTheme.swift` with dynamic semantic colors, glassmorphic styles, and SF Symbols.
- [ ] **SW-ENG2-02**: Build `RootShellView.swift`: `NavigationStack` with tab bar on iOS; `NavigationSplitView` with sidebar rail on macOS.
- [ ] **SW-ENG2-03**: Build **HomeView** with infinite scrolling feed, mood chips, and daily mix carousels.
- [ ] **SW-ENG2-04**: Build **SearchView** with native `.searchable`, search filter pills (Songs, Artists, Albums), and recent search chips.
- [ ] **SW-ENG2-05**: Build **FullPlayerView** modal sheet with album art `MeshGradient`, progress scrubber, repeat/shuffle buttons, and lyrics toggle.
- [ ] **SW-ENG2-06**: Build **QueueView** with native list drag-and-drop (`.onMove`) and swipe-to-delete.
- [ ] **SW-ENG2-07**: Build **LibraryView**, **ArtistDetailView**, and **AlbumDetailView** with parallax hero images.
- [ ] **SW-ENG2-08**: Build **SettingsView**, **UpdatesFeedView**, and **DiagnosticsView** (live SLog stream).
- [ ] **SW-ENG2-09**: Port all 36 YouTube Music specific components (`ui.components.yt.*`) to modular SwiftUI views.
- [ ] **SW-ENG2-10**: Ensure 120Hz ProMotion render performance across all scrollable lists.

---

### 🔊 Engineer 3: Audio Engine, Hardware HAL & Apple OS Ecosystem
* **Lead:** Engineer 3 (Audio & Platform Integration Specialist)
* **Status:** In Progress

- [ ] **SW-ENG3-01**: Harden `native/audio/coreaudio_pull_adapter.cpp` and implement `CoreAudioRenderCore.swift` pulling `f32` samples from `AudioSink`.
- [ ] **SW-ENG3-02**: Implement `IOSAudioSessionAdapter.swift`: activate `.playback` category, listen for interruptions (`.began` / `.ended`), and handle route changes (`.oldDeviceUnavailable` pause on headphone disconnect).
- [ ] **SW-ENG3-03**: Implement `RemoteCommandCenterAdapter.swift` with `MPRemoteCommandCenter` handlers (Play, Pause, Toggle, Next, Prev, ChangePlaybackPosition).
- [ ] **SW-ENG3-04**: Implement `NowPlayingInfoPublisher.swift` pushing title, artist, artwork, playback rate, and elapsed time to `MPNowPlayingInfoCenter` (rate-limited to 1Hz).
- [ ] **SW-ENG3-05**: Implement **Dynamic Island & Live Activity** (`ActivityKit`) displaying live waveform animation and mini-controls.
- [ ] **SW-ENG3-06**: Implement **WidgetKit** widgets for iOS Home Screen, Lock Screen, and macOS Notification Center.
- [ ] **SW-ENG3-07**: Implement **CarPlay** template scene delegate (`CPTemplateApplicationSceneDelegate`).
- [ ] **SW-ENG3-08**: Implement AirPlay 2 video/audio routing and volume synchronization.

---

### 🧠 Engineer 4: ViewModels, Jam BLE/PTP & Social Features
* **Lead:** Engineer 4 (State & Connectivity Specialist)
* **Status:** In Progress

- [ ] **SW-ENG4-01**: Implement 14 `@Observable @MainActor` ViewModels (`PlaybackViewModel`, `SearchViewModel`, `HomeViewModel`, `QueueViewModel`, `JamViewModel`, `LyricsViewModel`, `EqualizerViewModel`, `BlendViewModel`, `CommunityViewModel`, `SettingsViewModel`, `StatsWrappedViewModel`, `UpdatesHubViewModel`, `UserProfileViewModel`, `IngestionViewModel`).
- [ ] **SW-ENG4-02**: Wire `DacPresentationClock` push ticks into `PlaybackViewModel` to drive smooth progress bar interpolation without polling.
- [ ] **SW-ENG4-03**: Build **TimedLyricsView** with millisecond karaoke highlighting and smooth auto-scroll.
- [ ] **SW-ENG4-04**: Implement **CoreBluetoothJamBeacon.swift** (`CBCentralManager`/`CBPeripheralManager`) for zero-touch Jam session discovery.
- [ ] **SW-ENG4-05**: Implement **BonjourDeviceDiscovery.swift** via Apple `Network.framework` for LAN Connect device discovery.
- [ ] **SW-ENG4-06**: Build **JamSessionView** with live participant list, latency gauges, and queue voting.
- [ ] **SW-ENG4-07**: Build **BlendView** with `Swift Charts` circular overlap indicator and shared playlist generator.
- [ ] **SW-ENG4-08**: Build **StatsWrappedView** with interactive listening time bar charts and top artist highlights.

---

## 🏁 Part IV: Verification Gates & CI Milestones

1. **Gate 1 (SPM Compilation):** `swift build` on `iosApp` succeeds with zero warnings across iOS and macOS targets.
2. **Gate 2 (UniFFI Linking):** UniFFI tests pass under XCTest, verifying `StreamifyCore.xcframework` loads and resolves streams via RustyPipe.
3. **Gate 3 (CoreAudio Parity):** `test_coreaudio_render_core` confirms `<5ms` latency estimation error and zero buffer underruns under 48kHz stereo playback.
4. **Gate 4 (UI Fluidity):** Xcode Instruments Time Profiler confirms zero main-thread hitching and sustained 120 FPS on ProMotion devices.
