import SwiftUI

#if canImport(UIKit)
import UIKit
#elseif canImport(AppKit)
import AppKit
#endif

// ─────────────────────────────────────────────────────────────────────────────
// SW-ENG2-01 — Streamify's dynamic color token system.
//
// Dark values are the EXACT OLED obsidian ladder from the Android theme
// (ui/theme/Color.kt — shared-palette parity with the CMP desktop shell);
// light values are the HIG-lifted equivalents rendered automatically in
// Light Mode. Every token resolves at draw time through the platform's
// trait collector (UITraitCollection / NSAppearance) with ZERO asset
// catalogs — automatic Dark/Light, one source of truth (SW-ENG2-01).
// ─────────────────────────────────────────────────────────────────────────────

// MARK: - Hex → platform color plumbing (private, asset-free)

#if canImport(UIKit)
private func platformColor(hex: UInt32) -> UIColor {
    UIColor(
        red: CGFloat((hex >> 16) & 0xFF) / 255.0,
        green: CGFloat((hex >> 8) & 0xFF) / 255.0,
        blue: CGFloat(hex & 0xFF) / 255.0,
        alpha: 1
    )
}
#elseif canImport(AppKit)
private func platformColor(hex: UInt32) -> NSColor {
    NSColor(
        red: CGFloat((hex >> 16) & 0xFF) / 255.0,
        green: CGFloat((hex >> 8) & 0xFF) / 255.0,
        blue: CGFloat(hex & 0xFF) / 255.0,
        alpha: 1
    )
}
#endif

extension Color {

    /// The asset-free dynamic token: one 0xRRGGBB pair resolved per
    /// appearance by the platform trait collector.
    init(streamifyLight light: UInt32, dark: UInt32) {
        #if canImport(UIKit)
        self.init(uiColor: UIColor { traits in
            traits.userInterfaceStyle == .dark
                ? platformColor(hex: dark)
                : platformColor(hex: light)
        })
        #elseif canImport(AppKit)
        self.init(nsColor: NSColor(name: nil) { appearance in
            let wantsDark = appearance.bestMatch(from: [.aqua, .darkAqua]) == .darkAqua
            return wantsDark ? platformColor(hex: dark) : platformColor(hex: light)
        })
        #else
        self.init(
            red: Double((light >> 16) & 0xFF) / 255.0,
            green: Double((light >> 8) & 0xFF) / 255.0,
            blue: Double(light & 0xFF) / 255.0
        )
        #endif
    }

    /// Static 0xRRGGBB token (appearance-agnostic, e.g. brand red).
    init(streamifyHex hex: UInt32) {
        self.init(
            red: Double((hex >> 16) & 0xFF) / 255.0,
            green: Double((hex >> 8) & 0xFF) / 255.0,
            blue: Double(hex & 0xFF) / 255.0
        )
    }
}

// MARK: - Semantic tokens

public enum StreamifyColors {

    // ─ Surface ladder (Android BgSurface0dp…4dp, exact dark hexes) ──
    public static let bgBase = Color(streamifyLight: 0xF6F6F8, dark: 0x030303)
    public static let bgSurface = Color(streamifyLight: 0xFFFFFF, dark: 0x0F0F0F)
    public static let bgCard = Color(streamifyLight: 0xFFFFFF, dark: 0x181818)
    public static let bgCardHover = Color(streamifyLight: 0xEDEDF0, dark: 0x282828)
    public static let bgSurfaceElevated = Color(streamifyLight: 0xF2F2F7, dark: 0x212121)
    public static let bgMiniPlayer = Color(streamifyLight: 0xF7F7FA, dark: 0x212121)
    public static let bgSearchBar = Color(streamifyLight: 0xE9E9EE, dark: 0x212121)
    public static let bgChipInactive = Color(streamifyLight: 0xE8E8ED, dark: 0x212121)
    public static let bgChipActive = Color(streamifyLight: 0x030303, dark: 0xFFFFFF)
    public static let textOnActiveChip = Color(streamifyLight: 0xFFFFFF, dark: 0x030303)
    public static let bgPlayer = Color(streamifyLight: 0xF9F9FB, dark: 0x0A0A0A)

    // ─ Brand ──
    /// OBSIDIAN RED — Streamify's brand primary, constant in both modes.
    public static let primary = Color(streamifyHex: 0xFF0000)
    public static let primaryAccent = Color(streamifyHex: 0xFF0033)
    public static let primaryHover = Color(streamifyLight: 0xE60000, dark: 0xFF3333)
    public static let primaryDark = Color(streamifyHex: 0xCC0000)
    public static let activeControl = Color(streamifyLight: 0x030303, dark: 0xFFFFFF)
    public static let accentBlue = Color(streamifyLight: 0x0077E6, dark: 0x3EA6FF)

    // ─ Text ──
    public static let textMain = Color(streamifyLight: 0x0A0A0C, dark: 0xFFFFFF)
    public static let textSecondary = Color(streamifyLight: 0x55555E, dark: 0xAAAAAA)
    public static let textTertiary = Color(streamifyLight: 0x8A8A93, dark: 0x717171)

    // ─ Strokes & separators ──
    public static let borderSubtle = Color(streamifyLight: 0xE3E3E8, dark: 0x282828)
    public static let borderChip = Color(streamifyLight: 0xD5D5DB, dark: 0x383838)
    public static let divider = Color(streamifyLight: 0xE8E8EC, dark: 0x212121)

