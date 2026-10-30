// swift-tools-version: 5.9
// ─────────────────────────────────────────────────────────────────────────────
// SW-ENG1-01 (Swift migration, Engineer 1): the UNIVERSAL Apple package —
// one module, two platforms (iOS 17+ / macOS 14+), zero external
// dependencies.
//
// A Swift package carrying the ENTIRE app surface: the SwiftUI shell
// (RootShellView + views), the PlaybackViewModel state holder, the
// EngineGateway seam, the native data services (Keychain, background
// downloads, daylist scheduling, canvas scraping — SW-ENG1-05..08) and
// the audio-session adapter. The package is a LIBRARY by construction —
// an Xcode app target wraps it (see iosApp/README.md "Host app target")
// because SPM cannot emit an App Store bundle on its own. Everything
// real lives here so the app target stays a thin shell.
//
// PLATFORM RULES (SW-DEC-04)
//   * Both .iOS(.v17) and .macOS(.v14) consume the unified StreamifyIOS
//     module — code sharing ceiling > 90%, platform divergence strictly
//     isolated via `#if canImport(UIKit)` / `#if os(macOS)` guards
//     (AVAudioSession is the one iOS-only surface; MediaPlayer's
//     now-playing/remote-command APIs compile on both).
//   * macOS compile health is part of the acceptance gate:
//     `swift build --package-path iosApp` must be clean on a Mac host.
//
// THE REAL ENGINE LANE (SW-DEC-01 / MP-DEC-02 — no double bridge)
//   * The direct Swift UniFFI bindings are consumed STRICTLY behind
//     `#if canImport(uniffi_streamify)` (Engine/UniFFIEngineGateway.swift
//     and the probe-gated Generated/ bindings).
//   * This manifest links StreamifyCore.xcframework as a conditional
//     BINARY TARGET the moment it exists at iosApp/Frameworks/ — the
//     output of:
//         ./scripts/build_ios_xcframework.sh --output iosApp/Frameworks
//     Without the artifact the package compiles standalone on the
//     preview gateway (the scaffold lane CI runs today); with it, the
//     real engine lane lights up with ZERO edits anywhere else (the
//     same probe-gate philosophy as the desktop CI's Windows check).
//     ZERO Kotlin/Native double-bridge either way (MP-DEC-02).
// ─────────────────────────────────────────────────────────────────────────────
import Foundation
import PackageDescription

// Probe at manifest-evaluation time (CWD/manifest both live in iosApp/):
// link the xcframework only when the build script has emitted it.
let manifestDir = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
let frameworkURL = manifestDir.appendingPathComponent("Frameworks/StreamifyCore.xcframework")
let realLaneLinked = FileManager.default.fileExists(atPath: frameworkURL.path)

var targets: [Target] = [
    .target(
        name: "StreamifyIOS",
        dependencies: realLaneLinked ? [.target(name: "StreamifyCore")] : [],
        path: "Sources/StreamifyIOS"
    ),
    .testTarget(
        name: "StreamifyIOSTests",
        // The binary target is a direct dependency of the TEST target too,
        // so `#if canImport(uniffi_streamify)` resolves inside the tests
        // (SwiftPM only puts direct dependencies on a target's module
        // search path) and the real-lane UniFFI contract tests compile.
        dependencies: realLaneLinked
            ? [.target(name: "StreamifyIOS"), .target(name: "StreamifyCore")]
            : [.target(name: "StreamifyIOS")],
        path: "Tests/StreamifyIOSTests"
    ),
]
if realLaneLinked {
    targets.insert(
        .binaryTarget(name: "StreamifyCore", path: "Frameworks/StreamifyCore.xcframework"),
        at: 0
    )
}

let package = Package(
    name: "StreamifyIOS",
    platforms: [
        .iOS(.v17),
        .macOS(.v14)
    ],
    products: [
        .library(name: "StreamifyIOS", targets: ["StreamifyIOS"])
    ],
    targets: targets
)
