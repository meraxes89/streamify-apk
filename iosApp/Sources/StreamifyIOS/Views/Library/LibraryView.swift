import SwiftUI

// ─────────────────────────────────────────────────────────────────────────────
// SW-ENG2-07 — Library: the segmented library hub (Playlists / Liked /
// Albums / Artists / Downloads). Native port of ui/screens/LibraryScreen.kt:
// the M3 filter rail becomes YtLibraryFilterChips over an internal segment
// index; the grid/list icon pair rides the trailing toolbar; every tab renders
// through the shared YT-style components (Lazy stacks/grids — 120 Hz discipline).
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
    public var likedTracks: [VirtualShelfTrack] = PreviewCatalog.tracks
    public var playlists: [LibraryPlaylistSummary] = [.init(name: "Obsidian Night Drive", count: 64), .init(name: "Focus Ladder", count: 48), .init(name: "Sunday Hardware", count: 81)]
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

    // Playlists always render as the 2-column grid; the liked-music auto playlist spans it above.
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
    }

    private var likedSection: some View {
        gridOrList(columns: rowColumns) {
            ForEach(likedTracks) { track in
                TrackListItem(track: track) { onPlayTrack(track) }
                    .padding(.horizontal, isGrid ? StreamifyDimens.spaceSM : StreamifyDimens.spaceLG)
            }
        }
    }

    // Albums are fixture-derived: VirtualShelfTrack carries no album column,
    // so the catalog's album vocabulary stands in for LibraryScreen's distinct-album shelf.
    private var albumsSection: some View {
        gridOrList(columns: twoColumns) {
            ForEach(derivedAlbums) { album in
                if isGrid {
                    YtMediumCard(track: album) { onOpenAlbum(album.title) }
                } else {
                    mediaRow(title: album.title, subtitle: album.artist, seed: album.title, symbol: StreamifyIcon.musicNoteList) { onOpenAlbum(album.title) }
                }
            }
        }
    }

    private var derivedAlbums: [VirtualShelfTrack] {
        let presentArtists = Set(likedTracks.map(\.artist))
        let catalog: [(name: String, artist: String)] = [
            ("Meridian", "Aurora Nine"),
            ("Obsidian Sunrise", "Sireen"),
            ("Neon Hallway", "Vetra"),
            ("48k Meridian", "DAC Kingsley"),
        ]
        return catalog
        .filter { presentArtists.contains($0.artist) }
        .map { VirtualShelfTrack(id: "album-\($0.name)", title: $0.name, artist: $0.artist, durationSec: 0) }
    }

    private var artistsSection: some View {
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

    private var distinctArtists: [String] {
        var seen = Set<String>()
        return likedTracks.compactMap { seen.insert($0.artist).inserted ? $0.artist : nil }
    }

    private var downloadsSection: some View {
        gridOrList(columns: rowColumns) {
            ForEach(downloadTransfers) { transfer in
                downloadRow(transfer)
                    .padding(.horizontal, isGrid ? StreamifyDimens.spaceSM : StreamifyDimens.spaceLG)
            }
        }
    }

    private var downloadTransfers: [DownloadTransfer] {
        PreviewCatalog.downloads.filter {
            switch $0.state {
            case .completed, .paused, .failed: return true
            case .queued, .downloading: return false
            }
        }
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

#Preview("Library") {
    NavigationStack { LibraryView() }
}
