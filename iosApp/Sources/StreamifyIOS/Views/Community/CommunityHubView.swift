import SwiftUI

// ─────────────────────────────────────────────────────────────────────────────
// SW-ENG2-03 — CommunityHubView: the community-playlists hub. Native port of
// ui/screens/CommunityHubScreen.kt: header + subtitle, a horizontal mood
// filter chip row (YtMoodFilterRail over the mood fixtures), and a 2-column
// LazyVGrid of community playlist cards — seeded gradient cover, name,
// creator caption, likes + track counts, the collaborative person.2 badge —
// tapping opens through onOpen. Empty selections land on EmptyStateView.
// ─────────────────────────────────────────────────────────────────────────────

public struct CommunityHubView: View {

    public var playlists: [CommunityPlaylistModel]
    public var onOpen: (CommunityPlaylistModel) -> Void

    /// Ephemeral mood filter (nil = all playlists).
    @State private var selectedMoodId: String?

    private let moods = PreviewCatalog.moods
    private let columns = [
        GridItem(.flexible(), spacing: StreamifyDimens.spaceLG),
        GridItem(.flexible(), spacing: StreamifyDimens.spaceLG)
    ]

    public init(
        playlists: [CommunityPlaylistModel] = PreviewCatalog.communityPlaylists,
        onOpen: @escaping (CommunityPlaylistModel) -> Void = { _ in }
    ) {
        self.playlists = playlists
        self.onOpen = onOpen
    }

    /// Deterministic mood assignment per playlist name (djb2 over the preset
    /// table discipline) so the fixture chips have a stable filter target.
    private var filteredPlaylists: [CommunityPlaylistModel] {
        guard let selectedMoodId else { return playlists }
        let moodIndex = moods.firstIndex { $0.id == selectedMoodId } ?? 0
        return playlists.filter { Self.moodIndex(for: $0.name, in: moods.count) == moodIndex }
    }

    public var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                headerBlock
                YtMoodFilterRail(moods: moods, selected: $selectedMoodId)
                    .padding(.top, StreamifyDimens.spaceMD)
                if filteredPlaylists.isEmpty {
                    EmptyStateView(
                        symbol: StreamifyIcon.musicNoteList,
                        title: "No playlists here",
                        message: "Nothing matches this mood yet — clear the filter or try another chip."
                    )
                    .frame(height: 420)
                } else {
                    LazyVGrid(columns: columns, spacing: StreamifyDimens.spaceXL) {
                        ForEach(filteredPlaylists) { playlist in
                            CommunityPlaylistCard(playlist: playlist, onOpen: onOpen)
                        }
                    }
                    .padding(.horizontal, StreamifyDimens.spaceLG)
                    .padding(.top, StreamifyDimens.spaceLG)
                }
            }
            .padding(.bottom, StreamifyDimens.spaceGiant)
        }
        .background(StreamifyColors.bgBase)
        .navigationTitle("Community")
    }

    private var headerBlock: some View {
        VStack(alignment: .leading, spacing: StreamifyDimens.spaceXXS) {
            Text("Community playlists")
                .font(StreamifyType.displayMedium)
                .foregroundStyle(StreamifyColors.textMain)
            Text("Playlists built by listeners — join one, or leave your own mark.")
                .font(StreamifyType.bodySmall)
                .foregroundStyle(StreamifyColors.textSecondary)
        }
        .padding(.horizontal, StreamifyDimens.spaceLG)
        .padding(.top, StreamifyDimens.spaceLG)
    }

    /// Deterministic djb2 bucket — the same playlist always maps to the same
    /// mood chip, in previews and tests alike.
    private static func moodIndex(for name: String, in moodCount: Int) -> Int {
        var hash: UInt64 = 5381
        for scalar in name.unicodeScalars {
            hash = (hash &<< 5) &+ hash &+ UInt64(scalar.value)
        }
        return Int(hash % UInt64(max(moodCount, 1)))
    }
}

/// One community playlist card (Android: the LazyColumn item — YtThumbnail
/// art, name, creator, likes, collaborative badge).
private struct CommunityPlaylistCard: View {
    let playlist: CommunityPlaylistModel
    let onOpen: (CommunityPlaylistModel) -> Void

    var body: some View {
        Button {
            Haptics.play(.scrubTick)
            onOpen(playlist)
        } label: {
            VStack(alignment: .leading, spacing: StreamifyDimens.spaceSM) {
                cover
                Text(playlist.name)
                    .font(StreamifyType.cardTitle)
                    .foregroundStyle(StreamifyColors.textMain)
                    .lineLimit(2)
                    .multilineTextAlignment(.leading)
                HStack(spacing: StreamifyDimens.spaceXS) {
                    Image(systemName: StreamifyIcon.avatar)
                        .font(.system(size: 10))
                        .foregroundStyle(StreamifyColors.textSecondary)
                    Text("by \(playlist.creatorName)")
                        .font(StreamifyType.cardSubtitle)
                        .foregroundStyle(StreamifyColors.textSecondary)
                        .lineLimit(1)
                }
                HStack(spacing: StreamifyDimens.spaceSM) {
                    Label("\(playlist.likesCount)", systemImage: StreamifyIcon.heartFill)
                        .font(StreamifyType.caption)
                        .foregroundStyle(StreamifyColors.primary)
                    Text("\(playlist.trackCount) tracks")
                        .font(StreamifyType.caption)
                        .foregroundStyle(StreamifyColors.textTertiary)
                    Spacer(minLength: 0)
                }
            }
            .padding(StreamifyDimens.spaceMD)
            .cardSurface(hoverable: false)
        }
        .buttonStyle(.pressableCard)
        .accessibilityLabel(playlist.name)
    }

    private var cover: some View {
        ZStack {
            StreamifyGradients.linear(StreamifyGradients.preset(forSeed: playlist.name))
            Image(systemName: StreamifyIcon.musicNoteList)
                .font(.system(size: 32))
                .foregroundStyle(Color.white.opacity(0.5))
        }
        .frame(height: 120)
        .frame(maxWidth: .infinity)
        .clipShape(RoundedRectangle(cornerRadius: StreamifyRadius.lg, style: .continuous))
        .overlay(alignment: .topLeading) {
            if playlist.isCollaborative {
                collabBadge.padding(StreamifyDimens.spaceSM)
            }
        }
    }

    private var collabBadge: some View {
        HStack(spacing: 4) {
            Image(systemName: StreamifyIcon.friends)
                .font(.system(size: 9, weight: .bold))
            Text("Collab")
                .font(.system(size: 9, weight: .bold))
        }
        .foregroundStyle(Color.white)
        .padding(.horizontal, 7)
        .padding(.vertical, 4)
        .background(Color.black.opacity(0.55), in: StreamifyShapes.chip)
    }
}

#Preview("Community hub") {
    NavigationStack {
        CommunityHubView()
    }
    .preferredColorScheme(.dark)
}

#Preview("Community hub — empty") {
    NavigationStack {
        CommunityHubView(playlists: [])
    }
    .preferredColorScheme(.dark)
}
