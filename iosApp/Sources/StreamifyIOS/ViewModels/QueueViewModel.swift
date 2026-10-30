import Foundation
import Observation

// ─────────────────────────────────────────────────────────────────────────────
// SW-ENG4-01 (Engineer 4): QueueViewModel — the live queue projection and the
// queue-mutation intent surface; the Swift mirror of the queue plane in
// `PlayerViewModel.kt` (reorderQueue / removeFromQueue / clearPlayedHistory /
// isAutoPlayEnabled), lifted into its own observable for the Queue screen.
//
// PUSH DISCIPLINE: queue STATE arrives exclusively through the
// `queueEvents` AsyncStream (rev-guarded full-state snapshots — the same
// MP-DEC-04 discipline as the playback plane; the engine owns queue truth,
// mutations dispatch and the next push repaints). No polling, no local
// reconciliation.
// ─────────────────────────────────────────────────────────────────────────────

@Observable
@MainActor
final class QueueViewModel {

    // ── Push-fed queue state (rev-guarded) ─────────────────────────────────

    private(set) var tracks: [PresentationTrack] = []
    private(set) var currentIndex = -1
    private(set) var isPlaying = false
    /// Infinite auto-mix (radio continuation) — engine-owned, UI-toggled.
    private(set) var autoMixEnabled = true

    private(set) var lastRev = 0
    nonisolated(unsafe) private var collectTask: Task<Void, Never>?

    private let library: any FeatureLibrary

    init(library: any FeatureLibrary = AppServices.library) {
        self.library = library
    }

    deinit {
        collectTask?.cancel()
    }

    // MARK: - Push collection

    /// Starts consuming the engine's queue snapshots (idempotent).
    func start() {
        guard collectTask == nil else { return }
        collectTask = Task { [weak self] in
            guard let stream = self?.library.queueEvents else { return }
            for await event in stream {
                guard let self else { return }
                switch event {
                case .snapshot(let snapshot):
                    self.ingest(snapshot)
                }
            }
        }
    }

    func stop() {
        collectTask?.cancel()
        collectTask = nil
    }

    /// One pushed snapshot applied atomically (stale revisions die here).
    private func ingest(_ snapshot: QueueSnapshot) {
        guard snapshot.rev > lastRev || lastRev == 0 else { return }
        lastRev = snapshot.rev
        tracks = snapshot.tracks
        currentIndex = snapshot.currentIndex
        isPlaying = snapshot.isPlaying
        autoMixEnabled = snapshot.autoMixEnabled
    }

    // MARK: - Projections (mirror of QueueListState)

    var current: PresentationTrack? {
        tracks.indices.contains(currentIndex) ? tracks[currentIndex] : nil
    }

    var upNext: [QueueEntry] {
        tracks.enumerated()
            .filter { $0.offset > currentIndex }
            .map { QueueEntry(index: $0.offset, track: $0.element) }
    }

    var played: [QueueEntry] {
        guard currentIndex >= 0 else { return [] }
        return tracks.enumerated()
            .filter { $0.offset < currentIndex }
            .map { QueueEntry(index: $0.offset, track: $0.element) }
    }

    // MARK: - Mutations (dispatch; the next push repaints)

    /// Drag-and-drop reorder (`.onMove`): moves the item at queue index
    /// `from` to queue index `to` (absolute engine queue indices).
    func move(from source: Int, to destination: Int) {
        guard tracks.indices.contains(source) else { return }
        let target = min(max(destination, 0), tracks.count)
        Task { await library.moveQueueItem(from: source, to: target) }
    }

    /// SwiftUI `IndexSet` variant of move (the List `.onMove` callback).
    func move(fromOffsets offsets: IndexSet, toOffset destination: Int) {
        guard let first = offsets.first, offsets.count == 1 else {
            // Multi-row drags: the shell lowers them row-by-row.
            for index in offsets {
                Task { await library.moveQueueItem(from: index, to: destination) }
            }
            return
        }
        // SwiftUI reports the destination in post-removal coordinates; map
        // it onto absolute queue indices (the Android moveMediaItem mapping).
        var target = destination
        if destination > first { target -= 1 }
        target = min(target, tracks.count - 1)
        move(from: first, to: target)
    }

    /// Swipe-to-delete (`.onDelete`).
    func remove(atOffsets offsets: IndexSet) {
        guard !offsets.isEmpty else { return }
        Task { await library.removeQueueItems(at: Array(offsets)) }
    }

    func remove(trackId: String) {
        guard let index = tracks.firstIndex(where: { $0.id == trackId }) else { return }
        Task { await library.removeQueueItems(at: [index]) }
    }

    func clearPlayedHistory() {
        Task { await library.clearPlayedHistory() }
    }

    func clearQueue() {
        let all = Array(tracks.indices)
        Task { await library.removeQueueItems(at: all) }
    }

    /// Toggles infinite auto-mix (radio continuation when the queue runs dry).
    func toggleAutoMix() {
        let next = !autoMixEnabled
        autoMixEnabled = next // optimistic; the next push confirms
        Task { await library.setAutoMix(enabled: next) }
    }
}
