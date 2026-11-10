import SwiftUI

// ─────────────────────────────────────────────────────────────────────────────
// WS-B B5 (ENG-2): the host app husk — three lines and an entry point.
//
// SPM cannot emit an App Store bundle, so the package stays a LIBRARY and
// THIS checked-in target owns the actual @main (iosApp/README.md "Host app
// target"): a fresh `git clone` builds an installable app via
//
//     xcodebuild -project iosApp/Streamify.xcodeproj -scheme Streamify \
//         -destination 'generic/platform=iOS Simulator' build
//
// with zero manual project creation. Everything real — shell, state holder,
// engine seam, audio session, render core, the ring mount — lives in the
// package; the husk composes the library's StreamifyMainScene (which also
// carries the macOS MenuBarExtra when a Mac host consumes it).
// ─────────────────────────────────────────────────────────────────────────────

@main
struct StreamifyApp: App {
    var body: some Scene {
        StreamifyMainScene()
    }
}
