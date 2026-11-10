import SwiftUI

// ─────────────────────────────────────────────────────────────────────────────
// SW-ENG2-03 / WS-B B2 — HomeView: the discovery feed. Native port of
// ui/screens/HomeScreen.kt (the YTM-style home): the morphing Daylist hero
// (the circadian slot + its recommendations), the Daily Mixes rail and the
// universal shelf stack (quick picks, made for you, recently played, top
// played, trending).
//
// WS-B B2 (de-fixtured production path): the view OWNS its HomeViewModel —
// `.task` loads on first appearance, pull-to-refresh recomputes — and every
// section renders from the model's projections over the LIVE FeatureLibrary
// lane (UniFFI when the xcframework is linked). The SW-ENG2-03 fixture
// init-default props (initialShelves / daylist / moods / dailyMixes /
// friends) and the fixture-template paging sentinel are gone: empty
// projections simply don't render, and a fresh engine degrades to the light
// "Find something to play" hint — never a crash, never an error screen. The
// shell keeps its push contract through the on* callbacks; the mood
// taxonomy and the friend ticker await their feed lanes and stay unwired
// until then.
// ─────────────────────────────────────────────────────────────────────────────

@MainActor
public struct HomeView: View {

    // MARK: Callbacks out (shell contract) — data lives in the owned model

    public var onOpenTrack: (VirtualShelfTrack) -> Void
    public var onOpenAlbum: (String) -> Void
    public var onOpenArtist: (String) -> Void
    /// The mood rail's tap lane — kept for the shell contract; the feed
    /// carries no mood taxonomy yet, so nothing invokes it (WS-B B2).
    public var onOpenMood: (MoodGenre) -> Void
    public var onOpenDaylist: () -> Void
    public var onOpenMix: (String) -> Void
    /// Rides through to the shelf feed's joinable-room cards.
    public var onJoinJam: (String) -> Void
    /// The friend ticker's profile lane — kept for the shell contract; the
    /// feed carries no friend activity yet (WS-B B2).
    public var onOpenProfile: (String) -> Void

    // The owned view model (WS-B B2): bare construction rides the live lane.
    // Previews may inject a scripted model instead.
    @State private var model = HomeViewModel()

    // INTERNAL init: the `model:` injection parameter carries the
    // module-internal HomeViewModel, and a public initializer cannot take
    // a less-visible parameter type. The shell and the #Preview host are
    // same-module callers; nothing outside the module constructs this view.
    init(
        model: HomeViewModel? = nil,
        onOpenTrack: @escaping (VirtualShelfTrack) -> Void = { _ in },
        onOpenAlbum: @escaping (String) -> Void = { _ in },
        onOpenArtist: @escaping (String) -> Void = { _ in },
        onOpenMood: @escaping (MoodGenre) -> Void = { _ in },
        onOpenDaylist: @escaping () -> Void = {},
        onOpenMix: @escaping (String) -> Void = { _ in },
        onJoinJam: @escaping (String) -> Void = { _ in },
        onOpenProfile: @escaping (String) -> Void = { _ in }
    ) {
        if let model {
            _model = State(initialValue: model)
        }
        self.onOpenTrack = onOpenTrack
        self.onOpenAlbum = onOpenAlbum
        self.onOpenArtist = onOpenArtist
        self.onOpenMood = onOpenMood
        self.onOpenDaylist = onOpenDaylist
        self.onOpenMix = onOpenMix
        self.onJoinJam = onJoinJam
        self.onOpenProfile = onOpenProfile
    }

    // MARK: Body

    public var body: some View {
        ScrollView {
            LazyVStack(spacing: 0) {
                feedContent
            }
            .padding(.bottom, StreamifyDimens.spaceGiant)
        }
        .background(StreamifyColors.bgBase)
        .navigationTitle("Home")
        .task {
            // Pull-on-demand (Engineer 4's discipline): loads once per
            // appearance; idempotent while a load is in flight.
            model.loadIfNeeded()
        }
        .refreshable {
            await model.refresh()
        }
    }

    @ViewBuilder
    private var feedContent: some View {
        if model.isLoading {
            loadingState
        } else if case .error(let message) = model.state {
            EmptyStateView(
                symbol: StreamifyIcon.wave,
                title: "Couldn't load your feed",
                message: message
            )
        } else {
            loadedFeed
        }
    }

    /// The first-load state: one centered spinner inside the feed's vertical
    /// rhythm (no fixture-shaped skeletons).
    private var loadingState: some View {
        ProgressView()
            .tint(StreamifyColors.textSecondary)
            .frame(maxWidth: .infinity)
            .padding(.vertical, StreamifyDimens.spaceXXL)
    }

    // MARK: Feed sections (each renders only what it carries)

    @ViewBuilder
    private var loadedFeed: some View {
        if let daylist = daylistHero {
            YtDaylistHeroBanner(model: daylist, onOpen: onOpenDaylist)
        }
        if !mixShelves.isEmpty {
            YtSectionHeader(title: "Daily mixes", subtitle: "Your artist clusters")
            YtDailyMixRail(mixes: mixShelves) { index in
                guard mixShelves.indices.contains(index) else { return }
                onOpenMix(mixShelves[index].id)
            }
        }
        ForEach(shelves) { shelf in
            YtSectionHeader(
                title: shelf.title,
                subtitle: shelf.subtitle,
                onMore: { onOpenAlbum(shelf.title) }
            )
            UniversalShelfFeedView(
                shelf: shelf,
                onPlayTrack: onOpenTrack,
                onOpenArtist: onOpenArtist,
                onJoinJam: onJoinJam
            )
        }
        if shelves.isEmpty && mixShelves.isEmpty && daylistHero == nil {
            EmptyStateView(
                symbol: StreamifyIcon.musicNote,
                title: "Find something to play",
                message: "Play a few tracks and your feed builds itself from there."
            )
        }
    }

