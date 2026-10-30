import SwiftUI

// ─────────────────────────────────────────────────────────────────────────────
// YtQuickPicksCarousel — YTM's signature quick picks. Port of
// ui/components/yt/YtQuickPicksCarousel.kt: the pre-chunked List<List<Track>>
// pages become columns of 3 compact rows in rounded panels, horizontally
// scrollable. Native touches: the context-menu "more" button is dropped (no
// callback in this contract) in favor of the track duration readout.
// ─────────────────────────────────────────────────────────────────────────────

public struct YtQuickPicksCarousel: View {
    public var tracks: [VirtualShelfTrack]
    public var onPlay: (Int) -> Void

    public init(tracks: [VirtualShelfTrack], onPlay: @escaping (Int) -> Void) {
        self.tracks = tracks
        self.onPlay = onPlay
    }

    /// Pre-chunked pages of 3 (Android: List<List<Track>>, 320 dp columns).
    private var columns: [[(offset: Int, element: VirtualShelfTrack)]] {
        let indexed = Array(tracks.enumerated())
        return stride(from: 0, to: indexed.count, by: 3).map { start in
            Array(indexed[start..<min(start + 3, indexed.count)])
        }
    }

    public var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(alignment: .top, spacing: StreamifyDimens.spaceMD) {
                ForEach(Array(columns.enumerated()), id: \.offset) { _, column in
                    VStack(spacing: StreamifyDimens.spaceSM) {
                        ForEach(column, id: \.offset) { item in
                            YtCompactTrackRow(track: item.element) {
                                onPlay(item.offset)
                            }
                        }
                    }
                    .frame(width: 320)
                    .padding(StreamifyDimens.spaceSM)
                    .cardSurface(hoverable: false)
                }
            }
            .padding(.horizontal, StreamifyDimens.spaceLG)
        }
    }
}

/// One 56 pt quick-pick row: 48 pt deterministic-gradient art, title/artist,
/// duration readout (Android: YtCompactTrackRow + MoreVert menu button).
private struct YtCompactTrackRow: View {
    let track: VirtualShelfTrack
    let onPlay: () -> Void

    var body: some View {
        Button(action: onPlay) {
            HStack(spacing: StreamifyDimens.spaceMD) {
                artwork
                VStack(alignment: .leading, spacing: StreamifyDimens.spaceXXS) {
                    Text(track.title)
                        .font(StreamifyType.cardTitle)
                        .foregroundStyle(StreamifyColors.textMain)
                        .lineLimit(1)
                    Text(track.artist)
                        .font(StreamifyType.cardSubtitle)
                        .foregroundStyle(StreamifyColors.textSecondary)
                        .lineLimit(1)
                }
                Spacer(minLength: StreamifyDimens.spaceSM)
                Text(PlaybackFormatting.formatPlaybackTime(track.durationSec * 1_000))
                    .font(StreamifyType.seekbarTime)
                    .foregroundStyle(StreamifyColors.textTertiary)
            }
            .frame(maxWidth: .infinity)
            .frame(height: StreamifyDimens.trackRowHeight)
        }
        .buttonStyle(.pressableCard)
    }

    private var artwork: some View {
        ZStack {
            StreamifyShapes.thumbnailSmall.fill(
                StreamifyGradients.linear(StreamifyGradients.preset(forSeed: track.title))
            )
            Image(systemName: StreamifyIcon.musicNote)
                .font(.system(size: 16))
                .foregroundStyle(Color.white.opacity(0.85))
        }
        .frame(width: StreamifyDimens.trackRowArt, height: StreamifyDimens.trackRowArt)
    }
}

#Preview("Quick picks") {
    YtQuickPicksCarousel(tracks: PreviewCatalog.tracks, onPlay: { _ in })
        .padding(.vertical, StreamifyDimens.spaceLG)
        .background(StreamifyColors.bgBase)
}
