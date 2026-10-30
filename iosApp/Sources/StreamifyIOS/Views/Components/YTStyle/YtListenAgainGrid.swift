import SwiftUI

// ─────────────────────────────────────────────────────────────────────────────
// YtListenAgainGrid — the "Listen again" grid. Port of
// ui/components/yt/YtListenAgainGrid.kt: the LazyRow of pre-chunked pairs
// becomes a native adaptive LazyVGrid (2–3 columns) of medium cards — art,
// title, artist — with the deterministic preset gradient standing in for
// artwork and pressable-card taps.
// ─────────────────────────────────────────────────────────────────────────────

public struct YtListenAgainGrid: View {
    public var tracks: [VirtualShelfTrack]
    public var onPlay: (Int) -> Void

    public init(tracks: [VirtualShelfTrack], onPlay: @escaping (Int) -> Void) {
        self.tracks = tracks
        self.onPlay = onPlay
    }

    private let columns = [
        GridItem(.adaptive(minimum: 120), spacing: StreamifyDimens.spaceMD)
    ]

    public var body: some View {
        LazyVGrid(columns: columns, spacing: StreamifyDimens.spaceLG) {
            ForEach(Array(tracks.enumerated()), id: \.element.id) { index, track in
                YtMediumCard(track: track) {
                    onPlay(index)
                }
            }
        }
        .padding(.horizontal, StreamifyDimens.spaceLG)
    }
}

/// One medium card: square art + title + artist (Android YtMediumCard, 120 dp).
public struct YtMediumCard: View {
    public var track: VirtualShelfTrack
    public var onOpen: () -> Void

    public init(track: VirtualShelfTrack, onOpen: @escaping () -> Void) {
        self.track = track
        self.onOpen = onOpen
    }

    public var body: some View {
        Button(action: onOpen) {
            VStack(alignment: .leading, spacing: 6) {
                artwork
                Text(track.title)
                    .font(StreamifyType.cardTitle)
                    .foregroundStyle(StreamifyColors.textMain)
                    .lineLimit(1)
                Text(track.artist)
                    .font(StreamifyType.cardSubtitle)
                    .foregroundStyle(StreamifyColors.textSecondary)
                    .lineLimit(1)
            }
        }
        .buttonStyle(.pressableCard)
    }

    private var artwork: some View {
        ZStack {
            StreamifyShapes.thumbnailMedium.fill(
                StreamifyGradients.linear(StreamifyGradients.preset(forSeed: track.title))
            )
            Image(systemName: StreamifyIcon.musicNote)
                .font(.system(size: 26))
                .foregroundStyle(Color.white.opacity(0.85))
        }
        .aspectRatio(1, contentMode: .fit)
    }
}

#Preview("Listen again grid") {
    YtListenAgainGrid(tracks: PreviewCatalog.tracks, onPlay: { _ in })
        .padding(.vertical, StreamifyDimens.spaceLG)
        .background(StreamifyColors.bgBase)
}
