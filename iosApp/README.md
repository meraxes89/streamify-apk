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
├── Package.swift                          (iOS 16+, Swift 5.9, zero external deps)
├── Sources/StreamifyIOS/
│   ├── App/
│   │   ├── StreamifyApp.swift             public StreamifyMainScene (host target composes it)
│   │   ├── RootShellView.swift            TabView shell + Home/Search/Library/Jam sections
│   │   └── AppTheme.swift                 brand tokens in Swift (shared-palette parity)
│   ├── Playback/
│   │   ├── PlaybackModels.swift           Swift mirrors of the shared presentation models
│   │   └── PlaybackViewModel.swift        @MainActor state holder + AudioSessionResponding
│   ├── Engine/
│   │   ├── EngineGateway.swift            the seam protocol (Swift mirror of EnginePort)
│   │   ├── UniFFIEngineGateway.swift      #if canImport(uniffi_streamify) — the REAL lane
│   │   ├── EngineGatewayLoader.swift      one-place lane selection
│   │   └── PreviewEngineGateway.swift     always-available deterministic stand-in
│   ├── AudioSession/
│   │   └── IOSAudioSessionAdapter.swift   MP-UI02 deliverable (see below)
│   └── Views/
│       └── NowPlayingBar.swift            mini player + volume control (SwiftUI)
└── Tests/StreamifyIOSTests/
    └── StreamifyIOSTests.swift            MP-UI02 policy matrix + model parity suites
```

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

The package is iOS-only (`.iOS(.v16)`), so tests run through `xcodebuild`
with a simulator destination (Xcode 15+):

```bash
cd iosApp
xcodebuild test -scheme StreamifyIOS \
    -destination 'platform=iOS Simulator,name=iPhone 15'
```

A macOS CI lane for this package is deliberately NOT added in this PR —
Apple-hosted runners are Alpha's XCFramework packaging scope (the master
plan's responsibility matrix), and the lane belongs with the framework it
verifies.
