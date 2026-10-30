import SwiftUI

// ─────────────────────────────────────────────────────────────────────────────
// SW-ENG2-C04.7 — TrackCard: the compact shelf track card.
//
// Port of ui/components/TrackCard.kt (150dp art square + title + artist on the
// cardPressEffect), extended with the duration readout and the like state the
// shelves render. Artwork goes through TrackCoverArt so the card shares the
// app's single artwork pipeline (SW-DEC-06).
// ─────────────────────────────────────────────────────────────────────────────

public struct TrackCard: View {

    public let track: VirtualShelfTrack
    public let onOpen: () -> Void
    public var onToggleLike: ((Bool) -> Void)?

    public init(
        track: VirtualShelfTrack,
        onOpen: @escaping () -> Void,
        onToggleLike: ((Bool) -> Void)? = nil
    ) {
        self.track = track
        self.onOpen = onOpen
        self.onToggleLike = onToggleLike
    }

    public var body: some View {
        Button(action: onOpen) {
            VStack(alignment: .leading, spacing: StreamifyDimens.spaceSM) {
                TrackCoverArt(
                    title: track.title,
                    artist: track.artist,
                    artworkUrl: track.artworkUrl,
                    size: StreamifyDimens.cardArtSize
                )
                .overlay(alignment: .topTrailing) { heartBadge }

                Text(track.title)
                    .font(StreamifyType.cardTitle)
                    .foregroundStyle(StreamifyColors.textMain)
                    .lineLimit(1)

                HStack(spacing: StreamifyDimens.spaceXS) {
                    Text(track.artist)
                        .font(StreamifyType.cardSubtitle)
                        .foregroundStyle(StreamifyColors.textSecondary)
                        .lineLimit(1)

                    Spacer(minLength: StreamifyDimens.spaceXS)

                    Text(PlaybackFormatting.formatPlaybackTime(track.durationSec * 1_000))
                        .font(StreamifyType.cardSubtitle)
                        .foregroundStyle(StreamifyColors.textTertiary)
                }
            }
            .frame(width: StreamifyDimens.cardWidth, alignment: .leading)
        }
        .buttonStyle(.pressableCard)
    }

    /// The like state chip pinned to the artwork corner; a Button when a
    /// toggle callback exists, a static medallion otherwise.
    @ViewBuilder
    private var heartBadge: some View {
        let heart = HStack(spacing: 3) {
            Image(systemName: track.isLiked ? StreamifyIcon.heartFill : StreamifyIcon.heart)
                .font(.system(size: 11, weight: .bold))
                .foregroundStyle(track.isLiked ? StreamifyColors.primary : .white)
                .contentTransition(.symbolEffect(.replace))
                .animation(Motion.snappy, value: track.isLiked)
        }
        .padding(6)
        .background(Circle().fill(.black.opacity(0.45)))
        .padding(StreamifyDimens.spaceSM)

        if let onToggleLike {
            Button {
                onToggleLike(!track.isLiked)
            } label: {
                heart
            }
            .buttonStyle(.plain)
            .accessibilityLabel(track.isLiked ? "Unlike \(track.title)" : "Like \(track.title)")
        } else {
            heart
                .accessibilityHidden(true)
        }
    }
}

#Preview("Track card") {
    HStack(spacing: 18) {
        TrackCard(track: PreviewCatalog.tracks[0], onOpen: {})
        TrackCard(
            track: PreviewCatalog.tracks[1],
            onOpen: {},
            onToggleLike: { _ in }
        )
    }
    .padding(24)
    .frame(maxWidth: .infinity, maxHeight: .infinity)
    .background(StreamifyColors.bgBase)
}
