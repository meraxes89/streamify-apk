# Streamify iOS (SW-ENG2 — Design System, Multiplatform Shell & 30 Screens)

**Adaptive SwiftUI shell · design system · 30 screens · 70 ported components · motion system**
Governing plan: `docs/architecture/SWIFT_MIGRATION_PLAN.MD` (SW-ENG2-01…10) ·
Ledger: `docs/architecture/MULTIPLATFORM_CHECKBOX.MD` (MP-UI01, MP-UI02, MP-UI04)

A Swift package carrying the ENTIRE Apple app surface — the adaptive
multiplatform shell (compact TabView + docked MiniPlayerBar on iPhone;
NavigationSplitView sidebar rail on iPad / Mac, SW-ENG2-02), the SW-ENG2
obsidian design system (dynamic Dark/Light tokens, SF Pro scale, SF Symbols 5
catalog, Apple Materials incl. NSVisualEffectView on macOS, SW-ENG2-01), the
full 30-screen surface (SW-ENG2-03…08), the 70-component port of the Android
`ui.components.*` kits (SW-ENG2-09), and the fluid motion system (named
springs, the GestureSpringSolver/LiquidMorphGeometry frozen-ABI ports,
interactive rubber-band sheets, SW-ENG2-10). **Zero Kotlin/Native
double-bridge** (the Apple-platform mandate, MP-DEC-02): the shell consumes
the *direct Swift UniFFI bindings* from Alpha's `StreamifyCore.xcframework`
and nothing else — until the xcframework lands, the deterministic
`PreviewEngineGateway` lane keeps every surface fully navigable.

