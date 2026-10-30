import SwiftUI

// ─────────────────────────────────────────────────────────────────────────────
// SW-ENG1-01 (multiplatform discipline, SW-DEC-04): the shell's canonical
// toolbar placements, resolved per platform.
//
// SwiftUI's ToolbarItemPlacement vocabulary diverges across Apple targets:
// `.topBarLeading` / `.topBarTrailing` (the iOS 14+ navigation-bar edge
// slots) do not exist on macOS, where the window toolbar's trailing action
// slot is `.primaryAction` and its leading slot is `.navigation`. Keeping
// the mapping HERE means every screen stays a single unconditional call
// site — the same philosophy as the audio adapter's same-API macOS no-op
// lane (AudioSession/IOSAudioSessionAdapter.swift).
//
// Engineer 2's screen ladder consumes these directly; new divergent slots
// (bottom bar, status bar, …) belong HERE, never inline at call sites —
// that discipline is what keeps `swift build --package-path iosApp` green
// on a macOS host (the SW-ENG1-01 acceptance gate the Apple CI lane
// enforces).
// ─────────────────────────────────────────────────────────────────────────────

extension ToolbarItemPlacement {
    /// The trailing chrome slot: the navigation bar's trailing edge on
    /// iOS, the window toolbar's primary-action (trailing) slot on macOS.
    static var shellTrailing: ToolbarItemPlacement {
        #if os(iOS)
        return .topBarTrailing
        #else
        return .primaryAction
        #endif
    }

    /// The leading chrome slot: the navigation bar's leading edge on iOS,
    /// the window toolbar's leading navigation slot on macOS.
    static var shellLeading: ToolbarItemPlacement {
        #if os(iOS)
        return .topBarLeading
        #else
        return .navigation
        #endif
    }
}
