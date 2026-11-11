# StreamifyHost — the checked-in app target (WS-B B5)

The Swift package at `iosApp/` is a **library** by construction (SPM cannot
emit an App Store bundle). This directory holds the thin, committed host
project that turns it into an installable app — no manual Xcode husk
creation, no ghost projects.

## Layout

```
iosApp/Host/
├── project.yml                    # XcodeGen manifest — the source of truth
├── StreamifyHost.xcodeproj/       # committed buildable project (Xcode 16+,
│   └── xcshareddata/xcschemes/    #   objectVersion 77 / synchronized folders)
│       └── StreamifyHost.xcscheme
├── StreamifyHost/                 # the app target (3-line @main App)
│   ├── StreamifyHostApp.swift
│   ├── Info.plist                 # CarPlay scene manifest, Live Activities,
│   │                              # streamify:// scheme, background audio
│   └── StreamifyHost.entitlements # App Group (widget snapshot store)
└── StreamifyWidgets/              # the widget extension target
    ├── StreamifyWidgetsEntryPoint.swift   # @main WidgetBundle (package code)
    ├── Info.plist                         # widgetkit-extension point
    └── StreamifyWidgets.entitlements      # same App Group
```

## Building (the B5 acceptance path)

A fresh clone builds out of the box — on the **preview engine lane** (the
deterministic `PreviewEngineGateway` / `PreviewFeatureLibrary` doubles):

```bash
xcodebuild -project iosApp/Host/StreamifyHost.xcodeproj \
           -scheme StreamifyHost \
           -destination 'generic/platform=iOS Simulator' \
           CODE_SIGNING_ALLOWED=NO build
```

CI runs exactly this in the Apple lane (`_ci-ios.yml`, "Host app build
gate") after staging the real xcframework, so the committed project is
proven buildable on every PR.

### The real engine lane

To play through the Rust core (the WS-B posture: CoreAudio pulls from the
engine's exported SPSC ring — never AVPlayer), stage the framework first:

```bash
./scripts/build_ios_xcframework.sh          # builds Rust + glue for all Apple targets
./scripts/build_ios_xcframework.sh --stage-only   # stages it into iosApp/Frameworks/
```

then open `iosApp/Host/StreamifyHost.xcodeproj` in Xcode (or re-run the
`xcodebuild` above without the signing overrides) — the package's
`Package.swift` probe-gate links `StreamifyCore.xcframework` automatically
and `AppServices` switches both the engine and feature lanes to UniFFI.

## Capabilities

| Capability | Where | Notes |
|---|---|---|
| CarPlay scene | `StreamifyHost/Info.plist` (`CPTemplateApplicationSceneSessionRoleApplication` → `StreamifyIOS.CarPlaySceneDelegate`) | The shell assigns the command router (`RootShellView.connectCarPlayRouter`). Presenting on real CarPlay hardware additionally requires the `com.apple.developer.carplay-audio` entitlement in your provisioning profile — add it to `StreamifyHost.entitlements` when your team has the CarPlay audio entitlement. |
| Live Activities | `Info.plist` `NSSupportsLiveActivities` | Driven by `PlaybackViewModel` through the package's `PlaybackLiveActivityController` (1 Hz admission). |
| `streamify://` deep links | `Info.plist` `CFBundleURLTypes` | The widget extension's iOS 16 fallback buttons. |
| Background audio | `Info.plist` `UIBackgroundModes: audio` | Required for the lock-screen transport. |
| App Group | `StreamifyHost.entitlements` + `StreamifyWidgets.entitlements` (`group.com.streamify.shared`) | The cross-process widget snapshot store. On-device builds need your development team selected (both targets) so Xcode can manage the group. |

## Regenerating the project

The committed `.xcodeproj` is generated from `project.yml` (XcodeGen,
objectVersion 77 / file-system-synchronized folders — Xcode 16+):

```bash
brew install xcodegen
cd iosApp/Host && xcodegen generate
```

Regenerate when adding/removing targets or capabilities; ordinary source
edits need nothing (the folders are synchronized).

## Why the committed project (and not Tuist)

The B5 acceptance criterion is literal: `git clone` → `xcodebuild build`
with **no manual steps**. A generation-first toolchain would make every
fresh clone depend on an `xcodegen`/`tuist` install before the first
build; the committed project builds directly while `project.yml` keeps
regeneration one command away. (AHEAD.md B5 named "minimal host target
(or Tuist manifest)" — this is the minimal-host reading of it.)
