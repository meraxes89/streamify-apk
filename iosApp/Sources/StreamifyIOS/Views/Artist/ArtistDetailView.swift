import SwiftUI

// ─────────────────────────────────────────────────────────────────────────────
// SW-ENG2-03 — ArtistDetailView: the artist page. Native port of
// ui/screens/ArtistScreen.kt with the YTM parallax anatomy the Android screen
// lacked: a stretchy GeometryReader hero (320 pt seeded gradient art that fills
// the overscroll), a sticky condensed bar whose gradient + artist name fade in
// once the scroll offset crosses the threshold (preference → @State), one-tap
// Follow, Play + Shuffle, "Popular" rows, an Albums rail and the Clips rail.
//
// WS-B B2 — fixture purge: the `tracks` parameter is GONE; the page sources
// everything from LIVE SEARCH by artist name through an internal
// SearchViewModel (the only engine surface): song rows → "Popular", album
// rows → the Albums rail. The clips rail had no engine surface and was
// removed; #Previews script rows through the preview-only `scriptedResults`
// lane. No PreviewCatalog in the production path.
// ─────────────────────────────────────────────────────────────────────────────

@MainActor
public struct ArtistDetailView: View {

    public var artistName: String
    public var isFollowing: Bool
    public var onFollow: ((Bool) -> Void)?
    public var onPlayTrack: (VirtualShelfTrack) -> Void
    public var onOpenAlbum: (String) -> Void
    public var onBack: (() -> Void)?

    /// #Preview / test lane only: scripted resolver rows rendered through the
    /// exact live-search path production uses; empty in production (WS-B B2).
    private let scriptedResults: [SearchRow]

    /// WS-B B2: the live "search by name" loader behind the whole page.
    @State private var search = SearchViewModel()

    /// 0 → 1 as the hero collapses past the threshold (quantized).
    @State private var condenseProgress: Double = 0
    private static let heroHeight: CGFloat = 320
    private static let condenseThreshold: CGFloat = 180

    public init(
        artistName: String,
        isFollowing: Bool = false,
        onFollow: ((Bool) -> Void)? = { _ in },
        onPlayTrack: @escaping (VirtualShelfTrack) -> Void = { _ in },
        onOpenAlbum: @escaping (String) -> Void = { _ in },
        onBack: (() -> Void)? = {}
    ) {
        self.init(
            artistName: artistName,
            scriptedResults: [],
            isFollowing: isFollowing,
            onFollow: onFollow,
            onPlayTrack: onPlayTrack,
            onOpenAlbum: onOpenAlbum,
            onBack: onBack
        )
    }

    /// #Preview / test lane (WS-B B2): scripted resolver rows flow through
    /// the same rendering path as live search; production callers use the
    /// public init and always hit the real lane.
    init(
        artistName: String,
        scriptedResults: [SearchRow] = [],
        isFollowing: Bool = false,
        onFollow: ((Bool) -> Void)? = { _ in },
        onPlayTrack: @escaping (VirtualShelfTrack) -> Void = { _ in },
        onOpenAlbum: @escaping (String) -> Void = { _ in },
        onBack: (() -> Void)? = {}
    ) {
        self.artistName = artistName
        self.scriptedResults = scriptedResults
        self.isFollowing = isFollowing
        self.onFollow = onFollow
        self.onPlayTrack = onPlayTrack
        self.onOpenAlbum = onOpenAlbum
        self.onBack = onBack
    }

    // MARK: - Row sources (WS-B B2)

    /// The resolver rows behind the page: scripted rows when present
    /// (previews/tests), otherwise the live search results.
    private var resultRows: [SearchRow] {
        scriptedResults.isEmpty ? search.currentResults : scriptedResults
    }

    /// The artist's top songs ("Popular"): the live search rows typed .song,
    /// mapped onto the view currency.
    private var topSongs: [VirtualShelfTrack] {
        resultRows
            .filter { $0.kind == .song }
            .map { VirtualShelfTrack(presentationTrack: $0.asPresentationTrack) }
    }

    /// The artist's albums: the live search rows typed .album.
    private var albumCards: [VirtualShelfTrack] {
        resultRows
            .filter { $0.kind == .album }
            .map { VirtualShelfTrack(presentationTrack: $0.asPresentationTrack) }
    }

    /// True until the first live result lands (`.idle` only ever precedes
    /// the initial submit) — keeps the empty state from flashing mid-flight.
    private var isAwaitingResults: Bool {
        if !scriptedResults.isEmpty { return false }
        if search.isLoading { return true }
        if case .idle = search.state { return true }
        return false
    }

    // MARK: Body

