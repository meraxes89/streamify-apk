import SwiftUI
import WidgetKit
import StreamifyIOS

// ─────────────────────────────────────────────────────────────────────────────
// WS-B B5 — the widget-extension entry point (the package's documented host
// wiring pattern, see Platform/Widgets/StreamifyWidgetBundle.swift).
//
// The widget CODE lives in the package (Home Screen small/medium + Lock
// Screen accessory families, iOS 17 AppIntent transport buttons with the
// `streamify://` deep-link fallback); this extension target only owns the
// @main. The cross-process snapshot store rides the shared App Group.
// ─────────────────────────────────────────────────────────────────────────────

@main
struct StreamifyWidgetsEntryPoint: WidgetBundle {
    var body: some Widget {
        StreamifyWidgetBundle().body
    }
}
