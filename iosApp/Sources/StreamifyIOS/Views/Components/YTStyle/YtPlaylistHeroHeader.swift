import SwiftUI

// ─────────────────────────────────────────────────────────────────────────────
// YtPlaylistHeroHeader — the parallax-capable playlist hero (Android
// ui/components/yt/YtPlaylistHeroHeader.kt: 180 dp artwork scaled/translated
// from the LazyColumn scroll offset, bold title, subtitle, play + shuffle
// circles) and YtLikedMusicCard — the synthetic liked-music gradient card
// (zero image loading, pinned song count). The GPU graphicsLayer parallax is
// expressed with scaleEffect/offset, driven by the parent's scrollOffset.
// ─────────────────────────────────────────────────────────────────────────────

public struct YtPlaylistHeroHeader: View {
    public var title: String
    public var kindLabel: String
    public var curator: String
    public var trackCount: Int
    public var seed: String
    public var scrollOffset: Double = 0
    public var onPlay: () -> Void = {}
    public var onShuffle: () -> Void = {}

    public init(
        title: String,
        kindLabel: String,
        curator: String,
        trackCount: Int,
        seed: String,
        scrollOffset: Double = 0,
        onPlay: @escaping () -> Void = {},
        onShuffle: @escaping () -> Void = {}
    ) {
        self.title = title
        self.kindLabel = kindLabel
        self.curator = curator
        self.trackCount = trackCount
        self.seed = seed
        self.scrollOffset = scrollOffset
        self.onPlay = onPlay
        self.onShuffle = onShuffle
    }

    /// GPU-parallax transform (Android graphicsLayer math, 1:1).
    private var heroScale: Double {
        min(max(1 - min(max(scrollOffset, 0), 300) / 1000, 0.7), 1)
    }

    private var heroLift: Double {
        -min(max(scrollOffset, 0), 300) * 0.4
    }

    public var body: some View {
        VStack(spacing: StreamifyDimens.spaceLG) {
            ZStack {
                StreamifyGradients.linear(StreamifyGradients.preset(forSeed: seed))
                Image(systemName: StreamifyIcon.musicNoteList)
                    .font(.system(size: 64))
                    .foregroundStyle(Color.white.opacity(0.35))
            }
            .frame(width: 180, height: 180)
            .clipShape(StreamifyShapes.playlistHero)
            .scaleEffect(heroScale)
            .offset(y: heroLift)

            VStack(spacing: StreamifyDimens.spaceXS) {
                Text(kindLabel.uppercased())
                    .font(StreamifyType.caption)
                    .tracking(1)
                    .foregroundStyle(StreamifyColors.textSecondary)
                Text(title)
                    .font(StreamifyType.displayLarge)
                    .foregroundStyle(StreamifyColors.textMain)
                    .lineLimit(2)
                    .multilineTextAlignment(.center)
                Text("\(curator) • \(trackCount) songs")
                    .font(StreamifyType.bodySmall)
                    .foregroundStyle(StreamifyColors.textSecondary)
                    .lineLimit(1)
            }

            HStack(spacing: StreamifyDimens.spaceLG) {
                Button(action: onPlay) {
                    Image(systemName: StreamifyIcon.play)
                        .font(.system(size: 22, weight: .semibold))
                        .foregroundStyle(StreamifyColors.textOnActiveChip)
                        .frame(width: 48, height: 48)
                        .background(StreamifyColors.activeControl, in: StreamifyShapes.actionPill)
                }
                .buttonStyle(.pressableCard)
                .accessibilityLabel("Play \(title)")

                Button(action: onShuffle) {
                    Image(systemName: StreamifyIcon.shuffle)
                        .font(.system(size: 18))
                        .foregroundStyle(StreamifyColors.textMain)
                        .frame(width: 44, height: 44)
                        .background(StreamifyColors.bgSurfaceElevated, in: StreamifyShapes.actionPill)
                }
                .buttonStyle(.pressableCard)
                .accessibilityLabel("Shuffle \(title)")
            }
        }
        .padding(.horizontal, StreamifyDimens.spaceLG)
        .padding(.vertical, StreamifyDimens.spaceMD)
    }
}

// MARK: - Liked music gradient card (synthetic art, pinned count)

public struct YtLikedMusicCard: View {
    public var trackCount: Int
    public var onTap: () -> Void = {}

    public init(trackCount: Int, onTap: @escaping () -> Void = {}) {
        self.trackCount = trackCount
        self.onTap = onTap
    }

    public var body: some View {
        Button(action: onTap) {
            HStack(spacing: StreamifyDimens.spaceMD) {
                ZStack {
                    LinearGradient(
                        colors: StreamifyGradients.brand,
                        startPoint: .topLeading,
                        endPoint: .bottomTrailing
                    )
                    Image(systemName: StreamifyIcon.heartFill)
                        .font(.system(size: 22))
                        .foregroundStyle(Color.white)
                }
                .frame(width: 48, height: 48)
                .clipShape(StreamifyShapes.thumbnailSmall)

                VStack(alignment: .leading, spacing: 2) {
                    Text("Liked Music")
                        .font(StreamifyType.songTitle)
                        .foregroundStyle(StreamifyColors.textMain)
                    Text("Auto playlist • \(trackCount) songs")
                        .font(StreamifyType.songArtist)
                        .foregroundStyle(StreamifyColors.textSecondary)
                }
                Spacer(minLength: StreamifyDimens.spaceMD)

                HStack(spacing: 4) {
                    Image(systemName: StreamifyIcon.pin)
                        .font(.system(size: 10))
                    Text("\(trackCount)")
                        .font(.system(size: 12, weight: .semibold))
                        .monospacedDigit()
                }
                .foregroundStyle(StreamifyColors.textSecondary)
                .padding(.horizontal, 8)
                .padding(.vertical, 4)
                .background(StreamifyColors.bgSurfaceElevated, in: Capsule())
            }
            .padding(.horizontal, StreamifyDimens.spaceLG)
            .frame(height: StreamifyDimens.trackRowHeight)
            .contentShape(Rectangle())
        }
        .buttonStyle(.pressableCard)
        .accessibilityLabel("Liked Music, \(trackCount) songs")
    }
}

#Preview("Playlist hero + liked card") {
    VStack(spacing: 24) {
        YtPlaylistHeroHeader(
            title: PreviewCatalog.shelves[1].title,
            kindLabel: "Playlist",
            curator: PreviewCatalog.shelves[1].subtitle ?? "Streamify",
            trackCount: PreviewCatalog.shelves[1].tracks.count,
            seed: PreviewCatalog.shelves[1].id
        )
        YtLikedMusicCard(trackCount: PreviewCatalog.tracks.count)
    }
    .frame(maxWidth: 560)
    .background(StreamifyColors.bgBase)
}