    public var body: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 0) {
                StretchyHeader(height: Self.heroHeight, coordinateSpaceName: "artistScroll") {
                    heroArt
                }
                headerSection
                YtSectionHeader(title: "Popular", subtitle: "\(topSongs.count) tracks")
                if topSongs.isEmpty {
                    if isAwaitingResults {
                        trackSkeleton
                    } else {
                        EmptyStateView(
                            symbol: StreamifyIcon.musicNoteList,
                            title: "No tracks found",
                            message: "Nothing matched \"\(artistName)\" on the resolver lane yet."
                        )
                        .padding(.horizontal, StreamifyDimens.spaceLG)
                        .padding(.top, StreamifyDimens.spaceXL)
                    }
                } else {
                    ForEach(topSongs) { track in
                        TrackListItem(track: track, onPlay: { onPlayTrack(track) })
                            .padding(.horizontal, StreamifyDimens.spaceLG)
                    }
                }
                if !albumCards.isEmpty {
                    YtSectionHeader(title: "Albums", subtitle: artistName)
                    albumsRail
                }
            }
            .padding(.bottom, StreamifyDimens.spaceGiant)
        }
        .coordinateSpace(name: "artistScroll")
        .background(StreamifyColors.bgBase)
        .task {
            // WS-B B2: live search by artist name is the artist page's only
            // engine surface; scripted rows (previews/tests) skip it.
            guard scriptedResults.isEmpty else { return }
            search.submit(query: artistName)
        }
        .onPreferenceChange(HeroStretchKey.self) { minY in
            let progress = min(1, max(0, Double(-minY / Self.condenseThreshold)))
            if abs(progress - condenseProgress) > 0.01 { condenseProgress = progress }
        }
        .overlay(alignment: .top) {
            condensedBar
                .opacity(condenseProgress).allowsHitTesting(false)
        }
        .overlay(alignment: .topLeading) { backButton }
    }

    // MARK: Hero + sticky chrome

    private var heroArt: some View {
        ZStack {
            StreamifyGradients.linear(StreamifyGradients.preset(forSeed: artistName))
            LinearGradient(colors: [.clear, Color.black.opacity(0.38)], startPoint: .top, endPoint: .bottom)
            Image(systemName: StreamifyIcon.avatar)
                .font(.system(size: 118)).foregroundStyle(Color.white.opacity(0.92))
        }
    }

    /// The condensed gradient bar that fades in as the hero collapses.
    private var condensedBar: some View {
        HStack(spacing: StreamifyDimens.spaceMD) {
            Circle().fill(StreamifyGradients.linear(StreamifyGradients.preset(forSeed: artistName)))
                .frame(width: 26, height: 26)
            Text(artistName).font(StreamifyType.headlineSmall)
                .foregroundStyle(StreamifyColors.textMain).lineLimit(1)
            Spacer(minLength: 0)
        }
        .padding(.leading, 54)
        .padding(.trailing, StreamifyDimens.spaceLG)
        .padding(.vertical, StreamifyDimens.spaceSM)
        .background {
            ZStack {
                StreamifyColors.bgPlayer
                StreamifyGradients.linear(StreamifyGradients.preset(forSeed: artistName)).opacity(0.3)
            }
        }
        .overlay(alignment: .bottom) { StreamifyColors.divider.frame(height: 0.5) }
    }

    private var backButton: some View {
        Button {
            onBack?()
        } label: {
            Image(systemName: StreamifyIcon.chevronLeft)
                .font(.system(size: 15, weight: .semibold)).foregroundStyle(Color.white)
                .frame(width: 34, height: 34).background(Circle().fill(Color.black.opacity(0.35)))
                .contentShape(Circle())
        }
        .buttonStyle(.pressableCard).accessibilityLabel("Back")
        .padding(.leading, StreamifyDimens.spaceLG)
        .padding(.top, StreamifyDimens.spaceSM)
    }

    private var headerSection: some View {
        VStack(alignment: .leading, spacing: StreamifyDimens.spaceMD) {
            Text(artistName).font(StreamifyType.displayLarge)
                .foregroundStyle(StreamifyColors.textMain).lineLimit(2)
            HStack(spacing: StreamifyDimens.spaceXS) {
                Image(systemName: StreamifyIcon.friends).font(.system(size: 12))
                Text("\(monthlyListeners) monthly listeners")
            }
            .font(StreamifyType.bodySmall)
            .foregroundStyle(StreamifyColors.textSecondary)
            HStack(spacing: StreamifyDimens.spaceLG) {
                FollowButton(isFollowing: isFollowing, onToggle: onFollow)
                Button(action: playPopular) {
                    HStack(spacing: StreamifyDimens.spaceSM) {
                        Image(systemName: StreamifyIcon.play)
                        Text("Play")
                    }
                    .font(StreamifyType.titleSmall).foregroundStyle(StreamifyColors.textOnActiveChip)
                    .padding(.horizontal, StreamifyDimens.spaceXL).padding(.vertical, StreamifyDimens.spaceSM)
                    .background(Capsule().fill(StreamifyColors.textMain))
                }
                .buttonStyle(.pressableCard)
                Button(action: shufflePopular) {
                    Image(systemName: StreamifyIcon.shuffle)
                        .font(.system(size: 16, weight: .semibold)).foregroundStyle(StreamifyColors.textMain)
                        .frame(width: 40, height: 40)
                        .background(Circle().fill(StreamifyColors.bgChipInactive))
                        .overlay(Circle().strokeBorder(StreamifyColors.borderChip, lineWidth: 1))
                }
                .buttonStyle(.pressableCard).accessibilityLabel("Shuffle artist")
            }
        }
        .padding(.horizontal, StreamifyDimens.spaceLG)
        .padding(.top, StreamifyDimens.spaceLG)
    }

    private var albumsRail: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: StreamifyDimens.spaceMD) {
                ForEach(albumCards) { album in
                    YtMediumCard(track: album) { onOpenAlbum(album.title) }
                }
            }
            .padding(.horizontal, StreamifyDimens.spaceLG).padding(.vertical, StreamifyDimens.spaceXS)
        }
    }

    private func playPopular() {
        if let first = topSongs.first { onPlayTrack(first) }
    }

    private func shufflePopular() {
        if let pick = topSongs.randomElement() { onPlayTrack(pick) }
    }

    /// The loading state — redacted placeholder rows while the resolver lane
    /// answers (mirrors SearchView's skeleton).
    private var trackSkeleton: some View {
        LazyVStack(spacing: StreamifyDimens.spaceMD) {
            ForEach(0..<4, id: \.self) { _ in
                HStack(spacing: StreamifyDimens.spaceMD) {
                    RoundedRectangle(cornerRadius: StreamifyRadius.md)
                        .fill(StreamifyColors.bgChipInactive)
                        .frame(width: StreamifyDimens.trackRowArt, height: StreamifyDimens.trackRowArt)
                    VStack(alignment: .leading, spacing: StreamifyDimens.spaceSM) {
                        RoundedRectangle(cornerRadius: StreamifyRadius.sm)
                            .fill(StreamifyColors.bgChipInactive)
                            .frame(height: 12)
                        RoundedRectangle(cornerRadius: StreamifyRadius.sm)
                            .fill(StreamifyColors.bgChipInactive)
                            .frame(width: 140, height: 10)
                    }
                    Spacer(minLength: 0)
                }
                .padding(.horizontal, StreamifyDimens.spaceLG)
            }
        }
        .redacted(reason: .placeholder)
        .accessibilityLabel("Searching")
    }

    /// Deterministic fixture-scale listener count (djb2 — same artist, same
    /// number, always).
    private var monthlyListeners: String {
        var hash: UInt64 = 5381
        for scalar in artistName.unicodeScalars {
            hash = (hash &<< 5) &+ hash &+ UInt64(scalar.value)
        }
        let listeners = 84_000 + Int(hash % 3_916_000)
        if listeners >= 1_000_000 {
            let tenths = listeners / 100_000
            return "\(tenths / 10).\(tenths % 10)M"
        }
        return "\(listeners / 1_000)K"
    }
}

