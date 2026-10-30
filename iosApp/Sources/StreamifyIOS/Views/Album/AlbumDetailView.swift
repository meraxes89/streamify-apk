import SwiftUI

// ─────────────────────────────────────────────────────────────────────────────
// SW-ENG2-03 — AlbumDetailView: the album / playlist collection page.
// Native port of ui/screens/AlbumScreen.kt (PlaylistDetailScreen) with the
// shared parallax anatomy: a stretchy gradient hero (file-local helper — the
// artist screen keeps its own copy), kind label, title, artist / year / count
// row, Play + Shuffle capsules, heart / download / share row, indexed track
// rows, then "More from artist" as a YtMediumCard rail.
// ─────────────────────────────────────────────────────────────────────────────

public struct AlbumDetailView: View {

    public var title: String
    public var artist: String
    public var kind: String
    public var year: String?
    public var tracks: [VirtualShelfTrack]
    public var onPlayTrack: (VirtualShelfTrack) -> Void
    public var onOpenArtist: (String) -> Void
    public var onLike: ((Bool) -> Void)?
    public var onBack: (() -> Void)?

    @State private var isLiked = false
    @State private var isDownloaded = false
    private static let heroHeight: CGFloat = 300

    public init(
        title: String,
        artist: String,
        kind: String = "ALBUM",
        year: String? = nil,
        tracks: [VirtualShelfTrack] = PreviewCatalog.tracks,
        onPlayTrack: @escaping (VirtualShelfTrack) -> Void = { _ in },
        onOpenArtist: @escaping (String) -> Void = { _ in },
        onLike: ((Bool) -> Void)? = {},
        onBack: (() -> Void)? = {}
    ) {
        self.title = title
        self.artist = artist
        self.kind = kind
        self.year = year
        self.tracks = tracks
        self.onPlayTrack = onPlayTrack
        self.onOpenArtist = onOpenArtist
        self.onLike = onLike
        self.onBack = onBack
    }

    /// Other releases by this artist: online results first (deduped against
    /// the album's own titles), falling back to catalog tracks by the artist.
    private var moreFromArtist: [VirtualShelfTrack] {
        let online = PreviewCatalog.onlineResults
            .filter { $0.uploader == artist && $0.type != .album }
            .filter { result in !tracks.contains { $0.title == result.title } }
            .map { VirtualShelfTrack(id: $0.id, title: $0.title, artist: $0.uploader, durationSec: $0.durationSec, platformOrigin: "ytm") }
        if !online.isEmpty { return online }
        let albumIds = Set(tracks.map(\.id))
        return PreviewCatalog.tracks.filter { $0.artist == artist && !albumIds.contains($0.id) }
    }

