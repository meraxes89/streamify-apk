import Foundation
import Observation

// ─────────────────────────────────────────────────────────────────────────────
// SW-ENG4-01 (Engineer 4): HomeViewModel — assembles the personalized feed
// (session recommendations, circadian mood playlists, made-for-you mixes,
// recent listening history, trending) through the feature seam; the Swift
// mirror of `HomeViewModel.kt`.
//
// CIRCADIAN SLOTS mirror `DaylistTimeBuckets` (morning 6-10, afternoon
// 11-16, evening 17-21, late-night else) with the same slot titles. Loads
// are pull-on-demand (on-appear + pull-to-refresh) — no polling loop.
// ─────────────────────────────────────────────────────────────────────────────

@Observable
@MainActor
final class HomeViewModel {

    enum State: Equatable {
        case loading
        case loaded(HomeFeed)
        case error(String)
    }

    private(set) var state: State = .loading
    private(set) var isRefreshing = false

    /// The circadian slot for the current hour (drives the hero section).
    private(set) var circadianSlot: CircadianSlot = .lateNight

    nonisolated(unsafe) private var loadTask: Task<Void, Never>?

    private let library: any FeatureLibrary

    init(library: (any FeatureLibrary)? = nil) {
        self.library = library ?? AppServices.library
        self.circadianSlot = CircadianSlot.slot(forHour: Calendar.current.component(.hour, from: Date()))
    }

    deinit {
        loadTask?.cancel()
    }

    // MARK: - Load

    /// Loads the feed once per appearance (idempotent while in flight).
    func loadIfNeeded() {
        guard loadTask == nil else { return }
        guard case .loading = state else { return }
        Task { await load() }
    }

    /// Pull-to-refresh: recomputes the circadian slot, reloads the feed.
    func refresh() async {
        circadianSlot = CircadianSlot.slot(forHour: Calendar.current.component(.hour, from: Date()))
        await load()
    }

    private func load() async {
        guard !isRefreshing else { return }
        isRefreshing = true
        defer { isRefreshing = false }

        let feed = await library.homeFeed(hourOfDay: Calendar.current.component(.hour, from: Date()))
        if feed.isEmpty {
            // Honest empty feed: the shell renders an empty state, not an
            // error (a fresh install is not a failure).
            state = .loaded(feed)
        } else {
            state = .loaded(feed)
        }
    }

    // MARK: - Projections

    var feed: HomeFeed? {
        if case .loaded(let feed) = state { return feed }
        return nil
    }

    var sessionRecommendations: [PresentationTrack] { feed?.sessionRecommendations ?? [] }
    var circadianRecommendations: [PresentationTrack] { feed?.circadianRecommendations ?? [] }
    var madeForYou: [PresentationTrack] { feed?.madeForYou ?? [] }
    var recent: [PresentationTrack] { feed?.recent ?? [] }
    var topPlayed: [PresentationTrack] { feed?.topPlayed ?? [] }
    var trending: [PresentationTrack] { feed?.trending ?? [] }
    var dailyMixes: [DailyMix] { feed?.dailyMixes ?? [] }

    var isLoading: Bool {
        if case .loading = state { return true }
        return false
    }
}
