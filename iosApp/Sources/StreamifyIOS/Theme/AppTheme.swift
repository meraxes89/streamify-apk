import SwiftUI

// ─────────────────────────────────────────────────────────────────────────────
// SW-ENG2-01 — the app-level theme: SF Pro typography scale, shape tokens,
// layout dimensions, the SF Symbols 5 catalog and the glassmorphic surface
// styles (Apple Materials + macOS VisualEffectView). Semantic COLORS live
// in StreamifyColors.swift; MOTION lives in Motion/SpringInterpolators.
//
// Every constant is the 1:1 mirror of the Android design tokens
// (ui/theme/{Type,Shape,Dimens}.kt) so a SwiftUI surface and its Compose
// twin share one geometry, then adapts each value to Apple HIG idioms.
// ─────────────────────────────────────────────────────────────────────────────

// MARK: - Typography (SF Pro — the system font, HIG default)

public enum StreamifyType {
    public static let displayLarge = Font.system(size: 28, weight: .bold)
    public static let displayMedium = Font.system(size: 24, weight: .bold)

    public static let headlineLarge = Font.system(size: 22, weight: .bold)
    public static let headlineMedium = Font.system(size: 18, weight: .bold)
    public static let headlineSmall = Font.system(size: 16, weight: .bold)

    public static let titleLarge = Font.system(size: 16, weight: .semibold)
    public static let titleMedium = Font.system(size: 15, weight: .semibold)
    public static let titleSmall = Font.system(size: 14, weight: .bold)

    public static let songTitle = Font.system(size: 15, weight: .medium)
    public static let songArtist = Font.system(size: 13, weight: .regular)
    public static let chipText = Font.system(size: 13, weight: .medium)

    public static let playerTitle = Font.system(size: 22, weight: .bold)
    public static let playerArtist = Font.system(size: 15, weight: .regular)
    public static let seekbarTime = Font.system(size: 12, weight: .regular)

    public static let lyricsActive = Font.system(size: 24, weight: .bold)
    public static let lyricsInactive = Font.system(size: 22, weight: .semibold)

    public static let bodyLarge = Font.system(size: 15, weight: .regular)
    public static let bodyMedium = Font.system(size: 14, weight: .regular)
    public static let bodySmall = Font.system(size: 12, weight: .regular)
    public static let caption = Font.system(size: 11, weight: .regular)

    public static let cardTitle = Font.system(size: 14, weight: .medium)
    public static let cardSubtitle = Font.system(size: 12, weight: .regular)
}

// MARK: - Shape tokens

public enum StreamifyShapes {
    public static let card = RoundedRectangle(cornerRadius: 8, style: .continuous)
    public static let chip = RoundedRectangle(cornerRadius: 8, style: .continuous)
    public static let searchBar = RoundedRectangle(cornerRadius: 24, style: .continuous)
    public static let actionPill = Circle()
    public static let heroArt = RoundedRectangle(cornerRadius: 24, style: .continuous)
    public static let playlistHero = RoundedRectangle(cornerRadius: 16, style: .continuous)
    public static let thumbnailSmall = RoundedRectangle(cornerRadius: 4, style: .continuous)
    public static let thumbnailMedium = RoundedRectangle(cornerRadius: 8, style: .continuous)
    public static let thumbnailLarge = RoundedRectangle(cornerRadius: 8, style: .continuous)
    public static let bottomSheet = UnevenRoundedRectangle(
        topLeadingRadius: 12, topTrailingRadius: 12, style: .continuous
    )
}

/// Raw radius scalars (Android RadiusSM…Full).
public enum StreamifyRadius {
    public static let sm: CGFloat = 4
    public static let md: CGFloat = 8
    public static let lg: CGFloat = 12
    public static let xl: CGFloat = 16
    public static let xxl: CGFloat = 24
    public static let full: CGFloat = 50
}

// MARK: - Layout dimensions (Android StreamifyDimens, 1:1)

public enum StreamifyDimens {
    // Spacing scale
    public static let spaceXXS: CGFloat = 2
    public static let spaceXS: CGFloat = 4
    public static let spaceSM: CGFloat = 8
    public static let spaceMD: CGFloat = 12
    public static let spaceLG: CGFloat = 16
    public static let spaceXL: CGFloat = 20
    public static let spaceXXL: CGFloat = 24
    public static let spaceHuge: CGFloat = 32
    public static let spaceGiant: CGFloat = 48