// MARK: - Stretchy header helper (file-local; AlbumDetailView inlines its own)

/// The classic stretchy-header pattern: on overscroll (minY > 0) the art grows
/// to fill the pulled gap (height + stretch) while offsetting up (-stretch) so
/// its top pins to the scroll edge. Reports the raw minY through a preference
/// so the host can drive the sticky condensed bar.
private struct StretchyHeader<Content: View>: View {
    let height: CGFloat
    let coordinateSpaceName: String
    let content: () -> Content

    init(height: CGFloat, coordinateSpaceName: String,
         @ViewBuilder content: @escaping () -> Content) {
        self.height = height
        self.coordinateSpaceName = coordinateSpaceName
        self.content = content
    }

    var body: some View {
        GeometryReader { geometry in
            let minY = geometry.frame(in: .named(coordinateSpaceName)).minY
            let stretch = max(0, minY)
            content()
                .frame(width: geometry.size.width, height: max(0, height + stretch))
                .offset(y: -stretch)
                .clipped()
                .preference(key: HeroStretchKey.self, value: minY)
        }
        .frame(height: height)
    }
}

private struct HeroStretchKey: PreferenceKey {
    static var defaultValue: CGFloat = 0
    static func reduce(value: inout CGFloat, nextValue: () -> CGFloat) { value = nextValue() }
}

#Preview("Artist detail — scripted live lane") {
    NavigationStack {
        ArtistDetailView(
            artistName: "Aurora Nine",
            scriptedResults: [
                SearchRow(id: "s1", title: "Bit-Perfect Rain", uploader: "Aurora Nine", durationMs: 198_000, kind: .song),
                SearchRow(id: "s2", title: "Silk Circuitry", uploader: "Aurora Nine", durationMs: 209_000, kind: .song),
                SearchRow(id: "s3", title: "Vellum Static", uploader: "Low Orbit Choir", durationMs: 312_000, kind: .song),
                SearchRow(id: "a1", title: "Meridian", uploader: "Aurora Nine", kind: .album),
            ]
        )
    }
    .preferredColorScheme(.dark)
}

#Preview("Artist detail — honest live lane") {
    NavigationStack {
        ArtistDetailView(artistName: "Aurora Nine")
    }
    .preferredColorScheme(.dark)
}
