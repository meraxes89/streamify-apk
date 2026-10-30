// swift-tools-version: 5.9
// ─────────────────────────────────────────────────────────────────────────────
// Phase 5 (MP-UI01 / Beta): the native iOS SwiftUI shell scaffold.
//
// A Swift package carrying the ENTIRE app surface: the SwiftUI shell
// (RootShellView + views), the PlaybackViewModel state holder, the
// EngineGateway seam and the IOSAudioSessionAdapter (MP-UI02). The package
// is a LIBRARY by construction — an Xcode app target wraps it (see
// README.md "Host app target" for the 5-minute wiring) because SPM cannot
// emit an App Store bundle on its own. Everything real lives here so the
// app target stays a thin shell.
//
// DEPENDENCIES: none beyond the SDK. The direct Swift UniFFI bindings
// (Alpha's StreamifyCore.xcframework + the generated `uniffi_streamify`
// module) are consumed STRICTLY behind `#if canImport(uniffi_streamify)`
// in Engine/UniFFIEngineGateway.swift — the scaffold compiles TODAY with
// the preview gateway and lights the real engine lane up automatically
// the moment the xcframework is linked, mirroring the desktop CI's
// Windows probe-gate philosophy. ZERO Kotlin/Native double-bridge (the
// Apple-platform mandate, MP-DEC-02).
// ─────────────────────────────────────────────────────────────────────────────
import PackageDescription

let package = Package(
    name: "StreamifyIOS",
    platforms: [
        // SW-DEC-02: the Observation stack (@Observable) requires iOS 17 /
        // macOS 14; the macOS platform declaration itself lands with
        // SW-ENG1-01 once the scaffold's iOS-only surfaces are guarded.
        .iOS(.v17)
    ],
    products: [
        .library(name: "StreamifyIOS", targets: ["StreamifyIOS"])
    ],
    targets: [
        .target(
            name: "StreamifyIOS",
            path: "Sources/StreamifyIOS"
        ),
        .testTarget(
            name: "StreamifyIOSTests",
            dependencies: ["StreamifyIOS"],
            path: "Tests/StreamifyIOSTests"
        ),
    ]
)