```
iosApp/
├── Package.swift                          (iOS 17+ & macOS 14+, Swift 5.9, zero external deps)
├── Sources/StreamifyIOS/
│   ├── App/
│   │   ├── StreamifyApp.swift             public StreamifyMainScene (host target composes it)
│   │   ├── RootShellView.swift            SW-ENG2-02 adaptive shell: compact TabView +
│   │   │                                  docked MiniPlayerBar / NavigationSplitView
│   │   │                                  sidebar rail (iPad + Mac)
│   │   └── AppRoute.swift                 value-based type-safe push currency (Hashable)
│   ├── Theme/                             SW-ENG2-01 design system
│   │   ├── StreamifyColors.swift          dynamic asset-free light/dark tokens (exact
│   │   │                                  Android obsidian ladder) + gradient presets
│   │   ├── AppTheme.swift                 SF Pro typography, shapes, dimens, SF Symbols 5
│   │   │                                  catalog, Materials, glassmorphic modifiers
│   │   └── Materials.swift               VisualEffectBlur (NSVisualEffectView on macOS)
│   ├── Motion/                            SW-ENG2-10 fluid motion
│   │   ├── SpringInterpolators.swift      named springs (fluid = response 0.35 / 0.85)
│   │   ├── GestureSpringSolver.swift       1:1 port of GestureSpringSolver.kt
│   │   ├── LiquidMorphGeometry.swift       1:1 port of LiquidMorphGeometry.kt
│   │   ├── InteractiveSheet.swift          rubber-band spring modal (FullPlayer host)
│   │   ├── CardEffects.swift              press effects + HeartBurstView (120 Hz Canvas)
│   │   └── Haptics.swift                  the HapticWaveform catalogue → UIKit/AppKit
│   ├── Views/                             30 screens + 70 components
│   │   ├── Home/ Artist/ Album/ Search/ Library/     discovery (SW-ENG2-03/04/07)
│   │   ├── Player/ Queue/ Lyrics/                    audiophile suite (SW-ENG2-05/06):
│   │   │                                  FullPlayerView (ambient MeshGradient bg),
│   │   │                                  WaveformProgressSlider (μs-precision DAC-paced
│   │   │                                  scrub), native List .onMove queue, karaoke panes
│   │   ├── Settings/ Equalizer/ Downloads/ Updates/  system (SW-ENG2-08)
│   │   ├── Jam/ Blend/ Community/ Stats/ Profile/    social
│   │   ├── Admin/ Diagnostics/                        observability
│   │   ├── Onboarding/ Splash/                       brand choreography
│   │   ├── Support/                      UiModels (the UI currency) + PreviewCatalog
│   │   │                                 (deterministic fixtures behind every #Preview)
│   │   └── Components/
│   │       ├── YTStyle/ (36)             YtDaylistHeroBanner, YtQuickPicksCarousel,
│   │       │                             YtDailyMixRail, YtStudioArcDial, YtBottomNavBar …
│   │       └── Common/ (34)              MiniPlayerBar (swipe-to-skip + peek), TrackCoverArt,
│   │                                     DynamicMeshBackground (iOS 18 MeshGradient +
│   │                                     layered fallback), CanvasVideoSurface (AVPlayerLayer),
│   │                                     LyricsCanvas, QuantumSonicTokenOverlay, pull-to-refresh …
│   ├── Playback/
│   │   ├── PlaybackModels.swift           public Swift mirrors of the shared presentation
│   │   │                                  models (public: the library's screen currency)
│   │   └── PlaybackViewModel.swift        @MainActor state holder + AudioSessionResponding
│   │                                    + RemoteCommandRouting (lockscreen transport)
│   │                                    + 1 Hz-gated lockscreen publishing
│   ├── Playback/NowPlayingInfoPublisher.swift  OneHertzGate · info-dict derivation ·
│   │                                            ArtworkLoader (SW-ENG3-04 async artwork)
│   ├── Engine/
│   │   ├── EngineGateway.swift            the seam protocol (Swift mirror of EnginePort)
│   │   │                                  + the SW-ENG1-04 typed data lane
│   │   ├── EngineContract.swift           lane-neutral Engine* mirrors of the SW-ENG1-03
│   │   │                                  UniFFI records (Track/SearchResult/QueueItem/
│   │   │                                  EqualizerBand/JamRoomState)
│   │   ├── UniFFIEngineGateway.swift      #if canImport(uniffi_streamify) — the REAL lane
│   │   │                                  (single-stream push bridge + typed lane mapping)
│   │   ├── EngineGatewayLoader.swift      one-place lane selection
│   │   ├── PreviewEngineGateway.swift     always-available deterministic stand-in with
│   │   │                                  the full typed data lane over a fixture catalog
│   │   └── Generated/                     (gitignored) probe-gated uniffi_streamify.swift
│   │                                      emitted by scripts/build_ios_xcframework.sh
│   ├── Services/                          SW-ENG1-05..08 native data services
│   │   ├── Auth/AppleKeychainStore.swift  Security.framework token vault
│   │   │                                  (EncryptedSharedPreferences successor)
│   │   ├── Storage/BackgroundDownloadService.swift  background URLSession downloads
│   │   │                                  with resume-data persistence + recovery
│   │   ├── Discovery/DaylistScheduleEngine.swift  circadian daylist port + AntiDrift
│   │   │                                  scoring core (SW-ENG1-07)
│   │   └── Network/CanvasScraperService.swift    canvas loops + artist clips fleet
│   │                                      scraper with in-memory SWR caches (SW-ENG1-06)
│   ├── AudioSession/
│   │   └── IOSAudioSessionAdapter.swift   MP-UI02 deliverable (see below; iOS lane
│   │                                      real, macOS same-API no-op fallback)
│   └── Views/                             SW-ENG2 SwiftUI screens & design system
│       ├── MiniPlayerView.swift           mini player + volume control (SwiftUI)
│       └── Queue/                         the native queue surface
├── Frameworks/                            (gitignored) StreamifyCore.xcframework lands
│                                          here via --output iosApp/Frameworks to light
│                                          the real lane up
└── Tests/StreamifyIOSTests/
    ├── StreamifyIOSTests.swift            MP-UI02 policy matrix + model parity +
    │                                      1 Hz gate / lockscreen / queue suites
    ├── EngineTests/UniFFIContractTests.swift  SW-ENG1-03/04 contract tests: real-lane
    │                                      FFI round-trips (probe-gated) + always-on
    │                                      typed-lane/daylist/anti-drift/parser suites
    └── ViewTests/                         SW-ENG2 gates: motion-math invariants (solver /
                                            morph / rubber-band), design-token parity with
                                            Android, queue reorder section-local→array
                                            projections, preview-catalog determinism
```

## The 1 Hz lockscreen / now-playing discipline (MP-UI01)

The directive mandates: "Rate-limit lockscreen and now-playing updates to
1 Hz." Two surfaces implement it:

* **Lockscreen (`NowPlayingInfoPublisher`)** — every pushed engine state
  flows through a pure `OneHertzGate` (injectable clock: first value
  admits, the 1 s window defers, the boundary re-opens) onto
  `MPNowPlayingInfoCenter`. A trailing-edge flush guarantees the LAST state
  of a burst always lands — a leading-edge-only limiter would freeze the
  lockscreen on a stale elapsed time forever. `RemoteCommandCenterAdapter`
  (Platform/Remote, SW-ENG3-03) routes the lockscreen / Control Center /
  headset transport (play, pause, toggle, next, previous, scrub-seek,
  like/dislike) through the MP-DEC-07 hardware debounce (50-150 ms envelope)
  onto the SAME `PlayerCommand` plane as the in-app UI and the MP-UI02
  audio-session reactions.
