import SwiftUI
import WidgetKit

// ─────────────────────────────────────────────────────────────────────────────
// WS-B B5/B6 (ENG-2): the widget extension's entry point.
//
// The library cannot carry `@main` (it would collide with the host's own
// entry at link time), so THIS checked-in extension target owns it: the
// home-screen + lock-screen widgets come from the package's
// StreamifyWidgetBundle, and the Live Activity widget joins the same bundle
// on iOS (ActivityKit) — the AHEAD B6 host-wiring note, verbatim.
// ─────────────────────────────────────────────────────────────────────────────

@main
struct StreamifyWidgetsEntryPoint: WidgetBundle {
    var body: some Widget {
        StreamifyWidgetBundle().body

        #if canImport(ActivityKit) && canImport(UIKit)
        PlaybackLiveActivityWidget()
        #endif
    }
}
