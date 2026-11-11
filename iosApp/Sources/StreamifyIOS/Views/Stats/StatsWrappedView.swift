import SwiftUI

// ─────────────────────────────────────────────────────────────────────────────
// SW-ENG2 (Stats wave) — StatsWrappedView: the year-in-sound telemetry surface.
//
// Native port of ui/screens/StatsWrappedScreen.kt: the YtWrappedHeroCard hero
// at the top, the "Your year in sound" 2-column stat-tile grid (icon in a
// seeded gradient circle + label + value), the BPM persona card, the genre
// breakdown (honest share fractions), the top-5 tracks list with
// gold/silver/bronze rank chips, and the primary capsule Share CTA.
//
// WS-B B2 (de-fixturing): the screen OWNS its StatsWrappedViewModel and
// rides the live feature lane (`AppServices.library` → UniFFIFeatureLibrary
// when the engine is linked) — every number renders the VM's engine
// projections and the empty state is the HONEST engine state (a fresh
// install has nothing to wrap yet). No PreviewCatalog seeding on the
// production path; previews inject a scripted PreviewFeatureLibrary.
// ─────────────────────────────────────────────────────────────────────────────

@MainActor
public struct StatsWrappedView: View {

    /// WS-B B2: the screen's own state holder — constructed bare, it rides
    /// the live feature lane automatically (loaded once, force-refreshable,
    /// never polled).
    @State private var model = StatsWrappedViewModel()

    public var onShare: () -> Void
    public var onPlayTrack: (VirtualShelfTrack) -> Void

    public init(
        onShare: @escaping () -> Void = {},
        onPlayTrack: @escaping (VirtualShelfTrack) -> Void = { _ in }
    ) {
        self.onShare = onShare
        self.onPlayTrack = onPlayTrack
    }

    /// Preview/test lane: inject a scripted view model (the bare init above
    /// rides `AppServices.library`).
    init(
        model: StatsWrappedViewModel,
        onShare: @escaping () -> Void = {},
        onPlayTrack: @escaping (VirtualShelfTrack) -> Void = { _ in }
    ) {
        self._model = State(initialValue: model)
        self.onShare = onShare
        self.onPlayTrack = onPlayTrack
    }

    /// Honest emptiness check — a fresh install reports zero minutes and no
    /// tops; that is a valid, calm state, not an error.
    private var hasWrappedData: Bool {
        model.totalMinutes > 0 || model.totalTracks > 0 || model.likedSongs > 0
            || !model.topTracks.isEmpty || !model.topGenres.isEmpty
            || !model.topArtists.isEmpty || !model.monthlyMinutes.isEmpty
            || !model.milestones.isEmpty
    }