    // Component metrics
    public static let bottomNavHeight: CGFloat = 56
    public static let miniPlayerHeight: CGFloat = 64
    public static let miniPlayerArt: CGFloat = 48
    public static let miniProgressBarHeight: CGFloat = 2
    public static let fullPlayerArtSize: CGFloat = 340
    public static let searchBarHeight: CGFloat = 48
    public static let quickPickRowHeight: CGFloat = 56
    public static let trackRowHeight: CGFloat = 56
    public static let trackRowArt: CGFloat = 48
    public static let filterChipHeight: CGFloat = 32
    public static let playerActionPill: CGFloat = 36
    public static let cardWidth: CGFloat = 150
    public static let cardArtSize: CGFloat = 150
    public static let playButtonSize: CGFloat = 64
    public static let skipButtonSize: CGFloat = 32
    public static let shuffleButtonSize: CGFloat = 24
    public static let seekBarHeight: CGFloat = 4
    public static let seekBarThumb: CGFloat = 12
    public static let sheetMaxHeight: CGFloat = 0.95

    // Adaptive shell
    public static let largeScreenMaxWidth: CGFloat = 1100
    public static let sidebarRailWidth: CGFloat = 260
}

// MARK: - SF Symbols 5 catalog (hierarchical & multicolor rendering)

/// One source of truth for every glyph the app renders — all names are
/// SF Symbols 5 (iOS 17 / macOS 14); glyphs whose palette variants matter
/// use `.hierarchical`/`.palette` rendering at their call sites.
public enum StreamifyIcon {
    // Transport
    public static let play = "play.fill"
    public static let pause = "pause.fill"
    public static let backward = "backward.end.fill"
    public static let forward = "forward.end.fill"
    public static let goBack15 = "gobackward.15"
    public static let goForward15 = "goforward.15"
    public static let shuffle = "shuffle"
    public static let repeatAll = "repeat"
    public static let repeatOne = "repeat.1"

    // Library & metadata
    public static let musicNote = "music.note"
    public static let musicNoteList = "music.note.list"
    public static let wave = "waveform"
    public static let eq = "graphic.eq"
    public static let heart = "heart"
    public static let heartFill = "heart.fill"
    public static let star = "star"
    public static let starFill = "star.fill"
    public static let plus = "plus"
    public static let plusCircle = "plus.circle.fill"
    public static let checkmark = "checkmark"
    public static let checkmarkCircle = "checkmark.circle.fill"
    public static let ellipsis = "ellipsis"
    public static let ellipsisCircle = "ellipsis.circle"
    public static let share = "square.and.arrow.up"
    public static let download = "arrow.down.circle"
    public static let downloadFill = "arrow.down.circle.fill"
    public static let cloudDownload = "icloud.and.arrow.down"
    public static let tag = "tag.fill"

    // Shell sections
    public static let home = "house.fill"
    public static let homeOutline = "house"
    public static let search = "magnifyingglass"
    public static let library = "music.note.list"
    public static let jam = "person.3.fill"
    public static let settings = "gearshape.fill"
    public static let queue = "list.bullet"
    public static let lyrics = "quote.opening"
    public static let related = "sparkles"
    public static let catalog = "square.grid.2x2.fill"

    // Audiophile / DAC
    public static let dac = "waveform.and.personalvideo"
    public static let airplay = "airplayaudio"
    public static let radio = "dot.radiowaves.left.and.right"
    public static let speaker = "speaker.wave.2.fill"
    public static let speakerMuted = "speaker.slash.fill"
    public static let meter = "dial.medium"
    public static let meterHigh = "dial.max"
    public static let cpu = "cpu"
    public static let network = "network"
    public static let clock = "clock"
    public static let timer = "timer"
    public static let sleep = "moon.zzz.fill"
    public static let fan = "fanblades.fill"

    // Social / Jam
    public static let qr = "qrcode"
    public static let avatar = "person.crop.circle.fill"
    public static let personPlus = "person.crop.circle.badge.plus"
    public static let crown = "crown.fill"
    public static let handshake = "hand.raised.fill"
    public static let broadcast = "dot.radiowaves.right"
    public static let comment = "text.bubble.fill"
    public static let friends = "person.2.fill"

