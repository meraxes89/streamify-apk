# Streamify iOS (Phase 5 — MP-UI01 / MP-UI02)

**Native iOS SwiftUI shell scaffold + `IOSAudioSessionAdapter`**
Governing plan: `docs/architecture/MULTIPLATFORM_PLAN_27_10.MD` §4 Phase 5 ·
Ledger: `docs/architecture/MULTIPLATFORM_CHECKBOX.MD` (MP-UI01, MP-UI02)

A Swift package carrying the ENTIRE iOS app surface — the SwiftUI shell
(TabView: Home / Search / Library / Jam Sessions + docked now-playing bar),
the `PlaybackViewModel` state holder (the Swift counterpart of the shared
`PlaybackController`), the `EngineGateway` seam, and the
`IOSAudioSessionAdapter`. **Zero Kotlin/Native double-bridge** (the
Apple-platform mandate, MP-DEC-02): the shell consumes the *direct Swift
UniFFI bindings* from Alpha's `StreamifyCore.xcframework` and nothing else.

```
iosApp/
├── Package.swift                          (iOS 17+ & macOS 14+, Swift 5.9, zero external deps)
├── Sources/StreamifyIOS/
│   ├── App/
│   │   ├── StreamifyApp.swift             public StreamifyMainScene (host target composes it)
│   │   ├── RootShellView.swift            TabView shell + NowPlaying/Search/Library/Jam sections
│   │   └── AppTheme.swift                 brand tokens in Swift (shared-palette parity)
│   ├── Playback/
│   │   ├── PlaybackModels.swift           Swift mirrors of the shared presentation models
│   │   └── PlaybackViewModel.swift        @MainActor state holder + AudioSessionResponding
│   │                                    + RemoteCommandRouting (lockscreen transport)
│   │                                    + 1 Hz-gated lockscreen publishing
│   ├── Playback/NowPlayingInfoPublisher.swift  OneHertzGate · info-dict derivation ·
│   │                                            RemoteCommandAdapter (MP-UI01)
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
│   └── Views/
│       ├── MiniPlayerView.swift         mini player + volume control (SwiftUI)
│       └── QueueView.swift              the native queue surface (Swift mirror of
│                                         the shared QueueList — now-playing / up-next
│                                         projection, honest empty state)
├── Frameworks/                            (gitignored) StreamifyCore.xcframework lands
│                                          here via --output iosApp/Frameworks to light
│                                          the real lane up
└── Tests/StreamifyIOSTests/
    ├── StreamifyIOSTests.swift            MP-UI02 policy matrix + model parity +
    │                                      1 Hz gate / lockscreen / queue suites
    └── EngineTests/UniFFIContractTests.swift  SW-ENG1-03/04 contract tests: real-lane
                                           FFI round-trips (probe-gated) + always-on
                                           typed-lane/daylist/anti-drift/parser suites
```

## The 1 Hz lockscreen / now-playing discipline (MP-UI01)

The directive mandates: "Rate-limit lockscreen and now-playing updates to
1 Hz." Two surfaces implement it:

* **Lockscreen (`NowPlayingInfoPublisher`)** — every pushed engine state
  flows through a pure `OneHertzGate` (injectable clock: first value
  admits, the 1 s window defers, the boundary re-opens) onto
  `MPNowPlayingInfoCenter`. A trailing-edge flush guarantees the LAST state
  of a burst always lands — a leading-edge-only limiter would freeze the
  lockscreen on a stale elapsed time forever. `RemoteCommandAdapter` routes
  the lockscreen / Control Center transport (play, pause, toggle, next,
  previous, scrub-seek) onto the SAME `PlayerCommand` plane as the in-app UI
  and the MP-UI02 audio-session reactions.
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
xcodebuild test -scheme StreamifyIOS \
    -destination 'platform=iOS Simulator,name=iPhone 15'
```

The REAL engine lane links conditionally: run
`./scripts/build_ios_xcframework.sh --output iosApp/Frameworks` on a Mac
and the manifest's probe picks `Frameworks/StreamifyCore.xcframework` up
as an SPM binary target — `import uniffi_streamify` resolves straight
out of the framework (no Kotlin/Native, no Obj-C shim; MP-DEC-02) and
the UniFFI-gated code paths light up with zero source edits.
