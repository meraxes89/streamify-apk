import Foundation

// ─────────────────────────────────────────────────────────────────────────────
// SW-ENG2-02 — the value-based, type-safe navigation currency.
//
// Every pushable destination the shell knows. NavigationStack is fed these
// VALUES (never string routes): type-checked by the compiler, restorable by
// state restoration, and deep-linkable by construction. The Android
// route-string graph ("artist/{artistName}", "album/{albumName}", "eq",
// "downloads"…) maps 1:1 onto these associated values.
// ─────────────────────────────────────────────────────────────────────────────

public enum AppRoute: Hashable, Sendable {
    // Player surfaces
    case nowPlaying
    case lyrics(trackTitle: String, trackArtist: String)

    // Discovery detail
    case artist(name: String)
    case album(title: String, artist: String)
    case mix(id: String)
    case mood(name: String)

    // Library & sound
    case queue
    case equalizer
    case dsp
    case downloads

    // System & social
    case updatesHub
    case wrapped
    case userProfile
    case profileSelection
    case community
    case blend
    case jam
    case adminDashboard
    case diagnostics
    case dogfood
    case onboarding
}
