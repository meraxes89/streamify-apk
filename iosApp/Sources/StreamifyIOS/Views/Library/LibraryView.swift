import SwiftUI

// ─────────────────────────────────────────────────────────────────────────────
// SW-ENG2-07 — Library: the segmented library hub (Playlists / Liked /
// Albums / Artists / Downloads). Native port of ui/screens/LibraryScreen.kt:
// the M3 filter rail becomes YtLibraryFilterChips over an internal segment
// index; the grid/list icon pair rides the trailing toolbar; every tab renders
// through the shared YT-style components (Lazy stacks/grids — 120 Hz discipline).
//
// WS-B B2 — fixture purge: liked tracks, playlists, albums and downloads are
// injectable props defaulting to honest empty (no PreviewCatalog seeding in
// the production path — the library has no engine surface yet); every segment
// degrades to its empty state. #Previews inject fixtures explicitly.
// ─────────────────────────────────────────────────────────────────────────────

/// One lowered playlist summary (the PlaylistRepository projection).
public struct LibraryPlaylistSummary: Identifiable, Hashable, Sendable {
    public let name: String
    public let count: Int
    public var id: String { name }
    public init(name: String, count: Int) {
        self.name = name
        self.count = count
    }
}

/// The five library segments (LibraryScreen's selectedFilter values).
private enum LibrarySegment: String, CaseIterable, Sendable {
    case playlists, liked, albums, artists, downloads
    var label: String { rawValue.capitalized }
}

public struct LibraryView: View {
    // WS-B B2: injectable, honestly empty by default — the shell (or a
    // future library surface) lowers real data onto these; previews inject
    // PreviewCatalog fixtures explicitly.
    public var likedTracks: [VirtualShelfTrack] = []
    public var playlists: [LibraryPlaylistSummary] = []
    public var albums: [VirtualShelfTrack] = []
    public var downloads: [DownloadTransfer] = []
    public var onPlayTrack: (VirtualShelfTrack) -> Void = { _ in }
    public var onOpenPlaylist: (String) -> Void = { _ in }
    public var onOpenAlbum: (String) -> Void = { _ in }
    public var onOpenArtist: (String) -> Void = { _ in }
    public var onCreatePlaylist: () -> Void = {}
    public var onSortTap: () -> Void = {}
    public var onFilterTap: () -> Void = {}

    @State private var segment: Int = LibrarySegment.allCases.firstIndex(of: .playlists) ?? 0
    @State private var isGrid = false

    private var currentSegment: LibrarySegment {
        LibrarySegment.allCases.indices.contains(segment) ? LibrarySegment.allCases[segment] : .playlists
    }

    public var body: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: StreamifyDimens.spaceXS) {
                YtLibraryFilterChips(filters: LibrarySegment.allCases.map(\.label), selected: segmentBinding, onSelect: { _ in })
                YtSortFilterBar(sortLabel: "Recent activity", onSortTap: onSortTap, onFilterTap: onFilterTap)
                segmentContent
            }
            .padding(.bottom, StreamifyDimens.spaceLG)
        }
        .background(StreamifyColors.bgBase)
        .navigationTitle("Library")
#if os(iOS)
        .navigationBarTitleDisplayMode(.inline)