* **In-app readouts** — `PlaybackUiState.positionForDisplayMs` quantizes the
  DAC-derived position to whole seconds: the elapsed-time text changes at
  most once per second OF CONTENT, never per push.

The views carry the directive's names: `NowPlayingView` (the Home tab's
now-playing surface, with the queue sheet entry point), `MiniPlayerView`
(the docked mini player) and `QueueView`.

## `IOSAudioSessionAdapter` (MP-UI02 — the directive contract)

| Directive requirement | Implementation |
|---|---|
| Category `.playback`, mode `.default`, options `[]` | `activate()` → `setCategory(.playback, mode: .default, options: [])` + `setActive(true)` |
| Interruption `.began` → pause engine | `decision(forInterruption: .began, …)` → `.pauseEngine` → `PlayerCommand.pause` |
| Interruption `.ended` + `.shouldResume` → resume | `decision(forInterruption: .ended, options: [.shouldResume])` → `.resumeEngine` → `PlayerCommand.play`; `.ended` WITHOUT the option stays paused |
| Route change `.oldDeviceUnavailable` → pause | headphones unplugged AND AirPlay sink drops land on this reason → `.pauseEngine` + clean session teardown (observer bookkeeping marked inactive so a stale route cannot leak into the next activation) |
| Clean teardowns | `deactivate()` removes every observer exactly once, releases the session; idempotent; `deinit` drops tokens synchronously |

The decision core is a pair of **pure functions over the real
`AVAudioSession` value types** — the full policy matrix is unit-tested with
no mocks and no device. Observer plumbing hops to the main actor before
touching adapter state (AVFoundation delivers these notifications on an
arbitrary queue).

## Engine lane (Alpha dependency)

The real lane (`UniFFIEngineGateway`) compiles **strictly** behind
`#if canImport(uniffi_streamify)`. Until Alpha's XCFramework packaging
automation (Phase 5, Alpha's row in the responsibility matrix) links
`StreamifyCore.xcframework` into the host app, the scaffold builds and runs
with `PreviewEngineGateway` — and the moment the framework lands, the real
lane activates with **zero edits** anywhere in this package (same
probe-gate philosophy as the desktop CI's Windows engine check).

Binding mode (MP-DEC-02): SwiftUI → `uniffi_streamify` Swift proxies → the
staticlib's `uniffi_*` C ABI. No `cinterop`, no Kotlin runtime, no Obj-C
wrapper.

## Host app target (5 minutes, one-time)

SPM cannot emit an App Store bundle, and an `@main` inside the library
would collide with the host's own `@main` at link time — so the package
exposes `StreamifyMainScene` (a `Scene`) and the Xcode app target is a
husk:

1. Xcode → File → New → Target → **iOS App**, name it `Streamify`
   (bundle id / signing as usual), SwiftUI interface, no tests.
2. Delete the template `ContentView.swift` and replace the template
   `*App.swift` body with:

   ```swift
   import SwiftUI
   import StreamifyIOS

   @main
   struct StreamifyApp: App {
       var body: some Scene {
           StreamifyMainScene()
       }
   }
   ```

3. File → Add Package Dependencies… → point at this repository's
   `iosApp` folder (or add the local package), add `StreamifyIOS` to the
   app target.
4. *(When Alpha's framework lands)* drag `StreamifyCore.xcframework` into
   the app target's Frameworks with *Embed & Sign* — the UniFFI lane
   lights up automatically.

## Building / testing

SW-ENG1-01: the package is UNIVERSAL (`.iOS(.v17)` + `.macOS(.v14)`, the
SW-DEC-04 shared shell). A plain macOS host build is the first acceptance
gate (zero errors, zero warnings):

```bash
swift build --package-path iosApp
```

The XCTest suite runs through `xcodebuild` with a simulator destination
(Xcode 15+; the AVAudioSession surface is `#if canImport(UIKit)`-gated,
matching its implementation):

```bash
cd iosApp
xcodebuild test -scheme StreamifyIOS \\
    -destination 'platform=iOS Simulator,name=iPhone 15'
```

`PreviewCatalog` fixtures drive every `#Preview` — deterministic, no
network, no engine required. The REAL engine lane links conditionally: run
`./scripts/build_ios_xcframework.sh --output iosApp/Frameworks` on a Mac
and the manifest's probe picks `Frameworks/StreamifyCore.xcframework` up
as an SPM binary target — `import uniffi_streamify` resolves straight
out of the framework (no Kotlin/Native, no Obj-C shim; MP-DEC-02) and
the UniFFI-gated code paths light up with zero source edits.
