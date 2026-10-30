import SwiftUI

// ─────────────────────────────────────────────────────────────────────────────
// SW-ENG2-05 — the full player's landscape / regular-width companion panes
// (the FullPlayerSheetPanes port: LandscapeQueuePane / LandscapeLyricsPane /
// LandscapeRelatedPane).
//
// On iPhone these surfaces arrive as swipeable sheet panes (the shell
// presents them from the player's bottom tabs); on iPad / Mac — every
// REGULAR width — the panes dock beside the player canvas in a split layout
// (the HIG two-pane player idiom). Each pane is a self-contained universal
// view (SW-DEC-04): the same code serves both placements.
// ─────────────────────────────────────────────────────────────────────────────

/// Which companion pane the player shows on regular widths / sheet swipes.
public enum PlayerPane: String, CaseIterable, Identifiable, Sendable {
    case queue
    case lyrics
    case related

    public var id: String { rawValue }

    public var label: String {
        switch self {
        case .queue: return "Queue"
        case .lyrics: return "Lyrics"
        case .related: return "Related"
        }
    }

    public var icon: String {
        switch self {
        case .queue: return StreamifyIcon.queue
        case .lyrics: return StreamifyIcon.lyrics
        case .related: return StreamifyIcon.related
        }
    }
}

/// The queue pane: a reorderable queue scoped to the pane's height.
public struct LandscapeQueuePane: View {
    @Binding public var state: QueueListState
    @Binding public var autoMix: Bool
    public var onReorder: ((IndexSet, Int) -> Void)? = nil
    public var onDelete: ((Int) -> Void)? = nil
    public var onTrackClick: ((Int) -> Void)? = nil

    public init(
        state: Binding<QueueListState>,
        autoMix: Binding<Bool>,
        onReorder: ((IndexSet, Int) -> Void)? = nil,
        onDelete: ((Int) -> Void)? = nil,
        onTrackClick: ((Int) -> Void)? = nil
    ) {
        _state = state
        _autoMix = autoMix
        self.onReorder = onReorder
        self.onDelete = onDelete
        self.onTrackClick = onTrackClick
    }

    public var body: some View {
        QueueView(
            state: $state,
            autoMix: $autoMix,
            onTrackClick: onTrackClick,
            onReorder: onReorder,
            onDelete: onDelete
        )
    }
}

/// The lyrics pane: the karaoke canvas driven by the DAC-paced position.
public struct LandscapeLyricsPane: View {
    public var lyrics: LyricsData
    public var positionMs: Double

    public init(lyrics: LyricsData, positionMs: Double) {
        self.lyrics = lyrics
        self.positionMs = positionMs
    }

    public var body: some View {
        LyricsCanvas(lyrics: lyrics, positionMs: positionMs)
            .padding(.horizontal, StreamifyDimens.spaceXXL)
    }
}

/// The related pane: the "Related / discover" surface (related artists,
/// recommended tracks, start-radio) — the RelatedDiscoverSheet content in
/// pane form, fed by the deterministic catalog.
public struct LandscapeRelatedPane: View {
    public var tracks: [VirtualShelfTrack]
    public var onStartRadio: (() -> Void)? = nil
    public var onOpenTrack: ((VirtualShelfTrack) -> Void)? = nil

    public init(
        tracks: [VirtualShelfTrack] = Array(PreviewCatalog.tracks[4..<10]),
        onStartRadio: (() -> Void)? = nil,
        onOpenTrack: ((VirtualShelfTrack) -> Void)? = nil
    ) {
        self.tracks = tracks
        self.onStartRadio = onStartRadio
        self.onOpenTrack = onOpenTrack
    }

    public var body: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: StreamifyDimens.spaceLG) {
                YtSectionHeader(title: "Similar to this", subtitle: nil, onMore: nil)
                ForEach(tracks) { track in
                    TrackListItem(
                        track: track,
                        onPlay: { onOpenTrack?(track) }
                    )
                }
                if let onStartRadio {
                    Button {
                        Haptics.play(.queueDrop)
                        onStartRadio()
                    } label: {
                        Label("Start radio", systemImage: StreamifyIcon.radio)
                            .font(StreamifyType.chipText)
                            .foregroundStyle(StreamifyColors.textOnActiveChip)
                            .padding(.horizontal, StreamifyDimens.spaceXXL)
                            .padding(.vertical, StreamifyDimens.spaceSM + 2)
                            .background(StreamifyColors.bgChipActive, in: StreamifyShapes.chip)
                    }
                    .buttonStyle(.pressableCard)
                }
            }
            .padding(.vertical, StreamifyDimens.spaceLG)
            .centeredInLargeScreen(maxWidth: 520)
        }
        .background(StreamifyColors.bgBase)
    }
}

#Preview("Panes — regular width (iPad / Mac)") {
    HStack(spacing: 0) {
        LandscapeQueuePane(
            state: .constant(PreviewCatalog.queueState),
            autoMix: .constant(true)
        )
        .frame(maxWidth: 360)
        Divider()
        LandscapeLyricsPane(lyrics: PreviewCatalog.lyrics, positionMs: 9_800)
            .frame(maxWidth: .infinity)
    }
    .background(StreamifyColors.bgBase)
    .preferredColorScheme(.dark)
}
