import SwiftUI

// ─────────────────────────────────────────────────────────────────────────────
// Phase 5 (MP-UI01 / Beta): the brand tokens in Swift — the same OLED
// graphite + brand-red system as the shared CMP theme
// (shared/src/commonMain/.../theme/StreamifyTheme.kt), expressed natively
// so the SwiftUI surfaces match the desktop shell pixel-for-pixel in tone.
// ─────────────────────────────────────────────────────────────────────────────

enum StreamifyTheme {
    static let bgBase = Color(red: 3 / 255, green: 3 / 255, blue: 3 / 255)
    static let bgSurface = Color(red: 15 / 255, green: 15 / 255, blue: 15 / 255)
    static let bgSurfaceElevated = Color(red: 33 / 255, green: 33 / 255, blue: 33 / 255)
    static let bgCard = Color(red: 24 / 255, green: 24 / 255, blue: 24 / 255)

    static let primary = Color(red: 1, green: 0, blue: 0)
    static let accentBlue = Color(red: 62 / 255, green: 166 / 255, blue: 1)

    static let textMain = Color.white
    static let textSecondary = Color(red: 170 / 255, green: 170 / 255, blue: 170 / 255)
    static let textTertiary = Color(red: 113 / 255, green: 113 / 255, blue: 113 / 255)

    static let borderSubtle = Color(red: 40 / 255, green: 40 / 255, blue: 40 / 255)

    /// Root chrome: the shell's base background + preferred color scheme.
    static let shellColorScheme: ColorScheme = .dark
}

/// Playback status badge text (shared wording with the desktop shell).
extension PlaybackStatus {
    var displayName: String {
        switch self {
        case .idle: return "Engine idle"
        case .buffering: return "Buffering…"
        case .playing: return "Playing"
        case .paused: return "Paused"
        case .error: return "Engine error"
        }
    }
}
