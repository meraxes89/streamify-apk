import SwiftUI

// ─────────────────────────────────────────────────────────────────────────────
// SW-ENG2-03 — AlbumDetailView: the album / playlist collection page.
// Native port of ui/screens/AlbumScreen.kt (PlaylistDetailScreen) with the
// shared parallax anatomy: a stretchy gradient hero (file-local helper — the
// artist screen keeps its own copy), kind label, title, artist / year / count
// row, Play + Shuffle capsules, heart / download / share row, indexed track
// rows, then "More from artist" as a YtMediumCard rail.
//
// WS-B B2 — fixture purge: the track list sources from LIVE SEARCH by album
// title (the collection page's only engine surface) through an internal
// SearchViewModel; the injectable `tracks` param defaults to empty and is a
// preview/test lane only. No PreviewCatalog in the production path.
// ─────────────────────────────────────────────────────────────────────────────

@MainActor
public struct AlbumDetailView: View {

    public var title: String
    public var artist: String
    public var kind: String
    public var year: String?
    /// Preview/test lane only: injected rows render as-is; when empty (the
    /// production default) the live search task populates the list.
    public var tracks: [VirtualShelfTrack]
    public var onPlayTrack: (VirtualShelfTrack) -> Void
    public var onOpenArtist: (String) -> Void
    public var onLike: ((Bool) -> Void)?
    public var onBack: (() -> Void)?

    /// WS-B B2: the live "search by name" loader behind the track list.
    @State private var search = SearchViewModel()
    @State private var isLiked = false
    @State private var isDownloaded = false
    private static let heroHeight: CGFloat = 300

    public init(
        title: String,
        artist: String,
        kind: String = "ALBUM",
        year: String? = nil,
        tracks: [VirtualShelfTrack] = [],
        onPlayTrack: @escaping (VirtualShelfTrack) -> Void = { _ in },
        onOpenArtist: @escaping (String) -> Void = { _ in },
        onLike: ((Bool) -> Void)? = { _ in },
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

    // MARK: - Row sources (WS-B B2)

    /// The rendered track list: injected rows (previews/tests) when present,
    /// otherwise the live search rows for the album title.
    private var displayTracks: [VirtualShelfTrack] {
        if !tracks.isEmpty { return tracks }
        return songRows
    }

    /// Live song rows for the album's title, mapped onto the view currency.
    private var songRows: [VirtualShelfTrack] {
        search.currentResults
            .filter { $0.kind == .song }
            .map { VirtualShelfTrack(presentationTrack: $0.asPresentationTrack) }
    }

    /// Other releases by this artist: live search rows outside the album's
    /// own track list, deduped by title — no catalog fallback (WS-B B2).
    private var moreFromArtist: [VirtualShelfTrack] {
        guard !artist.isEmpty else { return [] }
        var known = Set(displayTracks.map(\.title))
        known.insert(title)
        return search.currentResults
            .filter { $0.kind != .song }
            .filter { $0.uploader.localizedCaseInsensitiveContains(artist) }
            .filter { !known.contains($0.title) }
            .map { VirtualShelfTrack(presentationTrack: $0.asPresentationTrack) }
    }

    /// True until the first live result lands (`.idle` only ever precedes
    /// the initial submit) — keeps the empty state from flashing mid-flight.
    private var isAwaitingResults: Bool {
        if !tracks.isEmpty { return false }
        if search.isLoading { return true }
        if case .idle = search.state { return true }
        return false
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
                ForEach(Array(displayTracks.enumerated()), id: \.element.id) { index, track in
                    HStack(spacing: StreamifyDimens.spaceSM) {
                        Text("\(index + 1)").font(StreamifyType.seekbarTime.monospacedDigit())
                            .foregroundStyle(StreamifyColors.textTertiary)
                            .frame(width: 20, alignment: .trailing)
                        TrackListItem(track: track, onPlay: { onPlayTrack(track) })
                    }
                    .padding(.horizontal, StreamifyDimens.spaceLG)
                }
                if displayTracks.isEmpty {
                    if isAwaitingResults {
                        trackSkeleton
                    } else {
                        EmptyStateView(
                            symbol: StreamifyIcon.musicNoteList,
                            title: "No tracks found",
                            message: "Nothing matched \"\(title)\" on the resolver lane yet."
                        )
                        .padding(.horizontal, StreamifyDimens.spaceLG)
                        .padding(.top, StreamifyDimens.spaceXL)
                    }
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
        .task {
            // WS-B B2: live search by album title is the collection page's
            // only engine surface; injected rows (previews/tests) skip it.
            guard tracks.isEmpty else { return }
            search.submit(query: title)
        }
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
            Text("· \(displayTracks.count) tracks").font(StreamifyType.bodySmall)
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
        if let first = displayTracks.first { onPlayTrack(first) }
    }

    private func shuffleAlbum() {
        if let pick = displayTracks.randomElement() { onPlayTrack(pick) }
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

#Preview("Album detail — fixture tracks (preview lane)") {
    NavigationStack {
        AlbumDetailView(title: "Meridian", artist: "Aurora Nine", tracks: PreviewCatalog.tracks)
    }
    .preferredColorScheme(.dark)
}

#Preview("Album detail — honest live lane") {
    NavigationStack {
        AlbumDetailView(title: "Meridian", artist: "Aurora Nine")
    }
    .preferredColorScheme(.dark)
}
