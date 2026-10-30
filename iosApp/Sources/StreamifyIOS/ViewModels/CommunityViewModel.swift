import Foundation
import Observation

// ─────────────────────────────────────────────────────────────────────────────
// SW-ENG4-01 (Engineer 4): CommunityViewModel — friend activity feeds and
// public user-curated playlists; the Swift mirror of `CommunityViewModel.kt`.
//
// Loads on-appear + pull-to-refresh (the Android 30 s poll is deliberately
// NOT ported: refreshes are user-driven or event-driven — zero polling
// anywhere in the state layer).
// ─────────────────────────────────────────────────────────────────────────────

@Observable
@MainActor
final class CommunityViewModel {

    enum State: Equatable {
        case idle
        case loading
        case loaded(CommunityFeed)
        case error(String)
    }

    private(set) var state: State = .idle
    private(set) var isRefreshing = false

    private var loadTask: Task<Void, Never>?

    private let library: any FeatureLibrary

    init(library: any FeatureLibrary = AppServices.library) {
        self.library = library
    }

    deinit {
        loadTask?.cancel()
    }

    // MARK: - Load

    /// Loads the community feed once per appearance (guards a load already
    /// in flight).
    func loadIfNeeded() {
        guard loadTask == nil, case .idle = state else { return }
        Task { await load() }
    }

    /// Pull-to-refresh / manual reload.
    func refresh() async {
        await load()
    }

    private func load() async {
        guard !isRefreshing else { return }
        isRefreshing = state != .idle
        state = .loading

        let feed = await library.communityFeed()
        if feed.playlists.isEmpty && feed.friendsActivity.isEmpty && feed.activeBroadcasts.isEmpty {
            state = .loaded(feed) // honest empty — a fresh install is not an error
        } else {
            state = .loaded(feed)
        }
        isRefreshing = false
    }

    // MARK: - Projections

    var friendsActivity: [FriendActivity] {
        if case .loaded(let feed) = state { return feed.friendsActivity }
        return []
    }

    var playlists: [CommunityPlaylist] {
        if case .loaded(let feed) = state { return feed.playlists }
        return []
    }

    var activeBroadcasts: [String] {
        if case .loaded(let feed) = state { return feed.activeBroadcasts }
        return []
    }

    var isLoading: Bool {
        if case .loading = state { return true }
        return false
    }

    /// Seed artists for the Blend flow, derived from a friend activity row
    /// (the Kotlin `trackArtist.split(",")` heuristic).
    static func blendSeeds(from friend: FriendActivity) -> [String] {
        friend.trackArtist
            .split(whereSeparator: { $0 == "," || $0 == "•" })
            .map { $0.trimmingCharacters(in: .whitespaces) }
            .filter { !$0.isEmpty }
    }
}
