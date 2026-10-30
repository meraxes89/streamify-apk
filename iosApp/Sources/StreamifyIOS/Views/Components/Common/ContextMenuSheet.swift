import SwiftUI

// ─────────────────────────────────────────────────────────────────────────────
// SW-ENG2 (Common wave B) — the long-press track context menu, sheet content.
//
// Native port of ui/components/ContextMenuSheet.kt: Compose's ModalBottomSheet
// menu becomes a compact glass panel the host presents after a long-press
// (SW-DEC-06 — the HIG counterpart of the Android menu, trimmed to the eight
// actions the Swift shell routes). Track header art is the deterministic
// inline gradient plate; every action row reports back through
// onAction(ContextMenuAction). Fixtures: PreviewCatalog.tracks.
// ─────────────────────────────────────────────────────────────────────────────

/// The eight context actions the sheet routes (Android's menu distilled).
public enum ContextMenuAction: String, CaseIterable, Sendable {
    case playNext
    case addToQueue
    case addToPlaylist
    case download
    case share
    case goToAlbum
    case goToArtist
    case removeDownload

    public var label: String {
        switch self {
        case .playNext: return "Play Next"
        case .addToQueue: return "Add to Queue"
        case .addToPlaylist: return "Add to Playlist"
        case .download: return "Download"
        case .share: return "Share"
        case .goToAlbum: return "Go to Album"
        case .goToArtist: return "Go to Artist"
        case .removeDownload: return "Remove Download"
        }
    }

    public var icon: String {
        switch self {
        case .playNext: return "play.rectangle.on.rectangle"
        case .addToQueue: return StreamifyIcon.queue
        case .addToPlaylist: return StreamifyIcon.plusCircle
        case .download: return StreamifyIcon.download
        case .share: return StreamifyIcon.share
        case .goToAlbum: return "opticaldisc"
        case .goToArtist: return StreamifyIcon.avatar
        case .removeDownload: return StreamifyIcon.trash
        }
    }
}

/// The static value model of one open menu (Android's ContextMenuState +
/// TrackContextMenuController collapsed into a plain Sendable value — the
/// host owns a @State of this and re-renders; no controller needed).
public struct ContextMenuState: Equatable, Sendable {
    public let isOpen: Bool
    public let track: VirtualShelfTrack?

    public init(isOpen: Bool, track: VirtualShelfTrack?) {
        self.isOpen = isOpen
        self.track = track
    }

    public static let closed = ContextMenuState(isOpen: false, track: nil)

    public static func open(_ track: VirtualShelfTrack) -> ContextMenuState {
        ContextMenuState(isOpen: true, track: track)
    }
}

public struct ContextMenuSheet: View {
    public let track: VirtualShelfTrack
    public var onAction: (ContextMenuAction) -> Void
    public var onDismiss: (() -> Void)? = nil

    public init(
        track: VirtualShelfTrack,
        onAction: @escaping (ContextMenuAction) -> Void,
        onDismiss: (() -> Void)? = nil
    ) {
        self.track = track
        self.onAction = onAction
        self.onDismiss = onDismiss
    }

    public var body: some View {
        VStack(alignment: .leading, spacing: StreamifyDimens.spaceMD) {
            header
            LazyVGrid(
                columns: [
                    GridItem(.flexible(), spacing: StreamifyDimens.spaceSM),
                    GridItem(.flexible(), spacing: StreamifyDimens.spaceSM)
                ],
                spacing: StreamifyDimens.spaceSM
            ) {
                ForEach(ContextMenuAction.allCases, id: \.self) { action in
                    actionTile(action)
                }
            }
        }
        .padding(StreamifyDimens.spaceLG)
        .glassPanel(cornerRadius: StreamifyRadius.xxl)
        .frame(maxWidth: 420)
    }

    private var header: some View {
        HStack(spacing: StreamifyDimens.spaceMD) {
            MiniContextArt(seed: track.title)
            VStack(alignment: .leading, spacing: 2) {
                Text(track.title)
                    .font(StreamifyType.titleMedium)
                    .foregroundStyle(StreamifyColors.textMain)
                    .lineLimit(1)
                Text(track.artist)
                    .font(StreamifyType.bodySmall)
                    .foregroundStyle(StreamifyColors.textSecondary)
                    .lineLimit(1)
            }
            Spacer(minLength: 0)
            Button {
                onDismiss?()
            } label: {
                Image(systemName: StreamifyIcon.close)
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundStyle(StreamifyColors.textSecondary)
                    .frame(width: 30, height: 30)
            }
            .buttonStyle(.plain)
        }
    }

    private func actionTile(_ action: ContextMenuAction) -> some View {
        Button {
            Haptics.play(.scrubTick)
            onAction(action)
            onDismiss?()
        } label: {
            VStack(spacing: 6) {
                Image(systemName: action.icon)
                    .font(.system(size: 18))
                    .foregroundStyle(
                        action == .removeDownload
                            ? StreamifyColors.errorRed
                            : StreamifyColors.textMain
                    )
                Text(action.label)
                    .font(StreamifyType.caption)
                    .foregroundStyle(StreamifyColors.textSecondary)
                    .lineLimit(1)
                    .minimumScaleFactor(0.8)
            }
            .frame(maxWidth: .infinity)
            .padding(.vertical, StreamifyDimens.spaceMD)
            .background(StreamifyColors.bgCard, in: StreamifyShapes.card)
        }
        .buttonStyle(.pressableCard)
    }
}

/// Inline 44 pt art plate: deterministic preset gradient + note glyph
/// (self-contained on purpose — no cross-component imports).
private struct MiniContextArt: View {
    let seed: String

    var body: some View {
        ZStack {
            Rectangle()
                .fill(StreamifyGradients.linear(StreamifyGradients.preset(forSeed: seed)))
            Image(systemName: StreamifyIcon.musicNote)
                .font(.system(size: 16, weight: .semibold))
                .foregroundStyle(Color.white.opacity(0.9))
        }
        .frame(width: 44, height: 44)
        .clipShape(RoundedRectangle(cornerRadius: StreamifyRadius.md, style: .continuous))
    }
}

#Preview {
    ContextMenuSheet(
        track: PreviewCatalog.tracks[0],
        onAction: { _ in }
    )
    .padding(StreamifyDimens.spaceLG)
    .background(StreamifyColors.bgBase)
}