    public var body: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 0) {
                StretchyHeader(height: Self.heroHeight, coordinateSpaceName: "albumScroll") {
                    heroArt
                }
                VStack(alignment: .leading, spacing: StreamifyDimens.spaceSM) {
                    Text(kind.uppercased())
                        .font(StreamifyType.caption.weight(.bold))
                        .tracking(1.2)
                        .foregroundStyle(StreamifyColors.textSecondary)
                    Text(title).font(StreamifyType.displayLarge)
                        .foregroundStyle(StreamifyColors.textMain).lineLimit(2)
                    metadataRow
                }
                .padding(.horizontal, StreamifyDimens.spaceLG).padding(.top, StreamifyDimens.spaceXL)
                actionRow
                utilityRow
                ForEach(Array(tracks.enumerated()), id: \.element.id) { index, track in
                    HStack(spacing: StreamifyDimens.spaceSM) {
                        Text("\(index + 1)").font(StreamifyType.seekbarTime.monospacedDigit())
                            .foregroundStyle(StreamifyColors.textTertiary)
                            .frame(width: 20, alignment: .trailing)
                        TrackListItem(track: track, onPlay: { onPlayTrack(track) })
                    }
                    .padding(.horizontal, StreamifyDimens.spaceLG)
                }
                if !moreFromArtist.isEmpty {
                    YtSectionHeader(title: "More from \(artist)")
                    moreRail
                }
            }
            .padding(.bottom, StreamifyDimens.spaceGiant)
        }
        .coordinateSpace(name: "albumScroll")
        .background(StreamifyColors.bgBase)
        .overlay(alignment: .topLeading) { backButton }
        .navigationTitle(title)
    }

    private var heroArt: some View {
        ZStack {
            StreamifyGradients.linear(StreamifyGradients.preset(forSeed: title))
            LinearGradient(colors: [.clear, Color.black.opacity(0.4)], startPoint: .top, endPoint: .bottom)
            Image(systemName: StreamifyIcon.musicNoteList)
                .font(.system(size: 104)).foregroundStyle(Color.white.opacity(0.92))
        }
    }

    private var metadataRow: some View {
        HStack(spacing: StreamifyDimens.spaceXS) {
            Button {
                onOpenArtist(artist)
            } label: {
                Text(artist).font(StreamifyType.bodyMedium.weight(.semibold))
                    .foregroundStyle(StreamifyColors.textMain)
            }
            .buttonStyle(.plain)
            if let year {
                Text("· \(year)").font(StreamifyType.bodySmall)
                    .foregroundStyle(StreamifyColors.textSecondary)
            }
            Text("· \(tracks.count) tracks").font(StreamifyType.bodySmall)
                .foregroundStyle(StreamifyColors.textSecondary)
        }
    }

    private var actionRow: some View {
        HStack(spacing: StreamifyDimens.spaceMD) {
            Button(action: playAlbum) {
                HStack(spacing: StreamifyDimens.spaceSM) {
                    Image(systemName: StreamifyIcon.play)
                    Text("Play")
                }
                .font(StreamifyType.titleSmall).foregroundStyle(StreamifyColors.textOnActiveChip)
                .padding(.horizontal, StreamifyDimens.spaceXXL).padding(.vertical, StreamifyDimens.spaceSM)
                .background(Capsule().fill(StreamifyColors.textMain))
            }
            .buttonStyle(.pressableCard)
            Button(action: shuffleAlbum) {
                HStack(spacing: StreamifyDimens.spaceSM) {
                    Image(systemName: StreamifyIcon.shuffle)
                    Text("Shuffle")
                }
                .font(StreamifyType.titleSmall).foregroundStyle(StreamifyColors.textMain)
                .padding(.horizontal, StreamifyDimens.spaceLG).padding(.vertical, StreamifyDimens.spaceSM)
                .background(Capsule().fill(StreamifyColors.bgChipInactive))
                .overlay(Capsule().strokeBorder(StreamifyColors.borderChip, lineWidth: 1))
            }
            .buttonStyle(.pressableCard)
            Spacer(minLength: 0)
        }
        .padding(.horizontal, StreamifyDimens.spaceLG)
        .padding(.top, StreamifyDimens.spaceXL)
    }

    private var utilityRow: some View {
        HStack(spacing: StreamifyDimens.spaceSM) {
            HeartButton(isLiked: isLiked) { liked in
                isLiked = liked
                onLike?(liked)
            }
            Button {
                Haptics.play(.queueDrop)
                isDownloaded.toggle()
            } label: {
                Image(systemName: isDownloaded ? StreamifyIcon.downloadFill : StreamifyIcon.download)
                    .font(.system(size: 20))
                    .foregroundStyle(isDownloaded ? StreamifyColors.primary : StreamifyColors.textSecondary)
                    .frame(width: 44, height: 44)
            }
            .buttonStyle(.plain)
            .accessibilityLabel(isDownloaded ? "Downloaded" : "Download")
            ShareLink(item: shareText) {
                Image(systemName: StreamifyIcon.share)
                    .font(.system(size: 20)).foregroundStyle(StreamifyColors.textSecondary)
                    .frame(width: 44, height: 44)
            }
            Spacer(minLength: 0)
        }
        .padding(.horizontal, StreamifyDimens.spaceLG)
        .padding(.top, StreamifyDimens.spaceXS)
    }

    private var moreRail: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: StreamifyDimens.spaceMD) {
                ForEach(moreFromArtist) { track in
                    YtMediumCard(track: track) { onPlayTrack(track) }
                }
            }
            .padding(.horizontal, StreamifyDimens.spaceLG).padding(.vertical, StreamifyDimens.spaceXS)
        }
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

    private func playAlbum() {
        if let first = tracks.first { onPlayTrack(first) }
    }

    private func shuffleAlbum() {
        if let pick = tracks.randomElement() { onPlayTrack(pick) }
    }

    private var shareText: String {
        "\(title) by \(artist) — found on Streamify"
    }
}

// MARK: - Stretchy header helper (file-local; ArtistDetailView keeps its own)

/// The classic stretchy-header pattern: on overscroll the art grows to fill
/// the pulled gap (height + stretch) while offsetting up (-stretch) so its
/// top pins to the scroll edge; scrolled up, it scrolls away untouched.
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
        }
        .frame(height: height)
    }
}

#Preview("Album detail") {
    NavigationStack {
        AlbumDetailView(title: "Meridian", artist: "Aurora Nine")
    }
    .preferredColorScheme(.dark)
}
