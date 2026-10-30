import SwiftUI

// ─────────────────────────────────────────────────────────────────────────────
// SW-ENG2-03 — HomeView: the discovery feed. Native port of
// ui/screens/HomeScreen.kt (the YTM-style home): broadcast banner, morphing
// Daylist hero, mood filter rail, Daily Mixes 1–6, the universal shelf stack
// and the live friend-activity ticker — then an infinite-scroll sentinel that
// reports through onLoadMore and pages in copies of the shelf template
// (page-suffixed ids) up to 4 pages, where the Sireen branding footer takes
// over. Data arrives via props (PreviewCatalog defaults); the shell owns
// push/pop through the on* callbacks.
// ─────────────────────────────────────────────────────────────────────────────

public struct HomeView: View {

    // MARK: Props — data in, callbacks out (shell contract)

    public var bannerMessage: String?
    public var initialShelves: [VirtualShelf]
    public var daylist: DaylistHeroModel?
    public var moods: [MoodGenre]
    public var dailyMixes: [VirtualShelf]
    public var friends: [FriendActivityEntry]

    public var onOpenTrack: (VirtualShelfTrack) -> Void
    public var onOpenAlbum: (String) -> Void
    public var onOpenArtist: (String) -> Void
    public var onOpenMood: (MoodGenre) -> Void
    public var onOpenDaylist: () -> Void
    public var onOpenMix: (String) -> Void
    public var onJoinJam: (String) -> Void
    public var onOpenProfile: (String) -> Void
    public var onLoadMore: () -> Void

    // MARK: Ephemeral feed state

    @State private var shelves: [VirtualShelf]
    @State private var pagesLoaded = 1
    @State private var selectedMoodId: String?

    private static let maxPages = 4

    public init(
        bannerMessage: String? = nil,
        initialShelves: [VirtualShelf] = PreviewCatalog.shelves,
        daylist: DaylistHeroModel? = PreviewCatalog.daylistHero,
        moods: [MoodGenre] = PreviewCatalog.moods,
        dailyMixes: [VirtualShelf] = PreviewCatalog.dailyMixes,
        friends: [FriendActivityEntry] = PreviewCatalog.friends,
        onOpenTrack: @escaping (VirtualShelfTrack) -> Void = { _ in },
        onOpenAlbum: @escaping (String) -> Void = { _ in },
        onOpenArtist: @escaping (String) -> Void = { _ in },
        onOpenMood: @escaping (MoodGenre) -> Void = { _ in },
        onOpenDaylist: @escaping () -> Void = {},
        onOpenMix: @escaping (String) -> Void = { _ in },
        onJoinJam: @escaping (String) -> Void = { _ in },
        onOpenProfile: @escaping (String) -> Void = { _ in },
        onLoadMore: @escaping () -> Void = {}
    ) {
        self.bannerMessage = bannerMessage
        self.initialShelves = initialShelves
        self.daylist = daylist
        self.moods = moods
        self.dailyMixes = dailyMixes
        self.friends = friends
        self.onOpenTrack = onOpenTrack
        self.onOpenAlbum = onOpenAlbum
        self.onOpenArtist = onOpenArtist
        self.onOpenMood = onOpenMood
        self.onOpenDaylist = onOpenDaylist
        self.onOpenMix = onOpenMix
        self.onJoinJam = onJoinJam
        self.onOpenProfile = onOpenProfile
        self.onLoadMore = onLoadMore
        _shelves = State(initialValue: initialShelves)
    }

    // MARK: Body

    public var body: some View {
        ScrollView {
            LazyVStack(spacing: 0) {
                if let bannerMessage {
                    BroadcastBanner(message: bannerMessage)
                        .padding(.horizontal, StreamifyDimens.spaceLG)
                        .padding(.top, StreamifyDimens.spaceSM)
                }
                if let daylist {
                    YtDaylistHeroBanner(model: daylist, onOpen: onOpenDaylist)
                }
                YtMoodFilterRail(moods: moods, selected: $selectedMoodId)
                    .padding(.top, StreamifyDimens.spaceSM)
                    .onChange(of: selectedMoodId) { _, selectedId in
                        guard let selectedId,
                              let mood = moods.first(where: { $0.id == selectedId }) else { return }
                        onOpenMood(mood)
                    }
                if !dailyMixes.isEmpty {
                    YtSectionHeader(title: "Daily mixes", subtitle: "Your artist clusters • 1–6")
                    YtDailyMixRail(mixes: dailyMixes) { index in
                        guard dailyMixes.indices.contains(index) else { return }
                        onOpenMix(dailyMixes[index].id)
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
                if !friends.isEmpty {
                    YtSectionHeader(title: "Friends", subtitle: "Listening now")
                    friendTicker
                }
                loadingSentinel
            }
            .padding(.bottom, StreamifyDimens.spaceGiant)
        }
        .background(StreamifyColors.bgBase)
        .navigationTitle("Home")
    }

    // MARK: Friend activity ticker (Gap #32)

    private var friendTicker: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: StreamifyDimens.spaceMD) {
                ForEach(friends) { friend in
                    FriendActivityCard(
                        friend: friend,
                        onJoin: { code in onJoinJam(code) },
                        onOpenProfile: { userId in onOpenProfile(userId) }
                    )
                    .frame(width: 360)
                }
            }
            .padding(.horizontal, StreamifyDimens.spaceLG)
            .padding(.vertical, StreamifyDimens.spaceXS)
        }
    }

    // MARK: Infinite scroll (SW-ENG2-03)

    /// Sentinel after the last shelf: reports the tail through onLoadMore,
    /// pages in a copy of the shelf template (ids suffixed "-p<page>") up to
    /// 4 pages; past the cap the Sireen branding footer replaces the spinner.
    private var loadingSentinel: some View {
        Group {
            if pagesLoaded < Self.maxPages {
                HStack(spacing: StreamifyDimens.spaceSM) {
                    ProgressView()
                        .tint(StreamifyColors.textSecondary)
                    Text("Loading more shelves…")
                        .font(StreamifyType.bodySmall)
                        .foregroundStyle(StreamifyColors.textSecondary)
                }
                .frame(maxWidth: .infinity)
                .padding(.vertical, StreamifyDimens.spaceXXL)
                .onAppear { loadMoreShelves() }
            } else {
                VStack(spacing: StreamifyDimens.spaceMD) {
                    SireenBrandingBadge()
                    Text("You've reached the end of the feed")
                        .font(StreamifyType.caption)
                        .foregroundStyle(StreamifyColors.textTertiary)
                }
                .frame(maxWidth: .infinity)
                .padding(.vertical, StreamifyDimens.spaceXXL)
            }
        }
    }

    private func loadMoreShelves() {
        onLoadMore()
        guard pagesLoaded < Self.maxPages, !initialShelves.isEmpty else {
            pagesLoaded = Self.maxPages
            return
        }
        let page = pagesLoaded + 1
        let template = initialShelves.map { shelf in
            VirtualShelf(
                id: "\(shelf.id)-p\(page)",
                title: shelf.title,
                subtitle: shelf.subtitle,
                kind: shelf.kind,
                tracks: shelf.tracks
            )
        }
        pagesLoaded = page
        shelves.append(contentsOf: template)
    }
}

#Preview("Home feed") {
    NavigationStack {
        HomeView(
            bannerMessage: "Jam rooms now support mesh beacons — 1.2M listeners joined this week."
        )
    }
    .preferredColorScheme(.dark)
}
