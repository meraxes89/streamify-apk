// swift-tools-version: 5.9
// ─────────────────────────────────────────────────────────────────────────────
// The native Apple shell package (iOS / iPadOS / macOS, SW-ENG2-02).
//
// A Swift package carrying the ENTIRE app surface: the adaptive SwiftUI
// shell (RootShellView + 30 screens + 70 ported components), the motion
// system, the theme, the PlaybackViewModel state holder, the EngineGateway
// seam and the IOSAudioSessionAdapter (MP-UI02). The package is a LIBRARY
// by construction — an Xcode app target wraps it (see README.md "Host app
// target") because SPM cannot emit an App Store bundle on its own.
//
// PLATFORM FLOORS (SW-ENG1-01, applied here because SW-ENG2's SwiftUI
// surface is pinned to them): .iOS(.v17) / .macOS(.v14) — @Observable
// (Observation), NavigationSplitView sidebar rail, ContentUnavailableView,
// SF Symbols 5 rendering and the .scrollTargetBehavior paging carousel.
//
// DEPENDENCIES: none beyond the SDK. The direct Swift UniFFI bindings
// (Alpha's StreamifyCore.xcframework + the generated `uniffi_streamify`
// module) are consumed STRICTLY behind `#if canImport(uniffi_streamify)`
// in Engine/UniFFIEngineGateway.swift — the scaffold compiles TODAY with
// the preview gateway and lights the real engine lane up automatically
// the moment the xcframework is linked. ZERO Kotlin/Native double-bridge
// (the Apple-platform mandate, MP-DEC-02).
// ─────────────────────────────────────────────────────────────────────────────
import PackageDescription

let package = Package(
    name: "StreamifyIOS",
    platforms: [
        // SW-DEC-02: the Observation stack (@Observable) requires iOS 17 /
        // macOS 14; the macOS platform declaration lands here alongside the
        // SW-ENG2 multiplatform shell (the universal views target both).
        .iOS(.v17),
        .macOS(.v14)
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