    // MARK: Feed projections (engine currency → shelf currency)

    /// The numbered mixes lowered onto the daily-mix shelf currency.
    private var mixShelves: [VirtualShelf] {
        model.dailyMixes.map { VirtualShelf(dailyMix: $0) }
    }

    /// The Daylist hero lowered from the circadian slot and its
    /// recommendations (the banner's next-up chips + track count).
    private var daylistHero: DaylistHeroModel? {
        let slot = model.circadianSlot
        let tracks = model.circadianRecommendations
        guard !tracks.isEmpty else { return nil }
        return DaylistHeroModel(
            title: slot.title,
            subtitle: "Tuned to your \(slot.bucketName.lowercased()) listening",
            bucketLabel: slot.bucketName,
            trackTitles: tracks.prefix(3).map(\.title),
            trackCount: tracks.count
        )
    }

    /// The shelf stack: each non-empty projection becomes one shelf; empty
    /// projections render nothing (no empty-section clutter on a fresh
    /// engine).
    private var shelves: [VirtualShelf] {
        [
            makeShelf(
                id: "quick-picks",
                title: "Quick picks",
                subtitle: "Start with what you know",
                kind: .quickPicks,
                tracks: model.sessionRecommendations
            ),
            makeShelf(
                id: "made-for-you",
                title: "Made for you",
                subtitle: "Tuned to your taste",
                kind: .supermix,
                tracks: model.madeForYou
            ),
            makeShelf(
                id: "recently-played",
                title: "Recently played",
                subtitle: "Pick up where you left off",
                kind: .listenAgain,
                tracks: model.recent
            ),
            makeShelf(
                id: "top-played",
                title: "Top played",
                subtitle: "The songs you keep coming back to",
                kind: .listenAgain,
                tracks: model.topPlayed
            ),
            makeShelf(
                id: "trending",
                title: "Trending in your orbit",
                subtitle: nil,
                kind: .trending,
                tracks: model.trending
            ),
        ]
        .compactMap { $0 }
    }

    /// One non-empty projection as a shelf; nil when the lane carries
    /// nothing (the section then doesn't render at all).
    private func makeShelf(
        id: String,
        title: String,
        subtitle: String?,
        kind: ShelfKind,
        tracks: [PresentationTrack]
    ) -> VirtualShelf? {
        guard !tracks.isEmpty else { return nil }
        return VirtualShelf(
            id: id,
            title: title,
            subtitle: subtitle,
            kind: kind,
            tracks: tracks.map { VirtualShelfTrack(presentationTrack: $0) }
        )
    }
}

// MARK: - Circadian slot presentation helpers

private extension CircadianSlot {
    /// The hero's human-facing bucket name ("Late night" for lateNight).
    var bucketName: String {
        switch self {
        case .morning: return "Morning"
        case .afternoon: return "Afternoon"
        case .evening: return "Evening"
        case .lateNight: return "Late night"
        }
    }
}

// MARK: - Preview

#Preview("Home feed") {
    HomeViewPreviewHost()
        .preferredColorScheme(.dark)
}

/// The #Preview-only host: scripts a PreviewFeatureLibrary with hand-rolled
/// content (never PreviewCatalog — production paths stay fixture-free) and
/// hands the view model it drives to the real HomeView.
@MainActor
private struct HomeViewPreviewHost: View {
    @State private var model: HomeViewModel

    init() {
        let tracks: [PresentationTrack] = [
            PresentationTrack(id: "p1", title: "Obsidian Sunrise", artist: "Sireen", durationMs: 251_000),
            PresentationTrack(id: "p2", title: "Bit-Perfect Rain", artist: "Aurora Nine", durationMs: 198_000),
            PresentationTrack(id: "p3", title: "Vellum Static", artist: "Low Orbit Choir", durationMs: 312_000),
            PresentationTrack(id: "p4", title: "48k Meridian", artist: "DAC Kingsley", durationMs: 224_000),
            PresentationTrack(id: "p5", title: "Neon Hallway", artist: "Vetra", durationMs: 186_000),
            PresentationTrack(id: "p6", title: "Sable Meadow", artist: "Ines Wilder", durationMs: 275_000),
        ]
        let library = PreviewFeatureLibrary()
        library.homeFeedContent = HomeFeed(
            sessionRecommendations: Array(tracks.prefix(4)),
            circadianRecommendations: Array(tracks.suffix(3)),
            madeForYou: Array(tracks.dropFirst(2)),
            recent: tracks,
            topPlayed: Array(tracks.reversed()),
            trending: Array(tracks.dropFirst(1)),
            dailyMixes: (1...3).map { mix in
                DailyMix(
                    number: mix,
                    seedArtist: "Sireen, Aurora Nine and more",
                    tracks: Array(tracks.dropFirst(mix - 1).prefix(4))
                )
            }
        )
        _model = State(initialValue: HomeViewModel(library: library))
    }

    var body: some View {
        NavigationStack {
            HomeView(model: model)
        }
    }
}
