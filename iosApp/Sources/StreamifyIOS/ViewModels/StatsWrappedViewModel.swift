import Foundation
import Observation

// ─────────────────────────────────────────────────────────────────────────────
// SW-ENG4-01 / SW-ENG4-08 (Engineer 4): StatsWrappedViewModel — listening
// minutes, top genres, milestone cards and monthly listening bars computed
// from the SQLite metrics plane; the Swift mirror of `WrappedStats` +
// `YtStatsTelemetryEngine` (loaded once, cached, force-refreshable — never
// polled).
// ─────────────────────────────────────────────────────────────────────────────

@Observable
@MainActor
final class StatsWrappedViewModel {

    enum State: Equatable {
        case loading
        case loaded(WrappedStats)
        case error(String)
    }

    private(set) var state: State = .loading

    private var loadTask: Task<Void, Never>?
    private let library: any FeatureLibrary

    init(library: any FeatureLibrary = AppServices.library) {
        self.library = library
    }

    deinit {
        loadTask?.cancel()
    }

    // MARK: - Load

    func loadIfNeeded() {
        guard loadTask == nil, case .loading = state else { return }
        Task { await load(forceRefresh: false) }
    }

    func refresh() async {
        await load(forceRefresh: true)
    }

    private func load(forceRefresh: Bool) async {
        loadTask?.cancel()
        let library = library
        loadTask = Task { [weak self] in
            let stats = await library.wrappedStats()
            guard !Task.isCancelled, let self else { return }
            self.state = .loaded(stats)
        }
        _ = forceRefresh // the engine's cache tier handles freshness policy
    }

    // MARK: - Projections

    private var stats: WrappedStats? {
        if case .loaded(let stats) = state { return stats }
        return nil
    }

    var totalMinutes: Int { stats?.totalMinutes ?? 0 }
    var totalTracks: Int { stats?.totalTracks ?? 0 }
    var likedSongs: Int { stats?.likedSongs ?? 0 }
    var topPlayedCount: Int { stats?.topPlayedCount ?? 0 }
    var averageBpm: Int { stats?.averageBpm ?? 124 }

    /// The BPM persona (mirror of the Kotlin bands: >=130 Kinetic Pulse
    /// Runner ⚡, 110-129 Harmonic Groove Weaver 🌌, else Midnight Lofi
    /// Dreamer 🌙).
    var persona: (name: String, emoji: String, description: String) {
        WrappedStats.persona(forAverageBpm: averageBpm)
    }

    var personaName: String { persona.name }
    var personaEmoji: String { persona.emoji }
    var personaDescription: String { persona.description }

    var topGenres: [StatsGenre] { stats?.topGenres ?? [] }
    var topTracks: [PresentationTrack] { stats?.topTracks ?? [] }
    var topArtists: [(name: String, plays: Int)] { stats?.topArtists ?? [] }
    var monthlyMinutes: [MonthlyListening] { stats?.monthlyMinutes ?? [] }
    var milestones: [StatsMilestone] { stats?.milestones ?? [] }

    var isLoading: Bool {
        if case .loading = state { return true }
        return false
    }

    /// The share card text (mirror of the Kotlin share template).
    var shareText: String {
        """
        🔥 My Streamify Wrapped: \(totalMinutes) Minutes Listened \
        (\(totalTracks) tracks, \(likedSongs) liked) \
        ✨ \(personaEmoji) \(personaName) \
        🎶 Top Genre: \(topGenres.first?.name ?? "—") \
        🎵 Top 5: \(topTracks.prefix(5).map(\.title).joined(separator: ", "))
        """
    }
}