#endif
        .toolbar {
            ToolbarItem(placement: .shellTrailing) {
                Button(action: onCreatePlaylist) { Image(systemName: StreamifyIcon.plus) }
                    .accessibilityLabel("Create playlist")
            }
            if currentSegment != .playlists { // Playlists is always grid.
                ToolbarItem(placement: .shellTrailing) {
                    Picker("View mode", selection: $isGrid) {
                        Image(systemName: StreamifyIcon.grid).tag(true)
                        Image(systemName: StreamifyIcon.list).tag(false)
                    }
                    .pickerStyle(.segmented).frame(width: 96)
                }
            }
        }
    }

    private var segmentBinding: Binding<String> {
        Binding(
            get: { currentSegment.label },
            set: { label in
                if let index = LibrarySegment.allCases.firstIndex(where: { $0.label == label }) { segment = index }
            }
        )
    }

    @ViewBuilder
    private var segmentContent: some View {
        switch currentSegment {
        case .playlists: playlistsSection
        case .liked: likedSection
        case .albums: albumsSection
        case .artists: artistsSection
        case .downloads: downloadsSection
        }
    }

    private let twoColumns = [GridItem(.flexible(), spacing: StreamifyDimens.spaceMD), GridItem(.flexible(), spacing: StreamifyDimens.spaceMD)]
    private let rowColumns = [GridItem(.adaptive(minimum: 160), spacing: StreamifyDimens.spaceSM)]

    @ViewBuilder
    private func gridOrList<Cell: View>(columns: [GridItem], @ViewBuilder cell: () -> Cell) -> some View {
        if isGrid {
            LazyVGrid(columns: columns, spacing: StreamifyDimens.spaceLG) { cell() }
                .padding(.horizontal, StreamifyDimens.spaceLG).padding(.top, StreamifyDimens.spaceSM)
        } else {
            LazyVStack(spacing: StreamifyDimens.spaceXS) { cell() }
                .padding(.horizontal, StreamifyDimens.spaceLG).padding(.top, StreamifyDimens.spaceSM)
        }
    }

    // Playlists always render as the 2-column grid; the liked-music auto
    // playlist spans it above.
    @ViewBuilder
    private var playlistsSection: some View {
        LazyVGrid(columns: twoColumns, spacing: StreamifyDimens.spaceLG) {
            YtLikedMusicCard(trackCount: likedTracks.count, onTap: { onOpenPlaylist("Liked Music") })
                .gridCellColumns(2)
            ForEach(playlists) { playlist in
                // YtMediumCard renders the mandated card: gradient cover, name, "N tracks".
                YtMediumCard(
                    track: VirtualShelfTrack(
                        id: playlist.name, title: playlist.name,
                        artist: "\(playlist.count) tracks", durationSec: 0
                    ),
                    onOpen: { onOpenPlaylist(playlist.name) }
                )
            }
        }
        .padding(.horizontal, StreamifyDimens.spaceLG).padding(.top, StreamifyDimens.spaceSM)
        if playlists.isEmpty {
            libraryEmptyState(
                symbol: StreamifyIcon.plus,
                title: "No playlists yet",
                message: "Playlists you create or import will collect here."
            )
        }
    }

    @ViewBuilder
    private var likedSection: some View {
        if likedTracks.isEmpty {
            libraryEmptyState(
                symbol: StreamifyIcon.heartFill,
                title: "No liked songs yet",
                message: "Songs you like will collect here."
            )
        } else {
            gridOrList(columns: rowColumns) {
                ForEach(likedTracks) { track in
                    TrackListItem(track: track) { onPlayTrack(track) }
                        .padding(.horizontal, isGrid ? StreamifyDimens.spaceSM : StreamifyDimens.spaceLG)
                }
            }
        }
    }

    @ViewBuilder
    private var albumsSection: some View {
        if albums.isEmpty {
            libraryEmptyState(
                symbol: StreamifyIcon.musicNoteList,
                title: "No albums yet",
                message: "Albums you save will collect here."
            )
        } else {
            gridOrList(columns: twoColumns) {
                ForEach(albums) { album in
                    if isGrid {
                        YtMediumCard(track: album) { onOpenAlbum(album.title) }
                    } else {
                        mediaRow(title: album.title, subtitle: album.artist, seed: album.title, symbol: StreamifyIcon.musicNoteList) { onOpenAlbum(album.title) }
                    }
                }
            }
        }
    }

    @ViewBuilder
    private var artistsSection: some View {
        if distinctArtists.isEmpty {
            libraryEmptyState(
                symbol: StreamifyIcon.avatar,
                title: "No artists yet",
                message: "Artists from your saved music will collect here."
            )
        } else {
            gridOrList(columns: [GridItem(.adaptive(minimum: 104), spacing: StreamifyDimens.spaceMD)]) {
                ForEach(distinctArtists, id: \.self) { artist in
                    if isGrid {
                        ArtistCircleCard(name: artist, onOpen: { onOpenArtist(artist) })
                    } else {
                        mediaRow(title: artist, subtitle: "Artist", seed: artist, symbol: StreamifyIcon.avatar) { onOpenArtist(artist) }
                    }
                }
            }
        }
    }

    private var distinctArtists: [String] {
        var seen = Set<String>()
        return likedTracks.compactMap { seen.insert($0.artist).inserted ? $0.artist : nil }
    }

    @ViewBuilder
    private var downloadsSection: some View {
        if settledDownloads.isEmpty {
            libraryEmptyState(
                symbol: StreamifyIcon.downloadFill,
                title: "No downloads yet",
                message: "Music you download for offline listening will collect here."
            )
        } else {
            gridOrList(columns: rowColumns) {
                ForEach(settledDownloads) { transfer in
                    downloadRow(transfer)
                        .padding(.horizontal, isGrid ? StreamifyDimens.spaceSM : StreamifyDimens.spaceLG)
                }
            }
        }
    }

    /// The settled (non-in-flight) transfers of the injectable download list
    /// — in-flight rows live on the Downloads screen (WS-B B2: honest empty
    /// on the real lane until the download surface lands).
    private var settledDownloads: [DownloadTransfer] {
        downloads.filter {
            switch $0.state {
            case .completed, .paused, .failed: return true
            case .queued, .downloading: return false
            }
        }
    }

    /// The per-segment honest-empty state (WS-B B2): every shelf degrades to
    /// this until its engine surface lands.
    private func libraryEmptyState(symbol: String, title: String, message: String) -> some View {
        EmptyStateView(symbol: symbol, title: title, message: message)
            .padding(.horizontal, StreamifyDimens.spaceLG)
            .padding(.top, StreamifyDimens.spaceLG)
            .padding(.bottom, StreamifyDimens.spaceXL)
    }

    /// A settled transfer row: download art + meta + state chip; tap plays its library mirror.
    private func downloadRow(_ transfer: DownloadTransfer) -> some View {
        Button {
            let track = likedTracks.first { $0.title == transfer.title }
                ?? VirtualShelfTrack(id: transfer.id, title: transfer.title, artist: transfer.artist, durationSec: 0)
            onPlayTrack(track)
        } label: {
            HStack(spacing: StreamifyDimens.spaceMD) {
                ZStack {
                    StreamifyShapes.thumbnailSmall.fill(StreamifyColors.bgChipInactive)
                    Image(systemName: StreamifyIcon.downloadFill).font(.system(size: 16)).foregroundStyle(StreamifyColors.textSecondary)
                }
                .frame(width: 44, height: 44)
                VStack(alignment: .leading, spacing: 2) {
                    Text(transfer.title).font(StreamifyType.songTitle).foregroundStyle(StreamifyColors.textMain).lineLimit(1)
                    Text("\(transfer.artist) · \(transfer.quality) · \(transfer.sizeMb, specifier: "%.1f") MB").font(StreamifyType.songArtist).foregroundStyle(StreamifyColors.textSecondary).lineLimit(1)
                }
                Spacer(minLength: StreamifyDimens.spaceSM)
                switch transfer.state {
                case .completed: stateText("Downloaded", tint: StreamifyColors.successGreen)
                case .paused: stateText("Paused", tint: StreamifyColors.warningAmber)
                case .failed: stateText("Failed", tint: StreamifyColors.errorRed)
                case .queued, .downloading: stateText("In progress", tint: StreamifyColors.textSecondary)
                }
            }
            .frame(minHeight: StreamifyDimens.trackRowHeight)
            .contentShape(Rectangle())
        }
        .buttonStyle(.pressableCard)
    }

    private func stateText(_ label: String, tint: Color) -> some View {
        Text(label).font(StreamifyType.caption.weight(.semibold)).foregroundStyle(tint).padding(.horizontal, 8).padding(.vertical, 3).background(StreamifyColors.bgChipInactive, in: Capsule())
    }

    private func mediaRow(title: String, subtitle: String, seed: String, symbol: String, onOpen: @escaping () -> Void) -> some View {
        Button(action: onOpen) {
            HStack(spacing: StreamifyDimens.spaceMD) {
                ZStack {
                    StreamifyShapes.thumbnailSmall.fill(StreamifyGradients.linear(StreamifyGradients.preset(forSeed: seed)))
                    Image(systemName: symbol).font(.system(size: 15)).foregroundStyle(Color.white.opacity(0.85))
                }
                .frame(width: 44, height: 44)
                VStack(alignment: .leading, spacing: 2) {
                    Text(title).font(StreamifyType.songTitle).foregroundStyle(StreamifyColors.textMain).lineLimit(1)
                    Text(subtitle).font(StreamifyType.songArtist).foregroundStyle(StreamifyColors.textSecondary).lineLimit(1)
                }
                Spacer(minLength: 0)
                Image(systemName: StreamifyIcon.chevronRight).font(.system(size: 12)).foregroundStyle(StreamifyColors.textTertiary)
            }
            .frame(minHeight: StreamifyDimens.trackRowHeight).contentShape(Rectangle())
        }
        .buttonStyle(.pressableCard)
    }
}

#Preview("Library — fixture lane") {
    NavigationStack {
        LibraryView(
            likedTracks: PreviewCatalog.tracks,
            playlists: [
                .init(name: "Obsidian Night Drive", count: 64),
                .init(name: "Focus Ladder", count: 48),
                .init(name: "Sunday Hardware", count: 81),
            ],
            albums: [
                .init(id: "album-meridian", title: "Meridian", artist: "Aurora Nine", durationSec: 0),
                .init(id: "album-obsidian-sunrise", title: "Obsidian Sunrise", artist: "Sireen", durationSec: 0),
            ],
            downloads: PreviewCatalog.downloads
        )
    }
}

#Preview("Library — honest empty (real lane today)") {
    NavigationStack { LibraryView() }
}
