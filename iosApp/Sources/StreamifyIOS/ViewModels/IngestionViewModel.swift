import Foundation
import Observation

// ─────────────────────────────────────────────────────────────────────────────
// SW-ENG4-01 (Engineer 4): IngestionViewModel — progress for external
// playlist imports (Spotify links, Exportify JSON); the Swift mirror of
// `IngestionViewModel.kt` (the WorkManager download-flow projection).
//
// PUSH DISCIPLINE: progress arrives exclusively over the `ingestionEvents`
// AsyncStream (the WorkManager `getWorkInfosByTagFlow` counterpart) — no
// polling of any kind. Enqueued imports are fire-and-forget intents; the
// next pushed event repaints the row.
// ─────────────────────────────────────────────────────────────────────────────

@Observable
@MainActor
final class IngestionViewModel {

    private(set) var tasks: [IngestionTask] = []

    nonisolated(unsafe) private var collectTask: Task<Void, Never>?

    private let library: any FeatureLibrary

    init(library: (any FeatureLibrary)? = nil) {
        self.library = library ?? AppServices.library
    }

    deinit {
        collectTask?.cancel()
    }

    // MARK: - Push collection

    func start() {
        guard collectTask == nil else { return }
        collectTask = Task { [weak self] in
            guard let stream = self?.library.ingestionEvents else { return }
            for await event in stream {
                guard let self else { return }
                switch event {
                case .taskUpdated(let task):
                    self.applyTask(task)
                case .taskRemoved(let taskId):
                    self.tasks.removeAll { $0.id == taskId }
                }
            }
        }
    }

    func stop() {
        collectTask?.cancel()
        collectTask = nil
    }

    private func applyTask(_ task: IngestionTask) {
        if let index = tasks.firstIndex(where: { $0.id == task.id }) {
            tasks[index] = task
        } else {
            tasks.insert(task, at: 0)
        }
        // Terminal states drop out of the active list after a beat: keep
        // them visible (the summary counts read them) but stop them from
        // counting as active.
    }

    // MARK: - Import intents

    /// Enqueues an external playlist import (a Spotify share URL or an
    /// Exportify JSON source). Returns false when the queue refuses.
    @discardableResult
    func importPlaylist(named name: String, source: String) async -> Bool {
        await library.enqueuePlaylistImport(named: name, source: source)
    }

    func cancel(taskId: String) {
        Task { await library.cancelIngestion(taskId: taskId) }
    }

    // MARK: - Projections

    /// Aggregate progress fraction across ACTIVE tasks in [0, 1]
    /// (nil when nothing is running — the shell renders no bar, not 0%).
    var aggregateProgress: Double? {
        let active = tasks.filter { $0.state == .running || $0.state == .queued }
        guard !active.isEmpty else { return nil }
        let total = active.reduce(0.0) { $0 + $1.progressFraction }
        return min(total / Double(active.count), 1)
    }

    var activeCount: Int {
        tasks.filter { $0.state == .running || $0.state == .queued }.count
    }

    var completedCount: Int {
        tasks.filter { $0.state == .completed }.count
    }

    var failedCount: Int {
        tasks.filter {
            if case .failed = $0.state { return true }
            return false
        }.count
    }

    var hasActivity: Bool { !tasks.isEmpty }
}
