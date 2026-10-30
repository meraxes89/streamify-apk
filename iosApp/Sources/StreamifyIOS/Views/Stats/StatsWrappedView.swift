import SwiftUI

// ─────────────────────────────────────────────────────────────────────────────
// SW-ENG2 (Stats wave) — StatsWrappedView: the year-in-sound telemetry surface.
//
// Native port of ui/screens/StatsWrappedScreen.kt: the YtWrappedHeroCard hero
// at the top, the "Your year in sound" 2-column stat-tile grid (icon in a
// seeded gradient circle + label + value), the YtGenreDistributionBar genre
// breakdown, the top-5 tracks list with gold/silver/bronze rank chips, and the
// primary capsule Share CTA. Pure props — the shell lowers
// YtStatsTelemetryEngine truth onto them. Fixtures: PreviewCatalog.
// ─────────────────────────────────────────────────────────────────────────────

public struct StatsWrappedView: View {

    public var wrappedStats: [WrappedStat]
    public var genreStats: [GenreStat]
    public var topTracks: [VirtualShelfTrack]
    public var onShare: () -> Void
    public var onPlayTrack: (VirtualShelfTrack) -> Void

    public init(
        wrappedStats: [WrappedStat] = PreviewCatalog.wrappedStats,
        genreStats: [GenreStat] = PreviewCatalog.genreStats,
        topTracks: [VirtualShelfTrack] = Array(PreviewCatalog.tracks[0..<5]),
        onShare: @escaping () -> Void = {},
        onPlayTrack: @escaping (VirtualShelfTrack) -> Void = { _ in }
    ) {
        self.wrappedStats = wrappedStats
        self.genreStats = genreStats
        self.topTracks = topTracks
        self.onShare = onShare
        self.onPlayTrack = onPlayTrack
    }

    public var body: some View {
        ScrollView {
            LazyVStack(spacing: StreamifyDimens.spaceLG) {
                YtWrappedHeroCard(
                    headline: wrappedStats.first
                        ?? WrappedStat(id: "w0", label: "Minutes listened", value: "0", symbol: StreamifyIcon.clock),
                    stats: Array(wrappedStats.dropFirst())
                )
                sectionHeader("YOUR YEAR IN SOUND")
                LazyVGrid(
                    columns: [
                        GridItem(.flexible(), spacing: StreamifyDimens.spaceSM),
                        GridItem(.flexible(), spacing: StreamifyDimens.spaceSM),
                    ],
                    spacing: StreamifyDimens.spaceSM
                ) {
                    ForEach(wrappedStats) { stat in
                        StatTile(stat: stat)
                    }
                }
                .padding(.horizontal, StreamifyDimens.spaceLG)
                sectionHeader("TOP GENRES")
                YtGenreDistributionBar(genres: genreStats)
                    .padding(StreamifyDimens.spaceMD)
                    .cardSurface(hoverable: false)
                    .padding(.horizontal, StreamifyDimens.spaceLG)
                sectionHeader("YOUR TOP 5 SONGS")
                ForEach(Array(topTracks.enumerated()), id: \.element.id) { index, track in
                    TopTrackRow(rank: index + 1, track: track) {
                        Haptics.play(.playPauseClick)
                        onPlayTrack(track)
                    }
                    .padding(.horizontal, StreamifyDimens.spaceLG)
                }
                shareButton
            }
            .padding(.vertical, StreamifyDimens.spaceLG)
        }
        .background(StreamifyColors.bgBase)
    }

    private func sectionHeader(_ title: String) -> some View {
        Text(title)
            .font(StreamifyType.caption.weight(.bold))
            .tracking(1)
            .foregroundStyle(StreamifyColors.textSecondary)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, StreamifyDimens.spaceLG)
    }

    private var shareButton: some View {
        Button {
            Haptics.play(.likeHeartbeat)
            onShare()
        } label: {
            HStack(spacing: StreamifyDimens.spaceSM) {
                Image(systemName: StreamifyIcon.share)
                    .font(.system(size: 15, weight: .semibold))
                Text("Share your wrapped")
                    .font(StreamifyType.chipText.weight(.bold))
            }
            .foregroundStyle(StreamifyColors.textOnActiveChip)
            .frame(maxWidth: .infinity)
            .frame(height: 48)
            .background(StreamifyColors.activeControl, in: Capsule())
            .padding(.horizontal, StreamifyDimens.spaceLG)
        }
        .buttonStyle(.pressableCard)
    }
}

// MARK: - Stat tile (Android wrapped stat block, native grid cell)

private struct StatTile: View {

    let stat: WrappedStat

    var body: some View {
        HStack(spacing: StreamifyDimens.spaceMD) {
            ZStack {
                Circle().fill(
                    StreamifyGradients.linear(StreamifyGradients.preset(forSeed: stat.id))
                )
                Image(systemName: stat.symbol)
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundStyle(Color.white)
            }
            .frame(width: 32, height: 32)
            VStack(alignment: .leading, spacing: 2) {
                Text(stat.value)
                    .font(StreamifyType.titleMedium)
                    .foregroundStyle(StreamifyColors.textMain)
                    .lineLimit(1)
                    .minimumScaleFactor(0.7)
                Text(stat.label)
                    .font(StreamifyType.caption)
                    .foregroundStyle(StreamifyColors.textSecondary)
                    .lineLimit(1)
            }
            Spacer(minLength: 0)
        }
        .padding(StreamifyDimens.spaceMD)
        .cardSurface(hoverable: false)
    }
}

// MARK: - Top-track row (Android rank badge row, gold/silver/bronze)

private struct TopTrackRow: View {

    let rank: Int
    let track: VirtualShelfTrack
    let onPlay: () -> Void

    private var rankColor: Color {
        switch rank {
        case 1: return Color(streamifyHex: 0xFFD700)
        case 2: return Color(streamifyHex: 0xC0C0C0)
        case 3: return Color(streamifyHex: 0xCD7F32)
        default: return StreamifyColors.textSecondary
        }
    }

    var body: some View {
        Button(action: onPlay) {
            HStack(spacing: StreamifyDimens.spaceMD) {
                Text("#\(rank)")
                    .font(StreamifyType.titleSmall)
                    .monospacedDigit()
                    .foregroundStyle(rankColor)
                    .frame(width: 28, alignment: .leading)
                TrackCoverArt(
                    title: track.title,
                    artist: track.artist,
                    artworkUrl: track.artworkUrl,
                    size: 44
                )
                VStack(alignment: .leading, spacing: 2) {
                    Text(track.title)
                        .font(StreamifyType.songTitle)
                        .foregroundStyle(StreamifyColors.textMain)
                        .lineLimit(1)
                    Text(track.artist)
                        .font(StreamifyType.songArtist)
                        .foregroundStyle(StreamifyColors.textSecondary)
                        .lineLimit(1)
                }
                Spacer(minLength: 0)
                Image(systemName: StreamifyIcon.play)
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundStyle(StreamifyColors.activeControl)
            }
            .padding(.horizontal, StreamifyDimens.spaceMD)
            .padding(.vertical, StreamifyDimens.spaceSM)
            .background(StreamifyColors.bgCard, in: StreamifyShapes.card)
            .overlay(StreamifyShapes.card.strokeBorder(StreamifyColors.borderSubtle, lineWidth: 1))
        }
        .buttonStyle(.pressableCard)
    }
}

#Preview("Wrapped") {
    NavigationStack {
        StatsWrappedView()
    }
}