    // Chrome
    public static let chevronDown = "chevron.down"
    public static let chevronRight = "chevron.right"
    public static let chevronLeft = "chevron.left"
    public static let close = "xmark"
    public static let filter = "line.3.horizontal.decrease.circle"
    public static let sliders = "slider.horizontal.3"
    public static let grid = "square.grid.2x2"
    public static let list = "list.bullet"
    public static let sort = "arrow.up.arrow.down"
    public static let refresh = "arrow.clockwise"
    public static let paperplane = "paperplane.fill"
    public static let pin = "pin.fill"
    public static let info = "info.circle"
    public static let warning = "exclamationmark.triangle.fill"
    public static let trash = "trash.fill"
    public static let folder = "folder.fill"
    public static let doc = "doc.text.fill"
    public static let terminal = "terminal.fill"
    public static let bug = "ant.fill"
    public static let logo = "waveform"
    public static let cast = "tv"
    public static let video = "play.rectangle.fill"
    public static let songVideo = "play.square.stack.fill"
}

// MARK: - Apple Materials

public enum StreamifyMaterials {
    /// Sheets & the mini-player glass (SW-ENG2-01: .ultraThinMaterial).
    public static let sheet: Material = .ultraThinMaterial
    /// Docked panels, inspectors, hover chips (.thinMaterial).
    public static let panel: Material = .thinMaterial
    /// Top chrome / sidebar backgrounds.
    public static let chrome: Material = .regularMaterial
}

// MARK: - Glassmorphic surface styles

private struct CardSurfaceModifier: ViewModifier {
    var hoverable = true

    @State private var hovered = false

    func body(content: Content) -> some View {
        content
            .background(
                hovered ? StreamifyColors.bgCardHover : StreamifyColors.bgCard,
                in: StreamifyShapes.card
            )
            .overlay(StreamifyShapes.card.strokeBorder(StreamifyColors.borderSubtle, lineWidth: 1))
            .onHover { hovered = hoverable && $0 }
    }
}

private struct GlassPanelModifier: ViewModifier {
    var material: Material
    var cornerRadius: CGFloat

    func body(content: Content) -> some View {
        content
            .background(material, in: RoundedRectangle(cornerRadius: cornerRadius, style: .continuous))
            .overlay(
                RoundedRectangle(cornerRadius: cornerRadius, style: .continuous)
                    .strokeBorder(StreamifyColors.glassHairline, lineWidth: 1)
            )
    }
}

private struct ChipSurfaceModifier: ViewModifier {
    var active: Bool

    func body(content: Content) -> some View {
        content
            .background(
                active ? StreamifyColors.bgChipActive : StreamifyColors.bgChipInactive,
                in: StreamifyShapes.chip
            )
            .overlay(
                StreamifyShapes.chip.strokeBorder(
                    active ? Color.clear : StreamifyColors.borderChip,
                    lineWidth: 1
                )
            )
    }
}

extension View {
    /// Obsidian card surface: elevation token + hairline + optional hover
    /// lift (the macOS pointer idiom; a no-op inert overlay on touch).
    public func cardSurface(hoverable: Bool = true) -> some View {
        modifier(CardSurfaceModifier(hoverable: hoverable))
    }

    /// Native Apple Material panel (SW-ENG2-01 glassmorphism).
    public func glassPanel(
        _ material: Material = StreamifyMaterials.panel,
        cornerRadius: CGFloat = StreamifyRadius.lg
    ) -> some View {
        modifier(GlassPanelModifier(material: material, cornerRadius: cornerRadius))
    }

    /// Filter / mood chip surface (Android BgChipActive ↔ Inactive).
    public func chipSurface(active: Bool) -> some View {
        modifier(ChipSurfaceModifier(active: active))
    }

    /// Centers scrollable content columns on iPad / Mac (Android
    /// `centerInLargeScreen(maxWidth = 1100.dp)` — the HIG reading-width rule).
    public func centeredInLargeScreen(
        maxWidth: CGFloat = StreamifyDimens.largeScreenMaxWidth
    ) -> some View {
        frame(maxWidth: maxWidth)
            .frame(maxWidth: .infinity)
    }
}

// MARK: - Model badge text (Phase-5 API carried over verbatim)

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
