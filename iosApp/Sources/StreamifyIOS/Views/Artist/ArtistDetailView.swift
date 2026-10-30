import SwiftUI

// ─────────────────────────────────────────────────────────────────────────────
// SW-ENG2-03 — ArtistDetailView: the artist page. Native port of
// ui/screens/ArtistScreen.kt with the YTM parallax anatomy the Android screen
// lacked: a stretchy GeometryReader hero (320 pt seeded gradient art that fills
// the overscroll), a sticky condensed bar whose gradient + artist name fade in
// once the scroll offset crosses the threshold (preference → @State), one-tap
// Follow, Play + Shuffle, "Popular" rows, an Albums rail and the Clips rail.
// ─────────────────────────────────────────────────────────────────────────────

public struct ArtistDetailView: View {

    public var artistName: String
    public var tracks: [VirtualShelfTrack]
    public var isFollowing: Bool
    public var onFollow: ((Bool) -> Void)?
    public var onPlayTrack: (VirtualShelfTrack) -> Void
    public var onOpenAlbum: (String) -> Void
    public var onBack: (() -> Void)?

    /// 0 → 1 as the hero collapses past the threshold (quantized).
    @State private var condenseProgress: Double = 0
    private static let heroHeight: CGFloat = 320
    private static let condenseThreshold: CGFloat = 180

    public init(
        artistName: String,
        tracks: [VirtualShelfTrack] = PreviewCatalog.tracks,
        isFollowing: Bool = false,
        onFollow: ((Bool) -> Void)? = { _ in },
        onPlayTrack: @escaping (VirtualShelfTrack) -> Void = { _ in },
        onOpenAlbum: @escaping (String) -> Void = { _ in },
        onBack: (() -> Void)? = {}
    ) {
        self.artistName = artistName
        self.tracks = tracks
        self.isFollowing = isFollowing
        self.onFollow = onFollow
        self.onPlayTrack = onPlayTrack
        self.onOpenAlbum = onOpenAlbum
        self.onBack = onBack
    }

    private var artistTracks: [VirtualShelfTrack] {
        tracks.filter { $0.artist.localizedCaseInsensitiveContains(artistName) }
    }

    private var artistClips: [ArtistClipModel] {
        PreviewCatalog.artistClips.filter { $0.artistName == artistName }
    }

    /// Online album releases by this artist, then the artist's own tracks as
    /// fixture stand-ins (deduped by title).
    private var albumCards: [VirtualShelfTrack] {
        let online = PreviewCatalog.onlineResults
            .filter { $0.uploader == artistName && $0.type == .album }
            .map { VirtualShelfTrack(id: $0.id, title: $0.title, artist: $0.uploader, durationSec: $0.durationSec, platformOrigin: "ytm") }
        let known = Set(online.map(\.title))
        return online + artistTracks.filter { !known.contains($0.title) }
    }

    // MARK: Body

    public var body: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 0) {
                StretchyHeader(height: Self.heroHeight, coordinateSpaceName: "artistScroll") {
                    heroArt
                }
                headerSection
                YtSectionHeader(title: "Popular", subtitle: "\(artistTracks.count) tracks")
                ForEach(artistTracks) { track in
                    TrackListItem(track: track, onPlay: { onPlayTrack(track) })
                        .padding(.horizontal, StreamifyDimens.spaceLG)
                }
                if !albumCards.isEmpty {
                    YtSectionHeader(title: "Albums", subtitle: artistName)
                    albumsRail
                }
                if !artistClips.isEmpty {
                    VerticalClipsRail(clips: artistClips)
                        .padding(.top, StreamifyDimens.spaceXL)
                }
            }
            .padding(.bottom, StreamifyDimens.spaceGiant)
        }
        .coordinateSpace(name: "artistScroll")
        .background(StreamifyColors.bgBase)
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
        if let first = artistTracks.first { onPlayTrack(first) }
    }

    private func shufflePopular() {
        if let pick = artistTracks.randomElement() { onPlayTrack(pick) }
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

#Preview("Artist detail") {
    NavigationStack {
        ArtistDetailView(artistName: "Aurora Nine")
    }
    .preferredColorScheme(.dark)
}
