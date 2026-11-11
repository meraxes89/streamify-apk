import SwiftUI

// ─────────────────────────────────────────────────────────────────────────────
// SW-ENG2-03 — CommunityHubView: the community-playlists hub. Native port of
// ui/screens/CommunityHubScreen.kt: header + subtitle, the live friend
// activity rail (Gap #32), a horizontal mood filter chip row, and a 2-column
// LazyVGrid of community playlist cards — seeded gradient cover, name,
// creator caption, likes + track counts — tapping opens through onOpen.
// Empty selections land on EmptyStateView.
//
// WS-B B2 (de-fixturing): the screen OWNS its CommunityViewModel and rides
// the live feature lane (`AppServices.library` → UniFFIFeatureLibrary when
// the engine is linked). Every row renders the VM's engine projections; the
// empty state is the HONEST engine state — no PreviewCatalog seeding on the
// production path (previews inject a scripted PreviewFeatureLibrary).
// ─────────────────────────────────────────────────────────────────────────────

@MainActor
public struct CommunityHubView: View {

    /// WS-B B2: the screen's own state holder — constructed bare, it rides
    /// the live feature lane automatically (load on-appear, pull-to-refresh;
    /// zero polling).
    @State private var model = CommunityViewModel()

    public var onOpen: (CommunityPlaylistModel) -> Void

    /// Ephemeral mood filter (nil = all playlists).
    @State private var selectedMoodId: String?

    /// The static mood taxonomy — design-system gradients, UI chrome (the
    /// mood → community shelf seam), NOT engine data; the Rust contract
    /// exposes no mood surface yet.
    private static let moods: [MoodGenre] = [
        MoodGenre(id: "pop", name: "Pop", gradient: StreamifyGradients.pop),
        MoodGenre(id: "hiphop", name: "Hip-Hop", gradient: StreamifyGradients.hipHop),
        MoodGenre(id: "rock", name: "Rock", gradient: StreamifyGradients.rock),
        MoodGenre(id: "indie", name: "Indie", gradient: StreamifyGradients.indie),
        MoodGenre(id: "workout", name: "Workout", gradient: StreamifyGradients.workout),
        MoodGenre(id: "focus", name: "Focus", gradient: StreamifyGradients.focus),
        MoodGenre(id: "chill", name: "Chill", gradient: StreamifyGradients.chill),
        MoodGenre(id: "sleep", name: "Sleep", gradient: StreamifyGradients.sleep),
    ]

    private let columns = [
        GridItem(.flexible(), spacing: StreamifyDimens.spaceLG),
        GridItem(.flexible(), spacing: StreamifyDimens.spaceLG)
    ]

    public init(
        onOpen: @escaping (CommunityPlaylistModel) -> Void = { _ in }
    ) {
        self.onOpen = onOpen
    }

    /// Preview/test lane: inject a scripted view model (the bare init above
    /// rides `AppServices.library`).
    init(
        model: CommunityViewModel,
        onOpen: @escaping (CommunityPlaylistModel) -> Void = { _ in }
    ) {
        self._model = State(initialValue: model)
        self.onOpen = onOpen
    }

    /// Deterministic mood assignment per playlist name (djb2 over the preset
    /// table discipline) so the chips have a stable filter target.
    private var filteredPlaylists: [CommunityPlaylist] {
        guard let selectedMoodId else { return model.playlists }
        let moodIndex = Self.moods.firstIndex { $0.id == selectedMoodId } ?? 0
        return model.playlists.filter { Self.moodIndex(for: $0.name, in: Self.moods.count) == moodIndex }
    }

    /// Honest pre-load state: idle (load not yet started) or in flight —
    /// the skeleton keeps the empty state from flashing before the first
    /// engine answer lands.
    private var isBootstrapping: Bool {
        model.state == .idle || model.state == .loading
    }