    // ─ Semantic ──
    public static let errorRed = Color(streamifyHex: 0xFF4D4D)
    public static let errorBg = Color(streamifyLight: 0x14EB5757, dark: 0x26EB5757)
    public static let explicit = Color(streamifyLight: 0x6E6E78, dark: 0x888888)
    public static let successGreen = Color(streamifyLight: 0x1F9D55, dark: 0x35C46B)
    public static let warningAmber = Color(streamifyLight: 0xB06200, dark: 0xFFB300)

    /// Modal scrim over content (80 % black in Dark, 70 % white in Light —
    /// Android `Scrim 0xCC000000` and its Light counterpart).
    public static let scrim: Color = {
        #if canImport(UIKit)
        Color(uiColor: UIColor { traits in
            traits.userInterfaceStyle == .dark
                ? UIColor(white: 0, alpha: 0.8)
                : UIColor(white: 1, alpha: 0.7)
        })
        #elseif canImport(AppKit)
        Color(nsColor: NSColor(name: nil) { appearance in
            appearance.bestMatch(from: [.aqua, .darkAqua]) == .darkAqua
                ? NSColor(white: 0, alpha: 0.8)
                : NSColor(white: 1, alpha: 0.7)
        })
        #else
        Color.black.opacity(0.8)
        #endif
    }()

    /// Softening veil over ambient artwork in the player (Android
    /// `PlayerGradient 0xE60A0A0A` — 90 % obsidian over the palette mesh).
    public static let playerScrim = Color.black.opacity(0.55)

    // ─ Elevation (glassmorphism shadow tones) ──
    public static let shadowSoft = Color.black.opacity(0.28)
    public static let shadowLift = Color.black.opacity(0.42)

    /// The full-screen player's dynamic blending base (Android
    /// `PlayerDynamicBase 0xFF212121`): what the ambient palette mesh washes over.
    public static let playerDynamicBase = Color(streamifyLight: 0xEDEDF2, dark: 0x212121)

    /// Glass hairline for ultraThin/thinMaterial panels — brighter edge in
    /// Dark, ink hairline in Light.
    public static let glassHairline = Color(streamifyLight: 0x3C3C3C, dark: 0x4A4A4A).opacity(0.35)
}

// MARK: - Mood / genre gradient presets (Android gradient set, 1:1)

public enum StreamifyGradients {
    public static let pop = [Color(streamifyHex: 0xE13300), Color(streamifyHex: 0xFF8A65)]
    public static let hipHop = [Color(streamifyHex: 0xBA5D07), Color(streamifyHex: 0xFFB74D)]
    public static let rock = [Color(streamifyHex: 0xE91E63), Color(streamifyHex: 0xF48FB1)]
    public static let indie = [Color(streamifyHex: 0x608108), Color(streamifyHex: 0xAED581)]
    public static let workout = [Color(streamifyHex: 0x777777), Color(streamifyHex: 0xE0E0E0)]
    public static let focus = [Color(streamifyHex: 0x509BF5), Color(streamifyHex: 0x90CAF9)]
    public static let chill = [Color(streamifyHex: 0x8D67AB), Color(streamifyHex: 0xCE93D8)]
    public static let sleep = [Color(streamifyHex: 0x1E3264), Color(streamifyHex: 0x5C6BC0)]
    /// Shared CMP brand pair (`GradientPop 8D67AB → 4B3F8E`).
    public static let brand = [Color(streamifyHex: 0x8D67AB), Color(streamifyHex: 0x4B3F8E)]

    public static let all: [[Color]] = [pop, hipHop, rock, indie, workout, focus, chill, sleep, brand]

    /// Deterministic djb2 hash over the preset table — the same seed always
    /// paints the same gradient, in previews and tests alike.
    public static func preset(forSeed seed: String) -> [Color] {
        var hash: UInt64 = 5381
        for scalar in seed.unicodeScalars {
            hash = (hash &<< 5) &+ hash &+ UInt64(scalar.value)
        }
        return all[Int(hash % UInt64(all.count))]
    }

    /// Diagonal brand-safe linear gradient for hero surfaces.
    public static func linear(_ colors: [Color]) -> LinearGradient {
        LinearGradient(
            colors: colors,
            startPoint: .topLeading,
            endPoint: .bottomTrailing
        )
    }
}

// MARK: - Phase-5 token facade (API-stable with the scaffold's views)

/// The names the Phase-5 scaffold referenced (App/AppTheme.swift) — kept
/// verbatim so existing call sites keep compiling; each now resolves
/// through the dynamic token system above.
public enum StreamifyTheme {
    public static let bgBase = StreamifyColors.bgBase
    public static let bgSurface = StreamifyColors.bgSurface
    public static let bgSurfaceElevated = StreamifyColors.bgSurfaceElevated
    public static let bgCard = StreamifyColors.bgCard
    public static let primary = StreamifyColors.primary
    public static let accentBlue = StreamifyColors.accentBlue
    public static let textMain = StreamifyColors.textMain
    public static let textSecondary = StreamifyColors.textSecondary
    public static let textTertiary = StreamifyColors.textTertiary
    public static let borderSubtle = StreamifyColors.borderSubtle

    /// nil = honor the SYSTEM appearance (SW-ENG2-01 automatic Dark/Light).
    /// `preferredColorScheme(_:)` takes an optional, so the Phase-5 call
    /// sites compile unchanged while gaining Light Mode support.
    public static let shellColorScheme: ColorScheme? = nil
}
