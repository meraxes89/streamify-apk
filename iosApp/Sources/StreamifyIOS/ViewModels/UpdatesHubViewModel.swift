import Foundation
import Observation

// ─────────────────────────────────────────────────────────────────────────────
// SW-ENG4-01 (Engineer 4): UpdatesHubViewModel — aggregates the release radar
// notices for followed artists; the Swift mirror of `UpdatesHubScreen`'s state
// (`ReleaseWatcherApi.ReleaseWatch` + `PreSaveStore`).
//
// The harvest is one async load per appearance (per-artist failures swallow
// silently, exactly like the Kotlin sequential harvest — max 12 artists).
// Countdown chips recompute per display frame in the view from the stored
// ETAs (TimelineView) — the VIEW MODEL holds static data, never a ticking
// clock (no polling).
// ─────────────────────────────────────────────────────────────────────────────

@Observable
@MainActor
final class UpdatesHubViewModel {

    private(set) var watches: [ReleaseWatch] = []
    private(set) var isLoading = true
    private(set) var savedReleaseIds: Set<String> = []

    /// The stored load task handle. `nonisolated(unsafe)`: the handle is
    /// only mutated on the main actor (init / load()); `deinit` — which is
    /// always nonisolated — reads it solely to cancel, and `Task.cancel()`
    /// is itself thread-safe.
    nonisolated(unsafe) private var loadTask: Task<Void, Never>?

    private let library: any FeatureLibrary
    private let settings: SettingsStore

    init(
        library: any FeatureLibrary = AppServices.library,
        settings: SettingsStore = AppServices.settings
    ) {
        self.library = library
        self.settings = settings
        self.savedReleaseIds = settings.savedReleaseIds()
    }

    deinit {
        loadTask?.cancel()
    }

    // MARK: - Harvest

    func loadIfNeeded() {
        guard loadTask == nil else { return }
        Task { await load() }
    }

    func refresh() async {
        await load()
    }

    private func load() async {
        loadTask?.cancel()
        isLoading = watches.isEmpty
        let library = library
        loadTask = Task { [weak self] in
            let radar = await library.releaseRadar()
            guard !Task.isCancelled, let self else { return }
            self.watches = radar
            self.isLoading = false
        }
    }

    // MARK: - Pre-saves

    func isPreSaved(_ release: ReleaseCandidate) -> Bool {
        savedReleaseIds.contains(release.releaseId)
    }

    func togglePreSave(_ release: ReleaseCandidate) {
        if isPreSaved(release) {
            savedReleaseIds.remove(release.releaseId)
            settings.removeReleasePreSave(release.releaseId)
        } else {
            savedReleaseIds.insert(release.releaseId)
            settings.addReleasePreSave(release.releaseId)
        }
    }

    // MARK: - Projections

    /// The flattened feed: upcoming first (soonest ETA first), then live
    /// releases (newest first); pre-saved releases pin to the top of each
    /// group (mirror of the Kotlin feed derivation).
    var feed: [ReleaseCandidate] {
        let now = Date()
        let all = watches.flatMap(\.releases)
        // Upcoming: pre-saved pinned first, then soonest ETA (total order:
        // rank, then ETA, then id — deterministic under unstable sorts).
        let upcoming = all
            .filter { candidate in
                guard let eta = candidate.expectedAt else { return false }
                return eta > now
            }
            .sorted(by: upcomingOrder)
        // Live: pre-saved first, then most recently released.
        let live = all
            .filter { candidate in
                guard let eta = candidate.expectedAt else { return true }
                return eta <= now
            }
            .sorted(by: upcomingOrder)
        return upcoming + live
    }

    private func upcomingOrder(_ lhs: ReleaseCandidate, _ rhs: ReleaseCandidate) -> Bool {
        let now = Date()
        let lhRank = rank(lhs)
        let rhRank = rank(rhs)
        if lhRank != rhRank { return lhRank > rhRank }
        let lhsEta = lhs.expectedAt ?? now
        let rhsEta = rhs.expectedAt ?? now
        if lhsEta != rhsEta { return lhsEta < rhsEta }
        return lhs.releaseId < rhs.releaseId
    }

    private func rank(_ release: ReleaseCandidate) -> Int {
        savedReleaseIds.contains(release.releaseId) ? 1 : 0
    }

    var releaseCount: Int {
        watches.reduce(0) { $0 + $1.releases.count }
    }

    /// Countdown chip label for a release at reference time `now`
    /// (the view calls this per display frame with TimelineView's date).
    func countdownLabel(for release: ReleaseCandidate, at now: Date) -> String {
        guard let eta = release.expectedAt else { return "Out now" }
        return PresentationFormatting.countdownLabel(to: eta, from: now)
    }
}