    public var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                headerBlock
                YtMoodFilterRail(moods: Self.moods, selected: $selectedMoodId)
                    .padding(.top, StreamifyDimens.spaceMD)
                if isBootstrapping {
                    loadingGrid
                } else if model.playlists.isEmpty && model.friendsActivity.isEmpty {
                    EmptyStateView(
                        symbol: StreamifyIcon.musicNoteList,
                        title: "No community playlists yet",
                        message: "Publish your custom playlist from the Library to see it here."
                    )
                    .frame(height: 420)
                } else {
                    if !model.friendsActivity.isEmpty {
                        friendsSection
                    }
                    playlistSection
                }
            }
            .padding(.bottom, StreamifyDimens.spaceGiant)
        }
        .background(StreamifyColors.bgBase)
        .navigationTitle("Community")
        .task { model.loadIfNeeded() }
        .refreshable { await model.refresh() }
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

    // MARK: - Friend activity (Gap #32 — renders only when the feed has rows)

    private var friendsSection: some View {
        VStack(alignment: .leading, spacing: StreamifyDimens.spaceSM) {
            Text("Friend activity")
                .font(StreamifyType.titleMedium)
                .foregroundStyle(StreamifyColors.textMain)
            Text("Live • what friends are playing right now")
                .font(StreamifyType.caption)
                .foregroundStyle(StreamifyColors.textSecondary)
            ForEach(model.friendsActivity) { friend in
                FriendActivityCard(friend: FriendActivityEntry(from: friend))
            }
        }
        .padding(.horizontal, StreamifyDimens.spaceLG)
        .padding(.top, StreamifyDimens.spaceLG)
    }

    // MARK: - Playlist grid (honest empty states)

    @ViewBuilder
    private var playlistSection: some View {
        if model.playlists.isEmpty {
            // Friends are live but the shared-shelf plane is empty — a calm
            // caption, not a second hero empty state.
            Text("No community playlists yet — publish one from your Library.")
                .font(StreamifyType.bodySmall)
                .foregroundStyle(StreamifyColors.textSecondary)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.horizontal, StreamifyDimens.spaceLG)
                .padding(.top, StreamifyDimens.spaceLG)
        } else if filteredPlaylists.isEmpty {
            EmptyStateView(
                symbol: StreamifyIcon.musicNoteList,
                title: "No playlists here",
                message: "Nothing matches this mood yet — clear the filter or try another chip."
            )
            .frame(height: 300)
        } else {
            LazyVGrid(columns: columns, spacing: StreamifyDimens.spaceXL) {
                ForEach(filteredPlaylists) { playlist in
                    CommunityPlaylistCard(
                        playlist: CommunityPlaylistModel(from: playlist),
                        onOpen: onOpen
                    )
                }
            }
            .padding(.horizontal, StreamifyDimens.spaceLG)
            .padding(.top, StreamifyDimens.spaceLG)
        }
    }

    /// The load-in-flight state — redacted card placeholders (the Android
    /// Compose CircularProgressIndicator in the native idiom).
    private var loadingGrid: some View {
        LazyVGrid(columns: columns, spacing: StreamifyDimens.spaceXL) {
            ForEach(0..<4, id: \.self) { _ in
                VStack(alignment: .leading, spacing: StreamifyDimens.spaceSM) {
                    RoundedRectangle(cornerRadius: StreamifyRadius.lg, style: .continuous)
                        .fill(StreamifyColors.bgChipInactive)
                        .frame(height: 120)
                    RoundedRectangle(cornerRadius: StreamifyRadius.sm, style: .continuous)
                        .fill(StreamifyColors.bgChipInactive)
                        .frame(height: 14)
                    RoundedRectangle(cornerRadius: StreamifyRadius.sm, style: .continuous)
                        .fill(StreamifyColors.bgChipInactive)
                        .frame(width: 90, height: 10)
                }
                .padding(StreamifyDimens.spaceMD)
                .cardSurface(hoverable: false)
            }
        }
        .padding(.horizontal, StreamifyDimens.spaceLG)
        .padding(.top, StreamifyDimens.spaceLG)
        .redacted(reason: .placeholder)
        .accessibilityLabel("Loading community")
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
    let scripted = PreviewFeatureLibrary()
    scripted.communityFeedContent = CommunityFeed(
        playlists: PreviewCatalog.communityPlaylists.map { playlist in
            CommunityPlaylist(
                id: playlist.id,
                creatorName: playlist.creatorName,
                name: playlist.name,
                description: playlist.description,
                coverUrl: playlist.coverUrl,
                likesCount: playlist.likesCount,
                trackCount: playlist.trackCount
            )
        },
        friendsActivity: PreviewCatalog.friends.map { friend in
            FriendActivity(
                userId: friend.userId,
                displayName: friend.displayName,
                trackTitle: friend.trackTitle,
                trackArtist: friend.trackArtist,
                lastActiveAt: friend.lastActiveLabel,
                sessionCode: friend.sessionCode
            )
        }
    )
    return NavigationStack {
        CommunityHubView(model: CommunityViewModel(library: scripted))
    }
    .preferredColorScheme(.dark)
}

#Preview("Community hub — empty") {
    NavigationStack {
        CommunityHubView()
    }
    .preferredColorScheme(.dark)
}
