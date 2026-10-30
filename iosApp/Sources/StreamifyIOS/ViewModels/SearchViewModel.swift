import Foundation
import Observation

// ─────────────────────────────────────────────────────────────────────────────
// SW-ENG4-01 (Engineer 4): SearchViewModel — debounced query dispatch to the
// resolver lane (StreamResolver::search_tracks behind the FeatureLibrary
// seam), the Swift mirror of `SearchViewModel.kt`.
//
// DEBOUNCE (300 ms, structured concurrency — no Combine, no timers): each
// keystroke restarts a single debounce Task; only the last query survives.
// Tests inject a tiny interval so debouncing is asserted deterministically.
// Search history persists (cap 10) exactly like the Kotlin store.
// ─────────────────────────────────────────────────────────────────────────────

@Observable
@MainActor
final class SearchViewModel {

    // ── Search UI state (mirror of SearchUiState) ──────────────────────────

    enum State: Equatable {
        case idle
        case loading
        case success([SearchResult])
        case error(String)
    }

    private(set) var state: State = .idle
    private(set) var filter: SearchResultKind?
    private(set) var suggestions: [String] = []
    private(set) var isResolving = false
    /// The result row currently resolving a stream URL (tap feedback).
    private(set) var resolvingResultId: String?

    /// Search history, newest first (cap 10).
    private(set) var searchHistory: [String] = []

    // ── Debounce (the directive's 300 ms input debounce) ───────────────────

    static let debounceNs: UInt64 = 300_000_000
    /// Test seam: debounce interval (nanoseconds).
    let debounceIntervalNs: UInt64

    nonisolated(unsafe) private var searchTask: Task<Void, Never>?
    nonisolated(unsafe) private var suggestTask: Task<Void, Never>?
    nonisolated(unsafe) private var historyCommitTask: Task<Void, Never>?

    private let library: any FeatureLibrary
    private let settings: SettingsStore

    init(
        library: (any FeatureLibrary)? = nil,
        settings: SettingsStore? = nil,
        debounceIntervalNs: UInt64? = nil
    ) {
        let library = library ?? AppServices.library
        let settings = settings ?? AppServices.settings
        self.library = library
        self.settings = settings
        self.debounceIntervalNs = debounceIntervalNs ?? Self.debounceNs
        self.searchHistory = settings.searchHistory()
    }

    deinit {
        searchTask?.cancel()
        suggestTask?.cancel()
        historyCommitTask?.cancel()
    }

    // MARK: - Query entry points

    /// Debounced search entry: the query lands on the resolver after the
    /// input debounce; only the newest query survives a typing burst.
    func submit(query: String) {
        let trimmed = query.trimmingCharacters(in: .whitespacesAndNewlines)
        searchTask?.cancel()
        suggestTask?.cancel()
        guard !trimmed.isEmpty else {
            state = .idle
            suggestions = []
            return
        }
        state = .loading
        let filter = filter
        let library = library
        let debounceWindowNs = debounceIntervalNs
        searchTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: debounceWindowNs)
            guard !Task.isCancelled else { return }
            let results = await library.searchTracks(query: trimmed, filter: filter)
            guard !Task.isCancelled else { return }
            guard let self else { return }
            self.finish(query: trimmed, results: results)
        }
        suggestTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: debounceWindowNs)
            guard !Task.isCancelled else { return }
            let suggestions = await library.searchSuggestions(query: trimmed)
            guard !Task.isCancelled, let self else { return }
            self.suggestions = suggestions
        }
    }

    private func finish(query: String, results: [SearchResult]) {
        state = results.isEmpty
            ? .success([]) // honest empty, never a fabricated error
            : .success(results)
        commitHistory(query)
    }

    private func commitHistory(_ query: String) {
        historyCommitTask?.cancel()
        let settings = settings
        historyCommitTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: 1_000_000_000) // 1s commit delay (Kotlin parity)
            guard !Task.isCancelled else { return }
            settings.addSearchQuery(query)
            guard let self else { return }
            self.searchHistory = settings.searchHistory()
        }
    }

    // MARK: - Filters & history

    func setFilter(_ filter: SearchResultKind?) {
        self.filter = filter
    }

    func clearHistory() {
        settings.clearSearchHistory()
        searchHistory = []
    }

    func clear() {
        searchTask?.cancel()
        suggestTask?.cancel()
        state = .idle
        suggestions = []
    }

    // MARK: - Play wiring

    /// Marks a row as stream-resolving (tap feedback); the shell drives the
    /// actual play through the engine command plane.
    func beginResolving(_ result: SearchResult) {
        resolvingResultId = result.id
    }

    func endResolving() {
        resolvingResultId = nil
    }

    /// The engine-track projection of a tapped row.
    func track(for result: SearchResult) -> PresentationTrack {
        result.asPresentationTrack
    }

    // MARK: - Projections

    var currentResults: [SearchResult] {
        if case .success(let results) = state { return results }
        return []
    }

    var isLoading: Bool {
        if case .loading = state { return true }
        return false
    }
}
