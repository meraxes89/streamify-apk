import SwiftUI
import StreamifyIOS

// ─────────────────────────────────────────────────────────────────────────────
// WS-B B5 — the checked-in host app target.
//
// The package (iosApp/Package.swift) is a LIBRARY by construction (SPM
// cannot emit an App Store bundle), so this 3-line App struct is the whole
// app target: everything real — shell, state holder, engine seam, audio
// session, CoreAudio plane — lives in the package. The host exists so a
// fresh `git clone` builds an installable app via
//
//     xcodebuild -project iosApp/Host/StreamifyHost.xcodeproj \
//                -scheme StreamifyHost \
//                -destination 'generic/platform=iOS Simulator' build
//
// with no manual project husks (the pre-B5 README recipe is retired).
// Capabilities wired here: CarPlay scene manifest, Live Activities,
// `streamify://` deep links, background audio, and the widget extension
// (Host/StreamifyWidgets) sharing the App Group snapshot store.
// ─────────────────────────────────────────────────────────────────────────────

@main
struct StreamifyHostApp: App {
    var body: some Scene {
        StreamifyMainScene()
    }
}