    public var body: some View {
        ScrollView {
            LazyVStack(spacing: StreamifyDimens.spaceLG) {
                if model.isLoading {
                    loadingPlaceholder
                } else if !hasWrappedData {
                    EmptyStateView(
                        symbol: StreamifyIcon.star,
                        title: "Nothing to wrap yet",
                        message: "Play something — your year in sound grows with every listen."
                    )
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, StreamifyDimens.spaceGiant)
                } else {
                    content
                }
            }
            .padding(.vertical, StreamifyDimens.spaceLG)
        }
        .background(StreamifyColors.bgBase)
        .task { model.loadIfNeeded() }
        .refreshable { await model.refresh() }
    }

    // MARK: - Loaded content (everything derives from the VM projections)

    @ViewBuilder
    private var content: some View {
        YtWrappedHeroCard(
            headline: heroStat,
            stats: heroSideStats
        )
        sectionHeader("YOUR YEAR IN SOUND")
        LazyVGrid(
            columns: [
                GridItem(.flexible(), spacing: StreamifyDimens.spaceSM),
                GridItem(.flexible(), spacing: StreamifyDimens.spaceSM),
            ],
            spacing: StreamifyDimens.spaceSM
        ) {
            ForEach(statTiles) { stat in
                StatTile(stat: stat)
            }
        }
        .padding(.horizontal, StreamifyDimens.spaceLG)
        YtPersonaCard(
            name: model.personaName,
            subtitle: "\(model.personaEmoji) \(model.personaDescription) · \(model.averageBpm) BPM",
            onOpen: {}
        )
        .padding(.horizontal, StreamifyDimens.spaceLG)
        if !model.topGenres.isEmpty {
            sectionHeader("TOP GENRES")
            genreBreakdown
        }
        if !model.topTracks.isEmpty {
            sectionHeader("YOUR TOP 5 SONGS")
            ForEach(Array(model.topTracks.prefix(5).enumerated()), id: \.element.id) { index, track in
                TopTrackRow(rank: index + 1, track: track) {
                    Haptics.play(.playPauseClick)
                    onPlayTrack(VirtualShelfTrack(presentationTrack: track, platformOrigin: "local"))
                }
                .padding(.horizontal, StreamifyDimens.spaceLG)
            }
        }
        shareButton
    }

    /// The hero headline: total minutes (the Android hero's #1 stat).
    private var heroStat: WrappedStat {
        WrappedStat(
            id: "minutes",
            label: "Minutes listened",
            value: model.totalMinutes.formatted(),
            symbol: StreamifyIcon.clock
        )
    }

    /// The hero's secondary stat blocks.
    private var heroSideStats: [WrappedStat] {
        [
            WrappedStat(
                id: "tracks", label: "Tracks played",
                value: model.totalTracks.formatted(), symbol: StreamifyIcon.musicNoteList
            ),
            WrappedStat(
                id: "liked", label: "Liked songs",
                value: model.likedSongs.formatted(), symbol: StreamifyIcon.heartFill
            ),
            WrappedStat(
                id: "rotations", label: "Heavy rotations",
                value: model.topPlayedCount.formatted(), symbol: StreamifyIcon.repeatAll
            ),
            WrappedStat(
                id: "tempo", label: "Average tempo",
                value: "\(model.averageBpm) BPM", symbol: StreamifyIcon.wave
            ),
        ]
    }

    /// The full tile grid: hero stat + side stats + the engine-reported tops.
    private var statTiles: [WrappedStat] {
        var tiles = [heroStat] + heroSideStats
        if let topGenre = model.topGenres.first {
            tiles.append(
                WrappedStat(
                    id: "genre", label: "Top genre",
                    value: topGenre.name, symbol: StreamifyIcon.eq
                )
            )
        }
        if let topArtist = model.topArtists.first {
            tiles.append(
                WrappedStat(
                    id: "artist", label: "Top artist",
                    value: topArtist.name, symbol: StreamifyIcon.personPlus, rank: 1
                )
            )
        }
        return tiles
    }

    // MARK: - Genre breakdown (stacked share bar + legend, honest fractions)

    private var genreBreakdown: some View {
        VStack(alignment: .leading, spacing: StreamifyDimens.spaceMD) {
            GeometryReader { proxy in
                HStack(spacing: 2) {
                    ForEach(model.topGenres) { genre in
                        RoundedRectangle(cornerRadius: StreamifyRadius.sm, style: .continuous)
                            .fill(Self.genreColor(genre.name))
                            .frame(width: proxy.size.width * max(genre.fraction, 0.02))
                    }
                }
            }
            .frame(height: 8)
            .clipShape(Capsule())
            .background(StreamifyColors.bgCard, in: Capsule())

            VStack(spacing: StreamifyDimens.spaceSM) {
                ForEach(model.topGenres) { genre in
                    HStack(spacing: StreamifyDimens.spaceSM) {
                        Circle()
                            .fill(Self.genreColor(genre.name))
                            .frame(width: 10, height: 10)
                        Text(genre.name)
                            .font(StreamifyType.bodyMedium)
                            .foregroundStyle(StreamifyColors.textMain)
                            .lineLimit(1)
                        Spacer(minLength: StreamifyDimens.spaceMD)
                        Text("\(Int((genre.fraction * 100).rounded()))%")
                            .font(StreamifyType.caption)
                            .monospacedDigit()
                            .foregroundStyle(StreamifyColors.textSecondary)
                    }
                    .accessibilityElement(children: .combine)
                }
            }
        }
        .padding(StreamifyDimens.spaceMD)
        .cardSurface(hoverable: false)
        .padding(.horizontal, StreamifyDimens.spaceLG)
    }

    /// Deterministic gradient per genre name (the preset-table discipline).
    private static func genreColor(_ name: String) -> Color {
        StreamifyGradients.preset(forSeed: name).first ?? StreamifyColors.textSecondary
    }

    // MARK: - Loading + share

    /// The load-in-flight state — a redacted hero card (the Android
    /// "Computing your audio DNA…" spinner in the native idiom).
    private var loadingPlaceholder: some View {
        VStack(spacing: StreamifyDimens.spaceSM) {
            RoundedRectangle(cornerRadius: StreamifyRadius.lg, style: .continuous)
                .fill(StreamifyColors.bgCard)
                .frame(height: 180)
            HStack(spacing: StreamifyDimens.spaceSM) {
                ForEach(0..<2, id: \.self) { _ in
                    RoundedRectangle(cornerRadius: StreamifyRadius.md, style: .continuous)
                        .fill(StreamifyColors.bgCard)
                        .frame(height: 64)
                        .frame(maxWidth: .infinity)
                }
            }
        }
        .padding(.horizontal, StreamifyDimens.spaceLG)
        .redacted(reason: .placeholder)
        .accessibilityLabel("Computing your audio DNA")
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
    let track: PresentationTrack
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
    let scripted = PreviewFeatureLibrary()
    scripted.statsContent = WrappedStats(
        totalMinutes: 92_640,
        totalTracks: 4_812,
        likedSongs: 933,
        topPlayedCount: 47,
        averageBpm: 126,
        topGenres: [
            StatsGenre(name: "Ambient Techno", fraction: 0.34),
            StatsGenre(name: "Indie Rock", fraction: 0.27),
            StatsGenre(name: "Hip-Hop", fraction: 0.22),
            StatsGenre(name: "Pop", fraction: 0.17),
        ],
        topTracks: PreviewCatalog.tracks[0..<5].map { track in
            PresentationTrack(
                id: track.id,
                title: track.title,
                artist: track.artist,
                durationMs: track.durationSec * 1_000,
                artworkUrl: track.artworkUrl
            )
        },
        topArtists: [StatsArtist(name: "Aurora Nine", plays: 412)]
    )
    return NavigationStack {
        StatsWrappedView(model: StatsWrappedViewModel(library: scripted))
    }
}

#Preview("Wrapped — empty") {
    NavigationStack {
        StatsWrappedView()
    }
}
