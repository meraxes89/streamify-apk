import SwiftUI

// ─────────────────────────────────────────────────────────────────────────────
// SW-ENG2-03 — UniversalShelfFeedView: ONE VirtualShelf rendered as a
// horizontal rail whose card ANATOMY switches on shelf.kind.
//
// Native port of ui/screens/UniversalHomeScreen.kt (Android renders one
// single-card LazyRow per shelf; here each kind gets its true YT-style
// anatomy): quickPicks → YtQuickPicksCarousel, listenAgain / trending /
// onlineDiscovery → YtMediumCard rail, dailyMix → YtDailyMixRail, hero and
// supermix → YtSupermixCard rail, friendActivity → FriendActivityCard rail,
// moods → YtMoodFilterRail. Any other kind (recentSearches, future kinds)
// falls to the default YtMediumCard rail so the switch stays exhaustive.
// HomeView stacks one of these per shelf under a YtSectionHeader.
// ─────────────────────────────────────────────────────────────────────────────

public struct UniversalShelfFeedView: View {

    public var shelf: VirtualShelf
    public var onPlayTrack: (VirtualShelfTrack) -> Void
    public var onOpenArtist: (String) -> Void
    /// Jam-room join hook — read only by the friendActivity branch.
    public var onJoinJam: ((String) -> Void)?

    /// Ephemeral chip selection for the moods branch (a visual filter).
    @State private var selectedMoodId: String?

    public init(
        shelf: VirtualShelf,
        onPlayTrack: @escaping (VirtualShelfTrack) -> Void = { _ in },
        onOpenArtist: @escaping (String) -> Void = { _ in },
        onJoinJam: ((String) -> Void)? = nil
    ) {
        self.shelf = shelf
        self.onPlayTrack = onPlayTrack
        self.onOpenArtist = onOpenArtist
        self.onJoinJam = onJoinJam
    }

    public var body: some View {
        rail
    }

    // MARK: Kind → anatomy (exhaustive; default = medium card rail)

    @ViewBuilder
    private var rail: some View {
        switch shelf.kind {
        case .quickPicks:
            YtQuickPicksCarousel(tracks: shelf.tracks) { index in
                guard shelf.tracks.indices.contains(index) else { return }
                onPlayTrack(shelf.tracks[index])
            }
        case .listenAgain, .trending, .onlineDiscovery:
            mediumCardRail
        case .dailyMix:
            YtDailyMixRail(mixes: [shelf]) { _ in
                if let first = shelf.tracks.first { onPlayTrack(first) }
            }
        case .hero, .supermix:
            supermixRail
        case .friendActivity:
            friendRail
        case .moods:
            YtMoodFilterRail(moods: moodChips, selected: $selectedMoodId)
        default:
            mediumCardRail
        }
    }

    // MARK: Rails

    /// The default anatomy: gradient-seed art + title + artist, with the
    /// native context menu lowering "Go to artist" onto onOpenArtist.
    private var mediumCardRail: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: StreamifyDimens.spaceMD) {
                ForEach(shelf.tracks) { track in
                    YtMediumCard(track: track) { onPlayTrack(track) }
                        .contextMenu {
                            Button {
                                onOpenArtist(track.artist)
                            } label: {
                                Label("Go to artist", systemImage: StreamifyIcon.avatar)
                            }
                        }
                }
            }
            .padding(.horizontal, StreamifyDimens.spaceLG)
            .padding(.vertical, StreamifyDimens.spaceXS)
        }
    }

    private var supermixRail: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: StreamifyDimens.spaceMD) {
                ForEach(shelf.tracks) { track in
                    YtSupermixCard(
                        title: track.title,
                        subtitle: track.artist,
                        seed: track.title,
                        onTap: { onPlayTrack(track) }
                    )
                }
            }
            .padding(.horizontal, StreamifyDimens.spaceLG)
            .padding(.vertical, StreamifyDimens.spaceXS)
        }
    }

    private var friendRail: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: StreamifyDimens.spaceMD) {
                ForEach(friendEntries) { entry in
                    FriendActivityCard(friend: entry, onJoin: onJoinJam, onOpenProfile: nil)
                        .frame(width: 360)
                }
            }
            .padding(.horizontal, StreamifyDimens.spaceLG)
            .padding(.vertical, StreamifyDimens.spaceXS)
        }
    }

    // MARK: Shelf tracks lowered onto the branch models

    /// Friend cards from shelf tracks: the artist stands in as the listener
    /// and the deterministic JAM-#### code as the live room (the friend
    /// shelf exists exactly because these rooms are joinable).
    private var friendEntries: [FriendActivityEntry] {
        shelf.tracks.map { track in
            FriendActivityEntry(
                userId: track.id,
                displayName: track.artist,
                trackTitle: track.title,
                trackArtist: track.artist,
                lastActiveLabel: "Now playing",
                sessionCode: sessionCode(for: track.id)
            )
        }
    }

    /// Mood chips from shelf tracks, each carrying its own seed gradient.
    private var moodChips: [MoodGenre] {
        shelf.tracks.map { track in
            MoodGenre(
                id: track.id,
                name: track.title,
                gradient: StreamifyGradients.preset(forSeed: track.title)
            )
        }
    }

    /// Deterministic djb2 room code — the same track id always joins the
    /// same room (mirrors the StreamifyGradients seed discipline).
    private func sessionCode(for id: String) -> String {
        var hash: UInt64 = 5381
        for scalar in id.unicodeScalars {
            hash = (hash &<< 5) &+ hash &+ UInt64(scalar.value)
        }
        return "JAM-\(1_000 + Int(hash % 9_000))"
    }
}

// MARK: - Preview (every shelf kind renders)

private let previewExtraShelves: [VirtualShelf] = [
    VirtualShelf(
        id: "prev-daily", title: "Daily Mix 1",
        subtitle: "Sireen, Aurora Nine, Vetra and more",
        kind: .dailyMix, tracks: Array(PreviewCatalog.tracks.prefix(4))
    ),
    VirtualShelf(
        id: "prev-hero", title: "Supermix for you",
        kind: .hero, tracks: Array(PreviewCatalog.tracks.prefix(3))
    ),
    VirtualShelf(
        id: "prev-supermix", title: "My Supermix",
        kind: .supermix, tracks: Array(PreviewCatalog.tracks[3..<6])
    ),
    VirtualShelf(
        id: "prev-friends", title: "Friends",
        kind: .friendActivity, tracks: Array(PreviewCatalog.tracks[6..<9])
    ),
    VirtualShelf(
        id: "prev-moods", title: "Moods",
        kind: .moods, tracks: Array(PreviewCatalog.tracks[9..<12])
    ),
    VirtualShelf(
        id: "prev-recent", title: "Recent searches",
        kind: .recentSearches, tracks: Array(PreviewCatalog.tracks[10..<12])
    ),
]

#Preview("Universal shelf feed") {
    ScrollView {
        VStack(spacing: StreamifyDimens.spaceLG) {
            ForEach(PreviewCatalog.shelves) { shelf in
                UniversalShelfFeedView(shelf: shelf)
            }
            ForEach(previewExtraShelves) { shelf in
                UniversalShelfFeedView(shelf: shelf)
            }
        }
        .padding(.vertical, StreamifyDimens.spaceLG)
    }
    .background(StreamifyColors.bgBase)
    .preferredColorScheme(.dark)
}
